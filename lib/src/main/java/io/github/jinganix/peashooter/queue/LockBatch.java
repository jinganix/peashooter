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
import io.github.jinganix.peashooter.internal.Interruptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * External-lock batch lifecycle for {@link LockableTaskQueue}.
 *
 * <p>Owns the held-lock flag, the acquire monitor, and the task-run/yield helpers that execute one
 * batch while holding the lock. The queue owns the unlock-in-flight gate (and all queue state);
 * this class owns lock pairing so every successful {@code tryLock} meets exactly one {@code unlock}
 * by the owning frame.
 */
final class LockBatch {

  private static final Logger log = LoggerFactory.getLogger(LockBatch.class);

  private final LockableTaskQueue queue;

  private final ExecutionStats stats;

  private final RetryScheduler retries;

  private volatile boolean locked = false;

  /**
   * Serializes external-lock acquisition attempts across competing runners. Never the queue
   * monitor: held while invoking the user-supplied {@code tryLock}.
   */
  private final Object acquireLock = new Object();

  LockBatch(LockableTaskQueue queue, ExecutionStats stats, RetryScheduler retries) {
    this.queue = queue;
    this.stats = stats;
    this.retries = retries;
  }

  boolean isLocked() {
    return locked;
  }

  boolean tryAcquire(LockableTaskQueue.RunState state) {
    if (queue.isUnlocking()) {
      return false;
    }
    if (locked) {
      return state.ownsBatch;
    }
    synchronized (acquireLock) {
      if (locked) {
        return state.ownsBatch;
      }
      boolean acquired;
      try {
        acquired = queue.tryLock(stats);
      } catch (Throwable e) {
        log.error("tryLock failed in {}; rescheduling runner", queue.describeQueue(), e);
        if (e instanceof Error err) {
          queue.clearClaim();
          throw err;
        }
        return false;
      }
      locked = acquired;
      if (acquired) {
        state.ownsBatch = true;
        retries.resetBackoff();
      }
      return acquired;
    }
  }

  /** Resets stats; {@code false} means reset threw and the runner must reschedule. */
  boolean resetStatsSafely() {
    try {
      stats.reset();
      return true;
    } catch (Throwable e) {
      log.error("stats.reset failed in {}; rescheduling runner", queue.describeQueue(), e);
      if (e instanceof Error err) {
        throw err;
      }
      return false;
    }
  }

  /**
   * Runs one task body, records stats, and checks yield.
   *
   * @return {@code true} to end the current batch
   */
  boolean runTaskAndCheckYield(TaskQueue.Task task) {
    try {
      task.runnable().run();
    } catch (Error e) {
      throw e;
    } catch (Throwable t) {
      if (Interruptions.carriesInterrupt(t)) {
        // Never swallow interrupts: restore the flag and end this batch. The outer release
        // unlocks and reschedules the preserved backlog; continuing would run the next task
        // on an interrupted thread. See TaskQueue.drain for pooled-thread tolerance.
        Thread.currentThread().interrupt();
        if (log.isWarnEnabled()) {
          log.warn(
              "Interrupted task in {} from task {}; ending batch with interrupt restored",
              queue.describeQueue(),
              task.runnable().getClass().getName(),
              t);
        }
        return true;
      }
      if (log.isErrorEnabled()) {
        log.error(
            "Caught unexpected Throwable in {} from task {}",
            queue.describeQueue(),
            task.runnable().getClass().getName(),
            t);
      }
    }
    try {
      stats.record();
    } catch (Error err) {
      log.error("stats.record failed in {}; ending batch", queue.describeQueue(), err);
      throw err;
    } catch (Throwable e) {
      log.error("stats.record failed in {}; ending batch", queue.describeQueue(), e);
      return true;
    }
    return shouldYieldSafely();
  }

  /**
   * Ends the current batch when {@code shouldYield} agrees. A throwing check ends the batch as
   * well: the completed task already made progress, and killing the runner here would discard the
   * still-queued tasks as a fake submission failure.
   */
  private boolean shouldYieldSafely() {
    try {
      return queue.shouldYield(stats);
    } catch (Error err) {
      log.error("shouldYield failed in {}; ending batch", queue.describeQueue(), err);
      throw err;
    } catch (Throwable e) {
      log.error("shouldYield failed in {}; ending batch", queue.describeQueue(), e);
      return true;
    }
  }

  /**
   * Ends the current batch when owned, releasing the external lock exactly once and rescheduling
   * pending work when this frame returns (not when it loops for the next batch in-thread).
   *
   * @param state explicit runner frame of the calling depth
   * @param callerReturning whether this iteration returns (vs looping for the next batch)
   * @param fatalError whether unwinding with a fatal task Error (no auto-retry, fail-open)
   * @param reschedule no-arg callback invoked when pending work remains after the release
   */
  void release(
      LockableTaskQueue.RunState state,
      boolean callerReturning,
      boolean fatalError,
      BatchReschedule reschedule) {
    if (!locked || !state.ownsBatch) {
      return;
    }
    queue.markUnlocking();
    boolean unlockFailedFatally = false;
    try {
      queue.unlock();
    } catch (Error err) {
      unlockFailedFatally = true;
      log.error("unlock failed in {}", queue.describeQueue(), err);
      throw err;
    } catch (Throwable e) {
      log.error("unlock failed in {}", queue.describeQueue(), e);
    } finally {
      boolean fatal = fatalError || unlockFailedFatally;
      // Clear the external hold before opening the release gate: a concurrent acquirer observing
      // unlocking==false with locked==true still backs off, while the reverse order would admit
      // a second batch without the external hold.
      locked = false;
      state.ownsBatch = false;
      // The rescheduler re-peeks the head under the queue monitor, so no executor is propagated.
      if (queue.finishUnlock(fatal, callerReturning && !fatal)) {
        reschedule.reschedule();
      }
    }
  }

  /** Reschedule callback used by {@link #release} to avoid a router/retry cycle. */
  @FunctionalInterface
  interface BatchReschedule {
    void reschedule();
  }
}
