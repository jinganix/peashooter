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

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single executor-handoff handshake for {@link TaskQueue} and {@link HandoffRouter}.
 *
 * <p>One template for thread-identity sync/async detection and head-only rejection: hot
 * <em>synchronous</em> transfers reuse the single thread-local {@link HandoffBoxes.HandoffBox} (no
 * per-call {@code AtomicReference} or closure on the sync path), re-entrant transfers allocate a
 * single-use box without touching it, and async transfers hand the box to the runner thread (the
 * submitter detaches, so the next submit on this thread allocates a fresh box). Submissions ({@link
 * #submit}) and mid-drain handoffs ({@link #transfer}) share the box; only the inline contract
 * differs.
 *
 * <p><b>Intentional duplication:</b> the hot/cold pairs ({@code transfer}/{@code transferCold},
 * {@code submit}/{@code submitCold}) share the handshake protocol but stay separate to avoid
 * allocating a box-supplier lambda per handoff on this hot path. Deduplicate only if the
 * zero-allocation budget is relaxed.
 */
final class HandoffTemplate {

  private static final Logger log = LoggerFactory.getLogger(HandoffTemplate.class);

  private HandoffTemplate() {}

  enum Result {
    SYNC_LOOP,
    ASYNC_RETURN,
    REJECT_LOOP,
  }

  /**
   * Whether a started command must be treated as a completed handoff instead of a scheduling
   * rejection: the executor dispatched the command (possibly to another thread) and only then
   * threw, so the runner owns the drain and this box.
   *
   * <p>Clearing the box, rejecting the head, or clearing the runner claim here would let a second
   * runner start while the first still drains (breaking per-key order) and would notify a task that
   * already ran. The failure is logged, never propagated: the submission/handoff succeeded.
   */
  private static boolean startedElsewhere(HandoffBoxes.HandoffBox box, Thread caller, Throwable e) {
    Thread runner = box.runnerThread.get();
    if (runner == null || runner == caller) {
      return false;
    }
    log.warn(
        "Executor threw after starting the handoff runner; handoff kept: {}: {}",
        e.getClass().getName(),
        e.getMessage(),
        e);
    return true;
  }

  /**
   * Hands off {@code asyncDrain} to {@code handoff} on behalf of {@code caller}.
   *
   * <p>Sync vs async is decided by thread identity: same-thread execution before return trampolines
   * via the caller's loop; any other thread (or not yet started) means async ownership transfer. A
   * deferred same-thread run after this method returned still drains via a fresh invocation, so no
   * runner is lost. Without backing-executor happens-before the handshake degrades to async (caller
   * returns) instead of risking a sync misclassification.
   *
   * <p>An inline task failure is never a scheduling rejection: the handshake is cleared and the
   * failure propagates so fail-open paths preserve the backlog. Sync and rejection outcomes clear
   * their handshake before returning; async outcomes detach (hot) and the runner clears the
   * handshake after the drain, so no path retains caller/drain refs.
   */
  static Result transfer(
      Executor handoff, Thread caller, Runnable asyncDrain, Consumer<Throwable> onReject) {
    HandoffBoxes.HandoffBox hot = HandoffBoxes.acquire();
    if (hot.inUse) {
      return transferCold(handoff, caller, asyncDrain, onReject);
    }
    hot.prepare(caller, asyncDrain);
    try {
      handoff.execute(hot);
    } catch (Throwable e) {
      if (hot.runnerThread.get() == caller) {
        hot.clear();
        throw e;
      }
      if (startedElsewhere(hot, caller, e)) {
        // The command started on another thread: the runner owns the drain and this box. Detach
        // so this thread cannot reuse a box the runner still touches.
        HandoffBoxes.detach();
        return Result.ASYNC_RETURN;
      }
      hot.clear();
      onReject.accept(e);
      return Result.REJECT_LOOP;
    } finally {
      hot.callerReturned.set(true);
    }
    if (hot.isSyncInline(caller)) {
      hot.clear();
      return Result.SYNC_LOOP;
    }
    // Async: detach only — the handshake stays alive for the runner that has not started yet
    // (it clears it after the drain); clearing here would lose the drain.
    HandoffBoxes.detach();
    return Result.ASYNC_RETURN;
  }

  /** Re-entrant path: allocates a single-use box, never touches the thread-local one. */
  private static Result transferCold(
      Executor handoff, Thread caller, Runnable asyncDrain, Consumer<Throwable> onReject) {
    HandoffBoxes.HandoffBox cold = new HandoffBoxes.HandoffBox();
    cold.prepare(caller, asyncDrain);
    try {
      handoff.execute(cold);
    } catch (Throwable e) {
      if (cold.runnerThread.get() == caller) {
        cold.clear();
        throw e;
      }
      if (startedElsewhere(cold, caller, e)) {
        // The runner owns the single-use cold box and clears the handshake after its drain.
        return Result.ASYNC_RETURN;
      }
      cold.clear();
      onReject.accept(e);
      return Result.REJECT_LOOP;
    } finally {
      cold.callerReturned.set(true);
    }
    if (cold.isSyncInline(caller)) {
      cold.clear();
      return Result.SYNC_LOOP;
    }
    // Async: no clear — the runner that has not started yet owns the handshake and clears it
    // after the drain; clearing here would lose the drain.
    return Result.ASYNC_RETURN;
  }

  /**
   * Schedules the queue runner for a claimed executor on behalf of a submitter.
   *
   * <p>Unlike {@link #transfer}, no caller drain loop exists here, so the drain runs through the
   * executor itself (preserving executor interposition such as depth guards): an inline executor
   * runs the drain before this returns, any other thread owns it and the submitter returns. Thread
   * identity plus try/catch tells an inline task failure (runner already started on the calling
   * thread: cleared and propagated, backlog preserved) apart from a scheduling failure (runner
   * never started: {@code onReject} fails only the triggering submission).
   *
   * <p>Inline (synchronous) submissions reuse the thread-local box; re-entrant submissions allocate
   * a single-use box, and async submissions hand the box to the runner thread (one allocation per
   * async submit on the submitting thread). {@code onReject} must propagate the scheduling failure
   * (it never returns normally).
   *
   * @param handoff executor to schedule the runner
   * @param caller submitting thread
   * @param drain queue runner
   * @param onReject head-only rejection for a scheduling failure; must not return normally
   */
  static void submit(
      Executor handoff, Thread caller, Runnable drain, Consumer<Throwable> onReject) {
    HandoffBoxes.HandoffBox hot = HandoffBoxes.acquire();
    if (hot.inUse) {
      submitCold(handoff, caller, drain, onReject);
      return;
    }
    hot.prepareSubmit(caller, drain);
    // Detach before executing: the inline drain's nested handoffs need the thread-local slot;
    // holding it across the call would force every nested handoff onto the allocating cold
    // path. The box is reinstalled after an inline run; an async runner owns it instead.
    // The finally signal below overwrites the catch-path clear: a reinstalled resident may rest
    // with callerReturned=true until the next prepareSubmit resets it (harmless; prepare rewrites
    // every flag). The signal must stay in finally so an async runner that started before a
    // throwing execute still observes the caller as returned.
    HandoffBoxes.detach();
    try {
      handoff.execute(hot);
    } catch (Throwable e) {
      if (hot.runnerThread.get() == caller) {
        HandoffBoxes.reinstall(hot);
        throw e;
      }
      if (startedElsewhere(hot, caller, e)) {
        // The runner started on another thread and owns this already-detached box: leave the
        // trigger queued and the runner claim held, and do not fail the accepted submission.
        return;
      }
      HandoffBoxes.reinstall(hot);
      onReject.accept(e);
      return;
    } finally {
      hot.callerReturned.set(true);
    }
    if (hot.runnerThread.get() == caller) {
      // Drained inline through the executor; the box already dropped the handshake.
      HandoffBoxes.reinstall(hot);
      return;
    }
    // Async: the runner owns the detached box (it clears the handshake after the drain).
  }

  /** Re-entrant submit: allocates a single-use drain-through box, never touches thread-local. */
  private static void submitCold(
      Executor handoff, Thread caller, Runnable drain, Consumer<Throwable> onReject) {
    HandoffBoxes.HandoffBox cold = new HandoffBoxes.HandoffBox();
    cold.prepareSubmit(caller, drain);
    try {
      handoff.execute(cold);
    } catch (Throwable e) {
      if (cold.runnerThread.get() == caller) {
        cold.clear();
        throw e;
      }
      if (startedElsewhere(cold, caller, e)) {
        // The runner owns the single-use cold box and clears the handshake after its drain.
        return;
      }
      cold.clear();
      onReject.accept(e);
      return;
    } finally {
      cold.callerReturned.set(true);
    }
    if (cold.runnerThread.get() == caller) {
      cold.clear();
    }
    // Async cold: no clear — the runner owns the handshake and clears it after the drain.
  }
}
