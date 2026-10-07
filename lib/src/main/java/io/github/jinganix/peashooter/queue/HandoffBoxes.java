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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single handoff-box template for executor handoffs.
 *
 * <p>One box type for every handoff (no per-kind {@code ThreadLocal}): at most one resident entry
 * per thread, reused across hot synchronous handoffs; async handoffs transfer ownership to the
 * runner thread and the submitter detaches, so the next async submit on this thread allocates a
 * fresh box (steady-state async costs one box per submit plus the per-submit {@link
 * io.github.jinganix.peashooter.queue.TaskQueue.Task}). The handshake inputs ride in an immutable
 * {@link Handshake} published through an {@link AtomicReference}, so no plain {@code caller}/{@code
 * owner} field relies on executor internals for visibility.
 *
 * <p><b>Executor happens-before contract:</b> the backing {@link java.util.concurrent.Executor}
 * must provide happens-before between the {@code execute()} call and the start of the task (all
 * {@code java.util.concurrent} executors do). Without it the handshake degrades to an async
 * transfer: the caller returns instead of looping inline and never releases a held external lock
 * early. At worst a racing inline handoff costs one extra async hop; an async handoff is never
 * misclassified as sync. The same contract covers the reverse direction: a caller unwinding a
 * throwing {@code execute()} reads {@link HandoffBox#runnerThread} to tell a started runner from a
 * scheduling rejection, so without happens-before such a failure counts as a rejection (the
 * head/trigger is discarded and notified) even though the command may run.
 */
final class HandoffBoxes {
  private HandoffBoxes() {}

  private static final ThreadLocal<HandoffBox> BOX = new ThreadLocal<>();

  /** Immutable handshake inputs for one transfer, published via atomics. */
  record Handshake(Thread caller, Runnable asyncDrain) {}

  /**
   * Single-use-per-transfer box, container reused across hot handoffs.
   *
   * <p>Handshake inputs are final inside {@link Handshake}; the container's atomics carry sync
   * detection only. {@code inUse} is thread-local bookkeeping (owning thread only, never read
   * cross-thread): re-entrant transfers take the allocating cold path instead of corrupting the
   * resident box.
   */
  static final class HandoffBox implements Runnable {
    /**
     * Thread that started this handoff's command, or {@code null} before it starts. Only the
     * handoff caller resets it (via {@link #clear()}); a runner never does, so it stays set after
     * the drain completes and the caller can still tell a started runner from a scheduling
     * rejection while unwinding a throwing {@code execute()}.
     */
    final AtomicReference<Thread> runnerThread = new AtomicReference<>();

    final AtomicBoolean callerReturned = new AtomicBoolean();
    final AtomicBoolean inlineSuppressed = new AtomicBoolean();
    final AtomicReference<Handshake> handshake = new AtomicReference<>();
    boolean inUse;
    boolean drainThrough;

    void prepare(Thread caller, Runnable asyncDrain) {
      this.inUse = true;
      this.drainThrough = false;
      this.handshake.set(new Handshake(caller, asyncDrain));
      this.runnerThread.set(null);
      this.inlineSuppressed.set(false);
      this.callerReturned.set(false);
    }

    void prepareSubmit(Thread caller, Runnable drain) {
      this.inUse = true;
      this.drainThrough = true;
      this.handshake.set(new Handshake(caller, drain));
      this.runnerThread.set(null);
      this.inlineSuppressed.set(false);
      this.callerReturned.set(false);
    }

    void clear() {
      this.handshake.set(null);
      this.inUse = false;
      this.drainThrough = false;
      // Release the last runner thread and reset detection flags: prepare() re-sets them on the
      // next transfer, but a resident box must not strongly retain a (possibly pooled) Thread
      // between handoffs.
      this.runnerThread.set(null);
      this.callerReturned.set(false);
      this.inlineSuppressed.set(false);
    }

    @Override
    public void run() {
      Thread current = Thread.currentThread();
      runnerThread.set(current);
      Handshake currentHandshake = handshake.get();
      if (drainThrough) {
        // Submit path: no caller drain loop exists, so the drain runs through the executor
        // itself (preserving executor interposition such as depth guards). Thread identity
        // tells the caller whether it ran inline.
        if (currentHandshake != null) {
          try {
            currentHandshake.asyncDrain().run();
          } finally {
            handshake.set(null);
          }
        }
        return;
      }
      boolean returned = callerReturned.get();
      if (currentHandshake != null && current == currentHandshake.caller() && !returned) {
        inlineSuppressed.set(true);
      } else if (currentHandshake != null) {
        // Async consumer: the caller already detached (hot) or never retained (cold) this box,
        // so dropping the handshake after the drain keeps no caller/drain refs behind. The
        // caller must not clear it: the async runner may not have started when the caller
        // returns, and an early clear would lose the drain.
        try {
          currentHandshake.asyncDrain().run();
        } finally {
          handshake.set(null);
        }
      }
    }

    boolean isSyncInline(Thread caller) {
      return inlineSuppressed.get() && runnerThread.get() == caller;
    }
  }

  /** Returns the thread-local box, allocating it lazily on first handoff. */
  static HandoffBox acquire() {
    HandoffBox box = BOX.get();
    if (box == null) {
      box = new HandoffBox();
      BOX.set(box);
    }
    return box;
  }

  /** Detaches the thread-local box after an async handoff; the next handoff reallocates. */
  static void detach() {
    BOX.remove();
  }

  /**
   * Reinstalls a consumed submit box for reuse on this thread. Overwrites any box retained by
   * nested handoffs (orphaned for GC, never leaked): at most one resident entry per thread is kept,
   * so steady-state submits allocate nothing.
   */
  static void reinstall(HandoffBox box) {
    box.clear();
    BOX.set(box);
  }
}
