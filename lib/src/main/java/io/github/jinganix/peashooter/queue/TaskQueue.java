/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * https://github.com/jinganix/peashooter
 */

package io.github.jinganix.peashooter.queue;

import io.github.jinganix.peashooter.internal.Interruptions;
import io.github.jinganix.peashooter.trace.ErrorPolicy;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ordered task queue: one runner drains tasks strictly in submission order for this instance.
 *
 * <p>The private {@code lock} monitor is held only to enqueue, dequeue, and hand off between {@link
 * Executor}s; task bodies run outside the lock so submitters and executors contend minimally.
 *
 * <p><b>Executor submission failures (fail-fast, never discard-all):</b> when {@link
 * Executor#execute(Runnable)} throws while scheduling the queue runner, only the triggering
 * submission fails: its {@link RejectionAware} callback (if any) is notified and the original
 * failure (usually {@link RejectedExecutionException}) propagates to the triggering submitter. The
 * rest of the backlog is preserved in order for the next submission to resume. A submission that
 * only joined an in-flight runner claim (because another submit had already claimed it) is part of
 * that preserved backlog: it is neither notified nor dropped, and it runs when a later submission
 * for the same queue resumes the drain. A saturated pool therefore fails one submit, never discards
 * the key's backlog.
 *
 * <p><b>Task body failures (at-most-once):</b> ordinary {@link Throwable}s from a task {@link
 * Runnable} are logged and the queue continues with the next task; the dequeued head is never
 * retried. {@link Error} propagates instead: the runner dies loudly while the runner claim is
 * released, so the remaining backlog is preserved for the next explicit submit rather than stalling
 * behind a dead runner. Exception propagation is otherwise the caller's responsibility (e.g. {@link
 * io.github.jinganix.peashooter.executor.OrderedTraceExecutor} wraps sync delegates in a {@link
 * java.util.concurrent.CompletableFuture}).
 *
 * <p><b>Subclass contract (internal, not public API; compiler-enforced):</b> queue state ({@code
 * head}/{@code tail}/{@code size}, {@code runner}, {@code current}) is private and guarded by the
 * private {@code lock} monitor. Subclasses must never touch the deque, the runner, or the claim
 * directly and must never synchronize on them. Extension is limited to this package by the
 * compiler, not by documentation: the template methods ({@link #tryClaimRunnerLocked}, {@link
 * #onEnqueueLocked}, {@link #onEnqueued}, {@link #onRunnerFinished}, {@link #describeQueue}, {@link
 * #notifyDiscarded}, {@link #run}) are package-private so a type outside {@code
 * io.github.jinganix.peashooter.queue} cannot override or invoke them (a same-named declaration
 * outside the package is a new method, never an override, and the queue body keeps dispatching to
 * the in-package implementation), and the public entry points ({@link #execute}, {@link #isIdle},
 * {@link #hasPending}) are final. The atomic drain helpers below are likewise package-private for
 * the in-package queues only ({@link LockableTaskQueue} and the provider pins). Combinations of
 * subclass pin state with queue state use only the narrow atomic helpers ({@code
 * pinForSubmit}/{@code syncPin}/{@code abortPin} for expiry pins, {@code markUnlocking}/{@code
 * finishUnlock} for external-lock release): no generic lock rental exists.
 */
public class TaskQueue {

  private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);

  /**
   * Intrusive FIFO of pending tasks, guarded by {@link #lock}.
   *
   * <p>An intrusive doubly-linked list (links live on {@link Task}) instead of an {@link
   * ArrayDeque}: removal of a known task ({@link #removeTrigger}) unlinks via its stored node
   * reference in O(1) instead of scanning under the monitor in O(n). Kept deliberately: the manual
   * links are only touched under the queue monitor, every mutation asserts {@link
   * #checkInvariantsLocked()}, and {@link TaskQueueBenchmarkTest} measures the drain path so a
   * collection swap's throughput cost stays observable (measurement only: the benchmark logs a WARN
   * envelope instead of failing, and CI skips it).
   */
  @GuardedBy("lock")
  private Task head;

  @GuardedBy("lock")
  private Task tail;

  @GuardedBy("lock")
  private int size;

  /** Queue runner handed to the executor; shared by {@link #execute} and inline handoff. */
  private final Runnable runner;

  /** current {@link Executor}, guarded by {@link #lock}. */
  @GuardedBy("lock")
  private Executor current;

  /**
   * Dedicated queue monitor. Private and final: never exposed, never replaced. All queue-state
   * critical sections synchronize on this object; collaborators use only the narrow atomic helpers.
   */
  private final Object lock = new Object();

  /**
   * External-lock release gate for {@link LockableTaskQueue} batches. Guarded by {@link #lock}.
   * Owned here (not in {@link LockBatch}) so release transitions update the gate and the runner
   * claim atomically without renting the monitor.
   */
  @GuardedBy("lock")
  private boolean unlocking;

  /** Constructor. */
  public TaskQueue() {
    // Stores (never invokes) the overridable run: PinnedTaskQueue.run only adds a post-drain
    // pin sync over super.run and touches no subclass state until after construction, so no
    // partially-constructed state is observed. The reference never escapes this instance.
    runner = this::run;
  }

  /**
   * Pin counters for expiry-pinned subclasses. The {@code busy} flag is volatile for lock-free
   * reads; {@code pending} is only touched under the queue monitor.
   */
  static final class PinState {
    volatile boolean busy;
    int pending;
  }

  /**
   * Pins for an upcoming submission. Atomic under the queue monitor so the pin cannot race with a
   * concurrent idle transition.
   *
   * @return {@code true} when this call transitioned idle to pinned
   */
  final boolean pinForSubmit(PinState state) {
    synchronized (lock) {
      boolean wasBusy = state.busy;
      state.pending++;
      state.busy = true;
      return !wasBusy;
    }
  }

  /**
   * Recomputes idleness and publishes {@code busy} atomically under the queue monitor.
   *
   * @return {@code true} when the pinned/idle transition changed
   */
  final boolean syncPin(PinState state) {
    synchronized (lock) {
      boolean now = state.pending > 0 || !(size == 0 && current == null);
      boolean changed = now != state.busy;
      state.busy = now;
      return changed;
    }
  }

  /**
   * Releases one pin fence for an abandoned submission and recomputes idleness atomically.
   *
   * @return {@code true} when the pinned/idle transition changed
   */
  final boolean abortPin(PinState state) {
    synchronized (lock) {
      if (state.pending > 0) {
        state.pending--;
      }
      boolean now = state.pending > 0 || !(size == 0 && current == null);
      boolean changed = now != state.busy;
      state.busy = now;
      return changed;
    }
  }

  /** Lock-free pinned read for maintenance paths holding the cache bin lock. */
  static boolean isPinned(PinState state) {
    return state.busy;
  }

  /** Whether an external-lock release is in flight. Snapshot under the queue monitor. */
  final boolean isUnlocking() {
    synchronized (lock) {
      return unlocking;
    }
  }

  /** Whether an external-lock release is in flight. Caller must hold the queue monitor. */
  final boolean isUnlockingLocked() {
    return unlocking;
  }

  /** Marks an external-lock release as in flight. */
  final void markUnlocking() {
    synchronized (lock) {
      unlocking = true;
    }
  }

  /**
   * Finishes an external-lock release: clears the release gate and repoints or releases the runner
   * claim atomically.
   *
   * @return {@code true} when pending work must resume on a fresh runner
   */
  final boolean finishUnlock(boolean fatal, boolean callerReturning) {
    synchronized (lock) {
      unlocking = false;
      if (fatal) {
        current = null;
        return false;
      }
      if (size != 0) {
        if (callerReturning) {
          current = head.executor();
          return true;
        }
        return false;
      }
      if (callerReturning) {
        current = null;
      }
      return false;
    }
  }

  /**
   * O(1) doubly-linked-list invariants. Callers must hold the queue monitor. Evaluated only when
   * {@code -ea} is on: every mutating helper asserts this, so production pays nothing while tests
   * and debug runs fail fast on a torn endpoint instead of corrupting FIFO order. Full traversal is
   * deliberately avoided here to keep drain/link paths O(1); per-link symmetry is asserted at the
   * mutation site.
   *
   * @return {@code true} when head/tail/size endpoints agree
   */
  @GuardedBy("lock")
  private boolean checkInvariantsLocked() {
    if (size < 0) {
      return false;
    }
    if (size == 0) {
      return head == null && tail == null;
    }
    if (head == null || tail == null) {
      return false;
    }
    if (head.prev != null || tail.next != null) {
      return false;
    }
    if (size == 1) {
      return head == tail;
    }
    return true;
  }

  /** Links {@code task} at the tail. Callers must hold the queue monitor. */
  @GuardedBy("lock")
  private void linkLast(Task task) {
    assert !task.linked : "task already linked";
    assert task.prev == null && task.next == null : "task carries stale links";
    task.prev = tail;
    task.next = null;
    if (tail == null) {
      head = task;
    } else {
      tail.next = task;
    }
    tail = task;
    task.linked = true;
    size++;
    assert checkInvariantsLocked() : "intrusive list torn after linkLast";
  }

  /** Links {@code task} at the head. Callers must hold the queue monitor. */
  @GuardedBy("lock")
  private void linkFirst(Task task) {
    assert !task.linked : "task already linked";
    assert task.prev == null && task.next == null : "task carries stale links";
    task.next = head;
    task.prev = null;
    if (head == null) {
      tail = task;
    } else {
      head.prev = task;
    }
    head = task;
    task.linked = true;
    size++;
    assert checkInvariantsLocked() : "intrusive list torn after linkFirst";
  }

  /** Unlinks and returns the head, or {@code null} when empty. Callers must hold the monitor. */
  @GuardedBy("lock")
  private Task unlinkFirst() {
    Task first = head;
    if (first != null) {
      unlink(first);
    }
    return first;
  }

  /**
   * Unlinks {@code task} in O(1) via its stored node reference; a no-op when it is not currently
   * queued (already drained or never enqueued). Callers must hold the queue monitor.
   */
  @GuardedBy("lock")
  private void unlink(Task task) {
    if (!task.linked) {
      return;
    }
    task.linked = false;
    Task previous = task.prev;
    Task next = task.next;
    if (previous == null) {
      assert head == task : "head mismatch on unlink";
      head = next;
    } else {
      assert previous.next == task : "prev link torn on unlink";
      previous.next = next;
    }
    if (next == null) {
      assert tail == task : "tail mismatch on unlink";
      tail = previous;
    } else {
      assert next.prev == task : "next link torn on unlink";
      next.prev = previous;
    }
    task.prev = null;
    task.next = null;
    size--;
    assert checkInvariantsLocked() : "intrusive list torn after unlink";
  }

  /**
   * Current runner-claim holder. Must be called with the queue monitor held. Package-private:
   * in-package queues only, not an extension point.
   *
   * @return current claim holder, or {@code null} when no runner is active
   */
  final Executor currentLocked() {
    return current;
  }

  /**
   * Sets the runner-claim holder. Must be called with the queue monitor held. Package-private:
   * in-package queues only, not an extension point.
   *
   * @param executor new claim holder
   */
  final void setCurrentLocked(Executor executor) {
    current = executor;
  }

  /**
   * Atomic poll-or-handoff decision for drain loops. Polls the head, requeues and repoints the
   * claim on an executor switch, or releases the claim when empty. Package-private: in-package
   * queues only.
   *
   * @return drain decision, never {@code null}
   */
  final PollOutcome pollNext() {
    synchronized (lock) {
      return pollLocked(false);
    }
  }

  /**
   * Polls for a batch drain while retaining the runner claim on empty: unlike {@link #pollNext()},
   * an empty poll does not release the claim so a concurrent submitter cannot start a second runner
   * while an external lock is still held (the claim is released after unlock). Package-private:
   * in-package queues only.
   *
   * @return drain decision, never {@code null}
   */
  final PollOutcome pollNextRetainingClaim() {
    synchronized (lock) {
      return pollLocked(true);
    }
  }

  /**
   * Shared poll body behind {@link #pollNext()} and {@link #pollNextRetainingClaim()}: the only
   * difference is whether an empty poll releases the claim. Caller must hold the queue monitor.
   */
  private PollOutcome pollLocked(boolean retainClaimOnEmpty) {
    Task polled = unlinkFirst();
    if (polled == null) {
      if (!retainClaimOnEmpty) {
        current = null;
      }
      return PollOutcome.drained();
    }
    if (polled.executor() != current) {
      linkFirst(polled);
      current = polled.executor();
      return PollOutcome.handoff(polled.executor(), polled);
    }
    return PollOutcome.task(polled);
  }

  /**
   * Removes {@code failedHead} when it is still at the front (identity) and repoints the runner
   * claim at the new head (or releases it when empty). Always returns {@code failedHead} for
   * outside-monitor notification. Package-private: in-package queues only.
   *
   * @param failedHead head task to remove when still at the front
   * @return {@code failedHead}, never {@code null}
   */
  final Task discardHeadAndRepoint(Task failedHead) {
    synchronized (lock) {
      if (head != null && head == failedHead) {
        unlinkFirst();
      }
      if (size == 0) {
        current = null;
      } else {
        current = head.executor();
      }
      return failedHead;
    }
  }

  /**
   * Atomically peeks the head or releases the runner claim when empty. Package-private: in-package
   * queues only.
   *
   * <p>Single monitor acquisition for timer-thread retries: a separate head peek plus claim clear
   * leaves a window where a concurrent submitter enqueues (declined on the still-held claim) before
   * the clear wipes the claim, stranding the task until the next submit. This helper closes that
   * window — either the head is observed or the claim is released, never a torn view.
   *
   * @return head task, or {@code null} when empty (claim released)
   */
  final Task peekHeadOrClearClaim() {
    synchronized (lock) {
      if (head == null) {
        current = null;
        return null;
      }
      return head;
    }
  }

  /**
   * Prepares a reschedule: when empty releases the claim and returns {@code null} (caller returns);
   * otherwise repoints the claim at the head and returns its executor. Package-private: in-package
   * queues only.
   *
   * @return head executor, or {@code null} when empty (claim released)
   */
  final Executor prepareReschedule() {
    synchronized (lock) {
      if (size == 0) {
        current = null;
        return null;
      }
      Task pending = head;
      current = pending.executor();
      return pending.executor();
    }
  }

  /** Releases the runner claim (for fail-open Error paths). Package-private: in-package only. */
  final void clearClaim() {
    synchronized (lock) {
      current = null;
    }
  }

  /**
   * Drains the queue until empty or an executor handoff schedules another runner. Package-private:
   * in-package queues only. External types cannot override it.
   */
  void run() {
    try {
      drain();
    } catch (Error fatal) {
      // Fail open: the runner dies loudly while the runner claim is released, so a future
      // submit starts a fresh runner draining the remaining backlog in order instead of
      // stalling behind a dead runner. Never swallowed.
      synchronized (lock) {
        current = null;
      }
      throw fatal;
    }
  }

  /** Loop body of {@link #run()}; separated so fatal errors can reset the runner claim. */
  private void drain() {
    for (; ; ) {
      switch (pollNext()) {
        case PollOutcome.Drained ignored -> {
          return;
        }
        case PollOutcome.Handoff(var handoff, var handoffHead) -> {
          // Universal same-thread trampoline by thread identity (no marker check):
          // any executor running the runner on this thread loops in this frame instead of
          // recursing one frame per executor switch. Async runners run on another thread.
          // Schedule the next runner without holding the queue monitor: Executor.execute()
          // is arbitrary third-party code and must not run under our lock.
          if (tryInlineHandoff(handoff, handoffHead)) {
            continue;
          }
          return;
        }
        case PollOutcome.Ready(var task) -> {
          try {
            task.runnable().run();
          } catch (Error e) {
            // Never swallow fatal errors: continuing onto the next task on a compromised JVM
            // risks cascading corruption. The outer run() releases the runner claim so the queue
            // recovers on the next submit; inline submitters observe the original failure, async
            // runners die loudly.
            throw e;
          } catch (Throwable t) {
            if (Interruptions.carriesInterrupt(t)) {
              // Never swallow interrupts: restore the flag and end this batch. The runner claim
              // is released so the preserved backlog resumes on the next submit instead of
              // stalling behind a dead runner; the interrupt flag lets the owner observe it.
              // Pooled runners tolerate the set flag: ThreadPoolExecutor workers re-check (and
              // clear) interruption before the next task, and inline (DirectExecutor) paths run
              // on the caller thread which must observe it.
              Thread.currentThread().interrupt();
              if (log.isWarnEnabled()) {
                log.warn(
                    "Interrupted task in {} from task {}; ending batch with interrupt restored",
                    describeQueue(),
                    task.runnable().getClass().getName(),
                    t);
              }
              synchronized (lock) {
                current = null;
              }
              return;
            }
            if (log.isErrorEnabled()) {
              log.error(
                  "Caught unexpected Throwable in {} from task {}",
                  describeQueue(),
                  task.runnable().getClass().getName(),
                  t);
            }
          }
        }
      }
    }
  }

  /**
   * Attempts a handoff, trampolining synchronous execution in the calling frame.
   *
   * <p>Thread identity (not a marker interface) decides sync vs async: the wrapper records the
   * runner thread; same-thread execution suppresses the nested drain and the caller loops instead,
   * keeping stack depth constant across arbitrary executor switches. Any other thread (or not yet
   * started) means async ownership transfer and the caller returns. A deferred same-thread run
   * after this method returned still drains via a fresh invocation (see {@code callerReturned}), so
   * no runner is lost.
   *
   * <p>Handshake detail: one thread-local box via {@link HandoffBoxes}, reused across hot
   * synchronous handoffs through {@link HandoffTemplate} (re-entrant transfers take the allocating
   * cold path instead); async transfers detach so no entry is retained.
   *
   * <p>The backing executor must provide happens-before between {@code execute()} and task start.
   * Without it the handshake degrades to an async transfer (caller returns) instead of risking a
   * sync misclassification.
   *
   * <p>On scheduling failure only the handoff head ({@code failedHead}, already requeued at the
   * front by the caller) is rejected and removed; the remaining backlog is preserved in order and
   * the caller loops to continue draining it in this frame.
   *
   * @param handoff executor to schedule the runner
   * @param failedHead head task to reject when scheduling fails; must be at the deque front
   * @return {@code true} when the caller must loop (inline handoff or head-only rejection); {@code
   *     false} when async and the caller must return
   */
  private boolean tryInlineHandoff(Executor handoff, Task failedHead) {
    Thread caller = Thread.currentThread();
    return HandoffTemplate.transfer(handoff, caller, runner, e -> rejectHandoffHead(failedHead, e))
        != HandoffTemplate.Result.ASYNC_RETURN;
  }

  /**
   * Rejects only the handoff head after a scheduling failure, preserving the remaining backlog in
   * order for this runner to continue draining.
   *
   * <p>Removes the head under the queue monitor and repoints the runner claim at the new head (or
   * releases it when empty) so ordering among survivors is unchanged. Notifies the head outside the
   * monitor; a throwing rejection callback is logged and contained so the surviving backlog still
   * drains.
   *
   * @param failedHead head task whose executor rejected the handoff
   * @param e rejection or failure cause
   */
  private void rejectHandoffHead(Task failedHead, Throwable e) {
    discardHeadAndRepoint(failedHead);
    log.warn(
        "Rejected handoff head in {} due to {}: {}; backlog preserved",
        describeQueue(),
        e.getClass().getName(),
        e.getMessage());
    notifyDiscardedContained(failedHead.runnable(), e);
  }

  /**
   * Enqueues {@code runnable} to run on {@code executor} when this queue reaches it.
   *
   * <p>When runner scheduling fails, only this submission fails: its {@link RejectionAware}
   * callback (if any) is notified and the original failure (usually {@link
   * java.util.concurrent.RejectedExecutionException}) propagates to this submitter. Tasks enqueued
   * by other submitters are preserved in order. Callers needing a future-style signal must use a
   * {@link RejectionAware} task (e.g. via {@link
   * io.github.jinganix.peashooter.executor.OrderedTraceExecutor#submitAsync(String, Runnable)}).
   *
   * <p>At most one runner is active per queue instance.
   *
   * <p>A fatal {@link Error} from a task run synchronously on the submitting thread propagates to
   * the submitter (the backlog is preserved). Sync vs async is decided by thread identity, not by a
   * marker interface.
   *
   * @param executor schedules the queue runner for this task
   * @param runnable task body
   * @throws RejectedExecutionException (or whatever {@code executor} throws) when runner scheduling
   *     fails; the original failure always propagates
   */
  public final void execute(Executor executor, Runnable runnable) {
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(runnable, "runnable");
    Task task = new Task(runnable, executor);
    EnqueuePlan plan;
    try {
      plan = linkAndClaim(task, executor);
    } catch (Error e) {
      // Fail open like below, but never lose the pin fence: attempt the same trigger removal
      // and fence release as the Exception path so a hook Error cannot pin the key resident.
      // Cleanup itself may fail under a JVM fault; the original Error always propagates.
      failEnqueue(task, e);
      throw e;
    } catch (Throwable hookFailure) {
      // A throwing enqueue hook (subclass bug) fails only this submission: remove it, keep the
      // prior backlog and the previous runner claim, then surface the failure to this submitter.
      // The pin fence (if any) is released via onEnqueueFailed so an enqueue-hook failure can
      // never strand a submit pin and pin the key resident.
      failEnqueue(task, hookFailure);
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(hookFailure);
    }
    Executor toSchedule = plan.toSchedule();
    try {
      onEnqueued(plan.changed());
    } catch (Error e) {
      // Fail open like above: the trigger stays queued; release only the claim we just took so
      // the next submission resumes it.
      releaseClaimAfterEnqueuedFailure(toSchedule);
      throw e;
    } catch (Throwable hookFailure) {
      // Same for the notification hook: drop this submission (and the runner claim we just took,
      // if any) while preserving every other pending task, then surface the failure.
      removeTrigger(task);
      releaseClaimAfterEnqueuedFailure(toSchedule);
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(hookFailure);
    }
    if (toSchedule == null) {
      return;
    }
    Thread submitter = Thread.currentThread();
    // Unified submit handoff: the thread-local box is reused on the synchronous path (no per-call
    // box, reference, or closure there); async submits hand the box to the runner thread and the
    // next submit allocates anew. Thread identity plus try/catch tells an inline task failure
    // apart from a scheduling failure, and the drain runs through the executor itself.
    try {
      HandoffTemplate.submit(
          toSchedule,
          submitter,
          runner,
          e -> {
            // Scheduling failure before the runner started: fail only this submission.
            removeTrigger(task);
            synchronized (lock) {
              current = null;
            }
            notifyDiscardedContained(runnable, e);
            throw ErrorPolicy.UNCHECKED.rethrowUnchecked(e);
          });
    } catch (Throwable submitFailure) {
      // Inline runner failure on the submitting thread or a scheduling failure rethrown by the
      // rejection callback: run()'s fail-open catch (and the callback above) already released the
      // claim, so only reconcile the pin here instead of clobbering a claim a concurrent submitter
      // legitimately took over. Reconciling once covers both failure shapes.
      onRunnerFinished();
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(submitFailure);
    }
  }

  /** Outcome of the atomic enqueue-and-claim critical section. */
  private record EnqueuePlan(boolean changed, Executor toSchedule) {}

  /**
   * Links {@code task} and claims the runner when free, atomically under the queue monitor, then
   * reports the notify flag and the executor to schedule (or {@code null} when a runner already
   * holds the claim). Hooks run inside the monitor exactly as before.
   *
   * @param task just-built task to enqueue
   * @param executor scheduler for {@code task}
   * @return enqueue plan for the caller to act on outside the monitor
   */
  private EnqueuePlan linkAndClaim(Task task, Executor executor) {
    synchronized (lock) {
      linkLast(task);
      boolean changed = onEnqueueLocked();
      Executor toSchedule = tryClaimRunnerLocked(executor) ? executor : null;
      return new EnqueuePlan(changed, toSchedule);
    }
  }

  /**
   * Cleans up a failed enqueue hook: removes the trigger and releases its pin fence, attaching any
   * cleanup failure to the original {@code failure} so it never masks the failure the submitter
   * must observe. Shared by the {@link Error} and {@link Exception} enqueue-failure paths.
   *
   * @param task triggering submission to remove
   * @param failure original enqueue-hook failure
   */
  private void failEnqueue(Task task, Throwable failure) {
    try {
      removeTrigger(task);
    } catch (Throwable cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
    try {
      onEnqueueFailed();
    } catch (Throwable reconcileFailure) {
      if (reconcileFailure != failure) {
        failure.addSuppressed(reconcileFailure);
      }
    }
  }

  /**
   * Releases the runner claim taken by a just-enqueued submission and reconciles the pin after an
   * {@code onEnqueued} hook failure. No-op when this submission did not claim the runner.
   *
   * @param toSchedule executor this submission had claimed the runner for, or {@code null}
   */
  private void releaseClaimAfterEnqueuedFailure(Executor toSchedule) {
    if (toSchedule != null) {
      synchronized (lock) {
        current = null;
      }
      onRunnerFinished();
    }
  }

  /**
   * Removes a just-enqueued triggering submission via its stored node reference in O(1), without
   * touching any other pending task or the runner claim. A no-op when the trigger already left the
   * queue (e.g. a concurrent drain polled it first).
   *
   * @param task the triggering submission to remove
   */
  private void removeTrigger(Task task) {
    synchronized (lock) {
      unlink(task);
    }
  }

  /**
   * Attempts to claim the runner for {@code executor} inside the queue monitor, immediately after
   * an enqueue. Package-private: in-package queues only.
   *
   * <p>The default claims when no runner is active. In-package subclasses with extra runner-claim
   * invariants (e.g. {@link LockableTaskQueue} while the previous batch still releases its external
   * lock) override to decline the claim; the pending work is then picked up by the in-flight
   * release path instead of starting a concurrent runner.
   *
   * <p>Called with the queue monitor held; overrides must not synchronize and must only use the
   * {@code *Locked} accessors or the claim helpers.
   *
   * @param executor scheduler for the enqueued task
   * @return {@code true} when the caller must schedule the runner outside the monitor
   */
  boolean tryClaimRunnerLocked(Executor executor) {
    if (current != null) {
      return false;
    }
    current = executor;
    return true;
  }

  /**
   * Runs inside the queue monitor right after a task is enqueued. Package-private: in-package
   * queues only. In-package subclasses reconcile enqueue-side state (e.g. pinning) here so it stays
   * atomic with the enqueue.
   *
   * @return whether the enqueue changed notify-worthy state, forwarded to {@link
   *     #onEnqueued(boolean)} outside the monitor
   */
  boolean onEnqueueLocked() {
    return false;
  }

  /**
   * Reconciles enqueue-side state after a throwing {@link #onEnqueueLocked()} or {@link
   * #tryClaimRunnerLocked} hook. Package-private: in-package queues only. Runs outside the queue
   * monitor after the trigger was removed, so implementations may touch provider state (e.g.
   * release a submit pin fence). The default is a no-op for pin-free queues.
   */
  void onEnqueueFailed() {}

  /**
   * Runs outside the queue monitor after an enqueue. Package-private: in-package queues only.
   *
   * @param changed the value returned by {@link #onEnqueueLocked()}
   */
  void onEnqueued(boolean changed) {}

  /**
   * Runs after an inline runner finished via the {@link #execute} scheduling path (runner death or
   * scheduling failure). Package-private: in-package queues only. Async runners reconcile through
   * {@link #run()} instead.
   */
  void onRunnerFinished() {}

  /**
   * Whether the queue is idle: no pending tasks and no active runner.
   *
   * <p>Note this is quiescence, not {@link java.util.Queue#isEmpty()} on the deque: {@code false}
   * while a runner is in flight even if the deque is momentarily empty.
   *
   * @return {@code true} when no tasks are queued and no runner is in flight
   */
  public final boolean isIdle() {
    synchronized (lock) {
      return size == 0 && current == null;
    }
  }

  /**
   * Whether tasks are waiting in the deque behind the active runner.
   *
   * <p>Unlike {@link #isIdle()}, this distinguishes "runner active with no backlog" (empty) from
   * "runner active with queued peers" (non-empty). Used by {@link
   * io.github.jinganix.peashooter.executor.OrderedTraceExecutor} to fail fast when a nested
   * same-key sync would overtake waiting peers instead of silently running inline ahead of them.
   *
   * @return {@code true} when at least one task is queued
   */
  public final boolean hasPending() {
    synchronized (lock) {
      return size != 0;
    }
  }

  /**
   * Short identity for logs so multi-key deployments can attribute poison tasks and rejections.
   * Package-private: in-package queues only. In-package subclasses with a key (e.g. the caffeine
   * provider's pinned queue) override to include it.
   *
   * @return log label, never {@code null}
   */
  String describeQueue() {
    return getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this));
  }

  /**
   * Notifies a rejected task runnable with the original failure, including {@link Error}.
   * Package-private: in-package queues only.
   *
   * @param runnable task body that was rejected
   * @param e rejection or failure cause passed to rejection-aware runnables
   */
  void notifyDiscarded(Runnable runnable, Throwable e) {
    RejectionAware.dispatch(runnable, e);
  }

  /**
   * Single owner of rejection-callback containment: notifies the rejected task outside the queue
   * monitor and never lets a throwing user {@code rejected} callback mask the original rejection. A
   * garbage callback failure is suppressed onto {@code e} (guarding against self-suppression) and
   * logged.
   *
   * @param runnable rejected task body
   * @param e original rejection or failure cause
   */
  void notifyDiscardedContained(Runnable runnable, Throwable e) {
    try {
      notifyDiscarded(runnable, e);
    } catch (Throwable callbackFailure) {
      if (callbackFailure != e) {
        e.addSuppressed(callbackFailure);
      }
      log.warn(
          "Rejection callback failed in {}: {}: {}",
          describeQueue(),
          callbackFailure.getClass().getName(),
          callbackFailure.getMessage());
    }
  }

  /**
   * Atomic drain decision: either a task to run, a handoff to schedule, or drained.
   * Package-private: queue internals only, not public API.
   *
   * <p>Sealed with one record per outcome: each carries exactly the fields it needs and callers
   * dispatch via exhaustive {@code switch} pattern matching, so no nullable union accessors exist
   * to misread.
   */
  sealed interface PollOutcome permits PollOutcome.Drained, PollOutcome.Handoff, PollOutcome.Ready {

    static PollOutcome drained() {
      return Drained.INSTANCE;
    }

    static PollOutcome handoff(Executor handoff, Task head) {
      return new Handoff(
          Objects.requireNonNull(handoff, "handoff"), Objects.requireNonNull(head, "head"));
    }

    static PollOutcome task(Task task) {
      return new Ready(Objects.requireNonNull(task, "task"));
    }

    /** Empty queue: claim released, caller returns. */
    record Drained() implements PollOutcome {
      static final Drained INSTANCE = new Drained();
    }

    /** Executor switch: head requeued, claim repointed, caller hands off. */
    record Handoff(Executor handoff, Task handoffHead) implements PollOutcome {}

    /** Runnable head for the current executor. */
    record Ready(Task task) implements PollOutcome {}
  }

  /**
   * Immutable per-key work item pairing the task body with the {@link Executor} that scheduled the
   * queue runner for it. Package-private: queue internals only, not public API.
   *
   * <p>Identity equality: distinct submissions are never equal even when they share the same
   * runnable and executor, so removal and head checks must use {@code ==}.
   */
  static final class Task {
    private final Runnable runnable;
    private final Executor executor;

    /** Previous node in the owning queue's intrusive list; guarded by the queue monitor. */
    @GuardedBy("TaskQueue.lock")
    private Task prev;

    /** Next node in the owning queue's intrusive list; guarded by the queue monitor. */
    @GuardedBy("TaskQueue.lock")
    private Task next;

    /** Whether currently linked into the owning queue; guarded by the queue monitor. */
    @GuardedBy("TaskQueue.lock")
    private boolean linked;

    /** Creates a task pairing the task body with its scheduler. */
    Task(Runnable runnable, Executor executor) {
      this.runnable = Objects.requireNonNull(runnable, "runnable");
      this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Task body. */
    Runnable runnable() {
      return runnable;
    }

    /** Executor that scheduled the queue runner for this task. */
    Executor executor() {
      return executor;
    }
  }
}
