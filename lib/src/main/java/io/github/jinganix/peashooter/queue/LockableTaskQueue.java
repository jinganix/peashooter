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

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TaskQueue} extended with an external lock acquired around each batch of task execution.
 *
 * <p>Inherits {@link TaskQueue}'s submission-failure and task-body policies.
 *
 * <p><b>Lock lifecycle:</b> subclasses implement {@link #tryLock}, {@link #shouldYield}, and {@link
 * #unlock}. The queue calls {@code tryLock} once per batch, runs tasks while holding the lock,
 * optionally {@code unlock}s and re-{@code tryLock}s when {@code shouldYield} returns {@code true},
 * then {@code unlock}s when the batch ends. If {@code tryLock} fails while tasks are pending, the
 * runner is rescheduled on the caller-owned scheduler after an exponential backoff with Full Jitter
 * (1-100ms, see {@link RetryScheduler}), to avoid synchronous re-entry.
 *
 * <p><b>Executor handoff:</b> when consecutive tasks use different {@link Executor}s, the external
 * lock stays held across <em>asynchronous</em> handoff until the new runner starts (see {@link
 * HandoffRouter}). Synchronous handoff (same-thread execution, decided by thread identity) loops in
 * this frame (trampoline) instead of nesting {@link #run()} depth.
 *
 * <p><b>Structure:</b> lock pairing lives in {@link LockBatch}, delayed retries in {@link
 * RetryScheduler}, executor handoff in {@link HandoffRouter}. This class keeps orchestration
 * (runner claim, drain loop, rescheduling) so no single class owns the whole protocol.
 *
 * <p><b>Subclass contract:</b> {@code tryLock} may be invoked multiple times per outer loop after a
 * yield. Only the runner frame that owns the batch releases the lock: competing runners that merely
 * observe the held lock back off, and synchronous handoff continuations inherit ownership instead
 * of nesting {@link #run()} depth. Pair every successful lock with exactly one {@code unlock} by
 * the owning frame. Blocking or spinning in {@code tryLock} is implementation-defined. {@code
 * tryLock}, {@code shouldYield}, and {@code unlock} must not throw for control flow. A throwing
 * hook behaves by failure shape: an {@link Exception} is contained ({@code tryLock} reschedules the
 * runner, {@code shouldYield}/{@code record} ends the batch, {@code unlock} is logged while the
 * lock state is still reset); an {@link Error} stays loud and fail-open (runner claim dropped,
 * backlog preserved for the next explicit submit, no automatic retry) so a compromised JVM never
 * auto-schedules more work.
 */
public abstract class LockableTaskQueue extends TaskQueue {

  private static final Logger log = LoggerFactory.getLogger(LockableTaskQueue.class);

  /**
   * Maximum heads discarded per timer retry tick. Bounds timer-thread work when a saturated pool
   * rejects every head in a deep backlog: each discard runs user {@code rejected} callbacks, so an
   * unbounded drain would stall the shared scheduler and delay other keys' backoffs. The remainder
   * is picked up by a follow-up retry instead of this tick.
   */
  static final int MAX_HEAD_DISCARD_PER_RETRY = 8;

  /**
   * Maximum tasks executed inline on the timer thread per retry tick. When the head's executor runs
   * the runner on the rescheduler thread (thread identity, e.g. {@link DirectExecutor} or any other
   * synchronous executor), an unbounded drain would hold the shared scheduler for an entire backlog
   * and delay every other key's backoff. The remainder is picked up by a follow-up retry instead of
   * this tick. Async heads hand to their own executor and need no budget here.
   */
  static final int MAX_TASKS_PER_TIMER_RETRY = 8;

  /**
   * Mutable runner frame for one runner invocation ({@link #run()} or an async handoff
   * continuation). Passed explicitly through {@link #runOuter} and collaborators instead of living
   * in a per-queue {@code ThreadLocal}: a {@code ThreadLocal} per queue leaves one {@code
   * ThreadLocalMap} entry per (thread, queue) pair, and the value outlives an evicted queue until
   * the next rehash. A single mutable holder also avoids boxing a {@link Boolean} on every {@link
   * #run()} entry.
   */
  static final class RunState {
    /**
     * Whether the current thread's runner owns the in-flight batch, i.e. it acquired the external
     * lock or inherited it through handoff. A competing runner that merely observes the held lock
     * must not reuse or release the lock.
     */
    boolean ownsBatch;
  }

  private final LockBatch batch;

  private final RetryScheduler retries;

  private final HandoffRouter handoffs;

  /**
   * Creates a queue with explicit stats and a caller-managed reschedule scheduler.
   *
   * <p>The scheduler is only used for delayed {@link #tryLock} retries (exponential backoff
   * 1ms-100ms with Full Jitter); it is never shut down by this queue, so its lifecycle stays with
   * the caller. No owned-scheduler overload is provided: one thread per queue pins threads and
   * classloaders under high key cardinality, so callers must share one scheduler across queues and
   * close it explicitly.
   *
   * <p><b>Scheduler ownership checklist (who builds it closes it):</b>
   *
   * <ol>
   *   <li>Base queue: never owns the scheduler and is not closeable. The caller builds the
   *       scheduler and the caller closes it.
   *   <li>Subclass with a self-created scheduler (e.g. test doubles owning a per-instance pool):
   *       that subclass owns it. It must implement {@link AutoCloseable} itself to shut its own
   *       scheduler down (idempotently) and document the exception to this rule.
   *   <li>Production code: share one scheduler across queues and close it explicitly. Never copy a
   *       test double's per-instance ownership into production.
   * </ol>
   *
   * @param stats execution statistics passed to {@link #tryLock} and {@link #shouldYield}; reset
   *     once per runner invocation after the lock is acquired (handoff continuations start a new
   *     window)
   * @param rescheduler scheduler for lock-contention retries; lifecycle stays with the caller
   */
  public LockableTaskQueue(ExecutionStats stats, ScheduledExecutorService rescheduler) {
    super();
    Objects.requireNonNull(stats, "stats");
    Objects.requireNonNull(rescheduler, "rescheduler");
    this.retries = new RetryScheduler(rescheduler, () -> ThreadLocalRandom.current().nextLong());
    // Collaborators only retain this (no callbacks, no thread starts during construction),
    // so no partially-constructed state is observed before the constructor returns.
    this.batch = new LockBatch(this, stats, retries);
    this.handoffs = new HandoffRouter(this);
  }

  @Override
  final boolean tryClaimRunnerLocked(Executor executor) {
    // Declines while any runner is active or an unlock is in flight (including the defensive
    // (null, true) state the monitor never publishes, so a future refactor can never start a
    // second runner mid-release). The done-retry reclaim below only heals a phantom claim with
    // no runner and no lock: an actively draining runner holds the external lock, so reclaiming
    // while locked could start a second concurrent runner and break mutual exclusion.
    if (currentLocked() != null || isUnlockingLocked()) {
      if (!isUnlockingLocked() && !batch.isLocked() && retries.reclaimDoneRetry()) {
        setCurrentLocked(executor);
        return true;
      }
      return false;
    }
    setCurrentLocked(executor);
    return true;
  }

  @Override
  final void run() {
    runOuter(new RunState());
  }

  /**
   * Per-iteration exit driving the single {@code finally} release: every {@code return} sets its
   * outcome first, the fall-through path sets {@link #NEXT_BATCH} to loop, and {@link Error}
   * unwinding forces the fatal release regardless of the outcome.
   */
  private enum BatchOutcome {
    /** Drained/terminal return: release, no reschedule decision beyond the release itself. */
    DRAINED,
    /** Async handoff: lock stays held across the transfer, no release in this frame. */
    ASYNC_KEEP,
    /** Normal terminal return: release and reschedule when work remains. */
    FINISHED_RETURN,
    /** Continue the outer loop in-thread: release then reacquire for the next batch. */
    NEXT_BATCH,
  }

  void runOuter(RunState state) {
    runOuter(state, Integer.MAX_VALUE);
  }

  /**
   * Runner with a timer-thread inline budget.
   *
   * @param maxTasks maximum tasks to run inline in this invocation; {@link Integer#MAX_VALUE} for
   *     pool threads (unbounded, batch size stays with {@code shouldYield}). Timer-thread retries
   *     pass {@link #MAX_TASKS_PER_TIMER_RETRY} so a {@link DirectExecutor} backlog cannot hold the
   *     shared scheduler: the remainder is rescheduled via the normal release path instead of this
   *     tick.
   */
  void runOuter(RunState state, int maxTasks) {
    int tasksRun = 0;
    for (; ; ) {
      BatchOutcome outcome = BatchOutcome.FINISHED_RETURN;
      boolean fatalError = false;
      try {
        boolean acquired = batch.tryAcquire(state);
        boolean statsReady = false;
        if (acquired) {
          statsReady = batch.resetStatsSafely();
        }
        batchLoop:
        while (statsReady) {
          if (tasksRun >= maxTasks) {
            // Budget exhausted on the timer thread: leave the head queued and return through the
            // release path (callerReturning), which repoints the claim at the preserved remainder
            // and schedules a follow-up retry. Breaking to NEXT_BATCH would keep draining in-thread
            // and dropping the already-polled head.
            outcome = BatchOutcome.FINISHED_RETURN;
            return;
          }
          switch (pollNextRetainingClaim()) {
            case PollOutcome.Drained ignored -> {
              outcome = BatchOutcome.DRAINED;
              return;
            }
            case PollOutcome.Handoff(var handoff, var handoffHead) -> {
              HandoffRouter.HandoffOutcome transferred = handoffs.transfer(handoff, handoffHead);
              if (transferred == HandoffRouter.HandoffOutcome.SYNC_CONTINUE
                  || transferred == HandoffRouter.HandoffOutcome.REJECT_CONTINUE) {
                continue batchLoop;
              }
              outcome = BatchOutcome.ASYNC_KEEP;
              return;
            }
            case PollOutcome.Ready(var task) -> {
              tasksRun++;
              if (batch.runTaskAndCheckYield(task)) {
                // End of batch: statsReady stays true so the post-loop
                // acquired && !statsReady check still distinguishes yield (loop
                // for the next batch) from a failed stats reset (return).
                break batchLoop;
              }
            }
          }
        }
        if (state.ownsBatch && Thread.currentThread().isInterrupted()) {
          // Never continue the next batch on an interrupted thread: release (which reschedules
          // the preserved backlog onto a fresh thread when returning) and exit with the flag
          // intact instead of running more tasks under interruption. A runner that never owned
          // the batch has nothing to release, so it must fall through to the reschedule path
          // instead of stranding the claim with no pending retry.
          return;
        }
        if (rescheduleIfUnlocked(state)) {
          return;
        }
        if (acquired && !statsReady) {
          return;
        }
        outcome = BatchOutcome.NEXT_BATCH;
      } catch (Error e) {
        fatalError = true;
        throw e;
      } finally {
        if (outcome != BatchOutcome.ASYNC_KEEP) {
          batch.release(
              state,
              outcome != BatchOutcome.NEXT_BATCH && !fatalError,
              fatalError,
              this::scheduleWithHeadDiscard);
        }
      }
    }
  }

  // Batch polling uses the inherited TaskQueue.pollNextRetainingClaim atomic helper so no
  // generic lock rental remains on this path.

  /**
   * Reschedules the runner when the lock is not held.
   *
   * @param state explicit runner frame of the calling depth
   * @return true when the caller must return (drained or rescheduled)
   */
  private boolean rescheduleIfUnlocked(RunState state) {
    if (batch.isLocked()) {
      return !state.ownsBatch;
    }
    Executor pendingWork = prepareReschedule();
    if (pendingWork == null) {
      return true;
    }
    scheduleWithHeadDiscard();
    return true;
  }

  /**
   * Schedules a timer-thread retry resuming the backlog after lock contention.
   *
   * <p>The retry reuses each head's own executor. When that executor runs the runner inline on the
   * timer thread (decided by thread identity), the drain is bounded by {@link
   * #MAX_TASKS_PER_TIMER_RETRY}: an unbounded inline drain would hold the shared scheduler for an
   * entire backlog and delay every other key's backoff. The remainder is picked up by a follow-up
   * retry instead of this tick (the bounded {@link #runOuter(RunState, int)} release path
   * reschedules it).
   */
  private void rescheduleRunner() {
    if (retries.hasPendingRetry()) {
      return;
    }
    Runnable retry =
        () -> {
          // Detect inline execution by thread identity (like the handoff trampoline): only a
          // runner the head's executor runs on this timer thread needs the budget. An async
          // executor hands off to its own thread and drains unbounded there.
          Thread timerThread = Thread.currentThread();
          AtomicReference<Thread> startedOn = new AtomicReference<>();
          Runnable inlineRunner =
              () -> {
                startedOn.set(Thread.currentThread());
                runOuter(
                    new RunState(),
                    Thread.currentThread() == timerThread
                        ? MAX_TASKS_PER_TIMER_RETRY
                        : Integer.MAX_VALUE);
              };
          int discarded = 0;
          while (true) {
            Task head = peekHeadOrClearClaim();
            if (head == null) {
              return;
            }
            if (discarded >= MAX_HEAD_DISCARD_PER_RETRY) {
              // Cap reached with rejecting heads remaining: schedule a follow-up tick instead of
              // holding the timer thread. Coalescing keeps at most one retry outstanding, so this
              // converges across ticks without piling timers.
              try {
                rescheduleRunner();
              } catch (Throwable rescheduleFailure) {
                // Timer dead: fail open, preserve the backlog for the next explicit submit
                // instead of discarding behind a dead scheduler.
                clearClaim();
                log.warn(
                    "Rescheduler rejected follow-up retry in {}; backlog preserved for next submit: {}: {}",
                    describeQueue(),
                    rescheduleFailure.getClass().getName(),
                    rescheduleFailure.getMessage());
              }
              return;
            }
            try {
              head.executor().execute(inlineRunner);
              return;
            } catch (Error fatal) {
              // A fatal task/hook Error from an inline run must stay loud instead of becoming a
              // head rejection: fail-open preserves the backlog with no automatic retry. When the
              // executor threw before starting the runner, no runner frame exists to release the
              // claim, so drop it here or the preserved backlog would strand behind a dead claim
              // (RetryScheduler already cleared its own claim when this retry body started).
              if (startedOn.get() == null) {
                clearClaim();
              }
              throw fatal;
            } catch (Throwable e) {
              if (startedOn.get() != null) {
                // The executor started the runner (inline on this timer thread, or on another
                // thread) and only then threw. The runner frame already owns the drain: rejecting
                // the head would double-notify a task that already ran, and this frame must not
                // resubmit the shared inlineRunner. Mirrors HandoffTemplate's started-runner
                // decision on the other dispatch sites (and relies on the backing-executor
                // happens-before documented in HandoffBoxes).
                log.warn(
                    "Retry executor threw after starting the runner in {}; backlog kept: {}: {}",
                    describeQueue(),
                    e.getClass().getName(),
                    e.getMessage());
                return;
              }
              discarded++;
              handoffs.rejectHead(head, e);
            }
          }
        };
    retries.scheduleRetry(retry);
  }

  /**
   * Unlock-time reschedule with fail-open preservation: when the caller-owned retry scheduler
   * rejects the delayed retry, the backlog is preserved in order and the runner claim is released
   * so the next explicit submit resumes it, instead of discarding the whole backlog behind a dead
   * timer. Only the triggering reschedule fails (logged); no head is rejected here. Head-executor
   * rejections are handled later on the timer thread by {@link #rescheduleRunner}, which discards
   * one head per rejected executor to find a working scheduler.
   */
  private void scheduleWithHeadDiscard() {
    try {
      rescheduleRunner();
    } catch (Throwable e) {
      // Fail open like TaskQueue executor failures (never discard-all): keep every pending task
      // and drop the claim so the next submit starts a fresh runner. The timer itself is
      // caller-owned and dead here, so no automatic retry is possible.
      clearClaim();
      log.warn(
          "Rescheduler rejected delayed retry in {}; backlog preserved for next submit: {}: {}",
          describeQueue(),
          e.getClass().getName(),
          e.getMessage());
    }
  }

  /**
   * Attempts to acquire the external lock for the current batch.
   *
   * <p>The lock must be thread-agnostic: it is acquired on one runner thread and may be released on
   * another after an asynchronous executor handoff. Thread-bound primitives (e.g. {@code
   * ReentrantLock}, {@code synchronized} ownership) must not be used here; prefer distributed
   * permits or {@code Semaphore}-style locks.
   *
   * @param stats batch statistics; reset before the first call in each outer loop
   * @return {@code true} if the lock was acquired
   */
  protected abstract boolean tryLock(ExecutionStats stats);

  /**
   * Whether to end the current batch, {@link #unlock}, and start a new one.
   *
   * <p>Fewer yields mean more consecutive tasks per lock hold and fewer lock round-trips.
   *
   * @param stats batch statistics, updated after each executed task
   * @return {@code true} to yield (unlock and re-{@link #tryLock} in the next batch)
   */
  protected abstract boolean shouldYield(ExecutionStats stats);

  /** Releases the external lock acquired by {@link #tryLock}. */
  protected abstract void unlock();
}
