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

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Coalesced delayed-retry scheduler for {@link LockableTaskQueue} lock contention.
 *
 * <p>Owns backoff state and timer coalescing: at most one unfired retry is outstanding per queue,
 * so contended keys cannot pile timer tasks faster than they fire. A claim stops coalescing as soon
 * as it starts or its future completes (cancelled or failed): the next request reserves a fresh
 * claim, so a lost timer cannot stick the queue starved.
 *
 * <p>Retries use exponential backoff with Full Jitter (AWS Architecture Blog): {@code sleep =
 * random(1, min(cap, base * 2^attempt))}. Fixed 1ms retries cause N×1000 QPS tryLock storms when N
 * queues contend on one external lock and starve timer threads; backoff keeps per-queue coalescing
 * while bounding cross-queue contention.
 */
final class RetryScheduler {

  static final long BACKOFF_BASE_MILLIS = 1;

  static final long BACKOFF_MAX_MILLIS = 100;

  /** Scheduler for retries; caller-owned, never shut down by the queue. */
  private final ScheduledExecutorService rescheduler;

  /**
   * Outstanding retry claim (at most one), or {@code null}. The claim is reserved before its timer
   * exists, so the timer always finds and clears its own claim: a retry that already started can
   * never be published afterwards as a live coalescing target (which would cancel the follow-up it
   * scheduled and strand the key's backlog).
   */
  private final AtomicReference<RetryClaim> rescheduleFuture = new AtomicReference<>();

  /**
   * Monitor for coalescing. Dedicated rather than the holder itself: synchronizing on a concurrent
   * object is fragile industry practice, while a private final lock keeps the monitor stable
   * regardless of holder type.
   */
  private final Object rescheduleLock = new Object();

  /** Consecutive failed tryLock rounds; reset on success to avoid permanent slowdown. */
  private final AtomicInteger backoffAttempts = new AtomicInteger();

  /** Jitter source for backoff delays; injected for deterministic tests. */
  private final LongSupplier jitter;

  RetryScheduler(ScheduledExecutorService rescheduler, LongSupplier jitter) {
    this.rescheduler = Objects.requireNonNull(rescheduler, "rescheduler");
    this.jitter = Objects.requireNonNull(jitter, "jitter");
  }

  static long computeBackoffDelayMillis(int attempt, LongSupplier jitter) {
    int shift = Math.min(Math.max(attempt, 0), 7);
    long cap = Math.min(BACKOFF_MAX_MILLIS, BACKOFF_BASE_MILLIS << shift);
    if (cap <= 1) {
      return 1;
    }
    return 1 + Math.floorMod(jitter.getAsLong(), cap);
  }

  /** Resets backoff after a successful acquisition to avoid permanent slowdown. */
  void resetBackoff() {
    backoffAttempts.set(0);
  }

  /**
   * One reserved retry: the scheduled future (once known) plus whether its body already started.
   *
   * <p>{@link #fired} is set before the body runs because {@link ScheduledFuture#isDone()} still
   * reports {@code false} while the body runs. Without it a follow-up requested from inside the
   * body would be coalesced onto the running retry and cancelled, leaving no timer and no runner
   * for a non-empty backlog.
   */
  private static final class RetryClaim {
    volatile boolean fired;
    volatile ScheduledFuture<?> future;
  }

  /**
   * Whether {@code claim} still coalesces: reserved (its future may not be published yet) and not
   * started and not done. A {@code null} future on a non-fired claim means another caller reserved
   * the slot and is about to schedule, so callers must coalesce onto it rather than start a second
   * timer.
   *
   * <p>A terminated scheduler can never run the timer: {@link
   * ScheduledExecutorService#shutdownNow()} drains queued tasks without completing their futures,
   * so {@link ScheduledFuture#isDone()} alone would report a dead claim as live forever and block
   * every retry for the key. Both queries are non-blocking state reads on the caller-supplied
   * scheduler, like {@code isDone()} itself.
   */
  private boolean isLive(RetryClaim claim) {
    if (claim == null || claim.fired) {
      // A started retry is never a coalescing target, including the few instructions between
      // `fired = true` and its own claim clearing: a request in that window must reserve a fresh
      // timer instead of being absorbed by the running body.
      return false;
    }
    if (claim.future == null) {
      return true;
    }
    return !claim.future.isDone() && !rescheduler.isTerminated();
  }

  /**
   * Whether an unfired retry is already outstanding (coalesce onto it instead of piling another
   * timer). A retry whose body started stops coalescing at that moment, so a request arriving
   * during the body reserves a fresh claim and schedules a follow-up timer.
   */
  boolean hasPendingRetry() {
    return isLive(rescheduleFuture.get());
  }

  /**
   * Self-heals a lost retry: schedule succeeded but never fired (shutdown, cancellation, or a dead
   * scheduler), leaving a phantom claim with no outstanding timer. Clears any claim that cannot
   * drive the queue any more so the caller may reclaim the runner.
   *
   * @return {@code true} when a dead claim was cleared
   */
  boolean reclaimDoneRetry() {
    RetryClaim pending = rescheduleFuture.get();
    if (pending == null || pending.future == null || isLive(pending)) {
      return false;
    }
    return rescheduleFuture.compareAndSet(pending, null);
  }

  /**
   * Schedules {@code retry} after backoff, coalescing onto an already-scheduled retry.
   *
   * <p>The claim is reserved under the coalescing monitor before the timer is created, so a retry
   * that fires immediately still clears its own claim and any follow-up it schedules survives. A
   * request arriving while a retry runs is not coalesced onto it (a running retry is not a
   * coalescing target): it reserves a fresh claim and schedules a follow-up timer.
   *
   * @param retry work to run on the rescheduler thread
   * @throws RuntimeException when the underlying scheduler rejects the retry (a checked failure
   *     smuggled past {@code schedule()}'s signature propagates unchanged)
   */
  void scheduleRetry(Runnable retry) {
    if (isLive(rescheduleFuture.get())) {
      return;
    }
    RetryClaim mine = new RetryClaim();
    // Both the claim and this lambda are allocated before the slot is reserved: a failure here
    // must not leave a claim whose timer was never scheduled (that would coalesce every later
    // request away and strand the backlog).
    Runnable clearing =
        () -> {
          // Mark started and drop our own claim before running: a running retry is not a
          // coalescing target, so a request arriving during the body reserves a fresh claim.
          mine.fired = true;
          rescheduleFuture.compareAndSet(mine, null);
          retry.run();
        };
    long delayMillis;
    synchronized (rescheduleLock) {
      if (isLive(rescheduleFuture.get())) {
        return;
      }
      int attempt = backoffAttempts.getAndUpdate(prev -> prev >= 7 ? 7 : prev + 1);
      delayMillis = computeBackoffDelayMillis(attempt, jitter);
      // Reserve before the timer exists: the fired retry clears this claim at the start of its
      // body, so a publish that lands late can never install an already-running retry as the live
      // claim and cancel the follow-up that retry scheduled.
      rescheduleFuture.set(mine);
    }
    // Third-party scheduler runs outside the coalescing monitor: holding our lock across an
    // arbitrary schedule() risks deadlock when the scheduler calls back or blocks.
    ScheduledFuture<?> scheduled;
    boolean scheduledOk = false;
    try {
      scheduled = rescheduler.schedule(clearing, delayMillis, TimeUnit.MILLISECONDS);
      scheduledOk = true;
    } finally {
      if (!scheduledOk) {
        // The timer never existed: drop the reservation so the next request schedules afresh.
        // Covers every failure shape, including a checked failure smuggled past schedule()'s
        // signature, without changing the propagating type. Backoff stays bumped: a dead
        // scheduler is a real failure, not a coalesce.
        rescheduleFuture.compareAndSet(mine, null);
      }
    }
    mine.future = scheduled;
  }
}
