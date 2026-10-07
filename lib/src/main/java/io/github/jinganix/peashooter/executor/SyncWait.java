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

package io.github.jinganix.peashooter.executor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Blocking-wait helpers for single-key sync submissions.
 *
 * <p>Owns deadline saturation, bounded waiting, timeout failure construction, and translation of
 * wait failures into sync exceptions. All state stays with the caller; this class holds no fields.
 */
final class SyncWait {

  private SyncWait() {}

  /**
   * Remaining wait clamped at zero so an elapsed deadline still polls an already-done future. The
   * saturated deadline never reaches here: {@link #awaitFuture} waits untimed instead of calling
   * this with {@link Long#MAX_VALUE} (subtracting from it could overflow negative on a JVM whose
   * {@code nanoTime} is negative).
   *
   * @param deadlineNanos saturating deadline in {@code nanoTime} units
   * @return remaining nanos, never negative
   */
  static long remainingNanos(long deadlineNanos) {
    long remaining = deadlineNanos - System.nanoTime();
    return remaining < 0 ? 0L : remaining;
  }

  /**
   * Waits for {@code future} until {@code deadlineNanos}. A saturated deadline waits without a
   * timeout instead of calling {@code get(Long.MAX_VALUE, NANOSECONDS)}: the timed overload adds
   * the timeout to {@code nanoTime} inside the JDK and may overflow to an immediate timeout on some
   * implementations, degrading "wait forever" into an instant failure.
   *
   * <p>Package-visible for production use by {@link OrderedTraceExecutor} in this package (its
   * narrowest callable visibility); tests cover it through the public sync API (saturated-timeout
   * submissions), never by calling it directly.
   *
   * @param future future to wait on, must not be {@code null}
   * @param deadlineNanos saturating deadline in {@code nanoTime} units
   * @param <R> result type
   * @return future result
   * @throws InterruptedException if the wait is interrupted
   * @throws ExecutionException if the future completes exceptionally
   * @throws TimeoutException if the deadline expires first
   */
  static <R> R awaitFuture(CompletableFuture<R> future, long deadlineNanos)
      throws InterruptedException, ExecutionException, TimeoutException {
    if (deadlineNanos == Long.MAX_VALUE) {
      return future.get();
    }
    return future.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
  }

  /**
   * Timeout failure carrying the configured wait, the remaining wait, the ordering key, and the
   * submission future for cancel/dedup.
   *
   * <p>Timeout means submitted: the caller must treat the work as submitted and deduplicate
   * non-idempotent retries via the key/future.
   *
   * @param key ordering key
   * @param waitNanos configured wait in nanos
   * @param deadlineNanos shared deadline in {@code nanoTime} units
   * @param cause timeout cause
   * @param future submission future for the timed-out work, must not be {@code null}
   * @return timeout failure, never {@code null}
   */
  static TraceTimeoutException timeoutFor(
      String key,
      long waitNanos,
      long deadlineNanos,
      TimeoutException cause,
      CompletableFuture<?> future) {
    // Remaining is ~0 on timeout; reporting both disambiguates nested multi-key timeouts where
    // the shared deadline leaves a small remaining wait though the configured wait is large.
    long remaining = remainingNanos(deadlineNanos);
    return TraceTimeoutException.forTimeout(key, waitNanos, remaining, cause, future);
  }

  /**
   * Saturating deadline ({@code nanoTime} + wait) for one sync call.
   *
   * @param waitNanos configured wait in nanos
   * @return deadline in {@code nanoTime} units, {@link Long#MAX_VALUE} when the wait is unbounded
   */
  static long deadlineOf(long waitNanos) {
    return deadlineOf(System.nanoTime(), waitNanos);
  }

  /**
   * Saturating deadline for a known {@code now}, package-visible for deterministic tests (the
   * no-arg overload reads {@code nanoTime} directly and cannot be pinned to negative origins).
   */
  static long deadlineOf(long now, long waitNanos) {
    if (waitNanos == Long.MAX_VALUE) {
      // Wait-forever must not add: on a JVM whose nanoTime is negative the sum stays finite
      // and degrades an infinite wait into a finite one.
      return Long.MAX_VALUE;
    }
    // Saturate on positive overflow only. Negative now can never overflow: MAX - now
    // would itself wrap (MAX - MIN == -1), but mathematically MAX - now > MAX >= wait,
    // so now + wait always fits. Only non-negative now needs the distance check.
    if (waitNanos > 0 && now >= 0 && Long.MAX_VALUE - now < waitNanos) {
      return Long.MAX_VALUE;
    }
    return now + waitNanos;
  }

  /**
   * Translates an interrupt during a sync wait into a {@link TraceInterruptedException} and
   * restores the interrupt flag.
   *
   * <p>Interruption means submitted: the work stays submitted; the future carries the cancel/dedup
   * handle.
   *
   * @param e interrupt cause
   * @param key ordering key that was interrupted
   * @param future submission future for the interrupted wait, must not be {@code null}
   * @return interruption failure, never {@code null}
   */
  static RuntimeException syncException(
      InterruptedException e, String key, CompletableFuture<?> future) {
    Thread.currentThread().interrupt();
    return TraceInterruptedException.forInterrupt(e, key, future);
  }
}
