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

import io.github.jinganix.peashooter.SpanAccessor;
import io.github.jinganix.peashooter.ThrowingSupplier;
import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.internal.KeySanitizer;
import io.github.jinganix.peashooter.trace.OrderedSpan;
import io.github.jinganix.peashooter.trace.OrderedTraceRunnable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Peer-free reentrant inline gate (Channel 2) for {@link OrderedTraceExecutor}.
 *
 * <p>Owns the per-thread nesting depth, maximum depth, and inline-execution count. A nested
 * same-key sync with no queued peers runs inline on the calling thread to avoid self-deadlock;
 * nesting with queued peers fails fast elsewhere so strict per-key FIFO holds. The depth budget is
 * per executor instance (not process-wide): each executor tracks its own per-thread depth.
 */
final class ReentrancyGate {

  /**
   * Maximum nested peer-free reentrant inline sync depth per thread (Channel 2). Deeper nesting
   * fails fast with {@link IllegalStateException} instead of recursing to {@link
   * StackOverflowError} (which would kill the runner thread). Sized to allow legitimate nesting
   * while keeping stack use bounded.
   *
   * <p><b>Unified stack budget:</b> Channel 1 ({@link DirectExecutor} inline, 32, process-wide) and
   * this Channel 2 (per executor instance) share the calling thread, so one thread can stack both.
   * Worst case per executor is the sum: 32 + 256 = 288 logical inline levels, each a constant
   * handful of Java frames, far below {@link StackOverflowError} on a default stack. Both channels
   * fail fast with {@link IllegalStateException} — never {@link StackOverflowError}.
   */
  static final int MAX_REENTRANT_DEPTH = 256;

  /**
   * Per-instance depth guard: the nesting budget belongs to this executor, not the process. A
   * shared static guard would couple independent executors on the same thread into one budget, so
   * one busy executor could starve another's legitimate nesting.
   */
  private final DepthGuard guard = new DepthGuard();

  /**
   * Times peer-free reentrant inline sync executions (nested same-key sync with no queued peers),
   * including executions whose delegate later fails: the counter increments after the {@link
   * #MAX_REENTRANT_DEPTH} gate passes but before the body runs, so depth-rejected attempts never
   * count while started executions (success or failure) do. Overtaking executions never occur:
   * nesting with queued peers fails fast instead of running.
   */
  private final AtomicLong reentrantInlineCount = new AtomicLong();

  /** Cumulative peer-free reentrant inline executions. */
  long getReentrantInlineCount() {
    return reentrantInlineCount.get();
  }

  /**
   * Whether the calling thread already holds {@code key} in its active span chain. A nested sync
   * for such a key would deadlock if enqueued behind itself, so it runs inline when no peers wait
   * and fails fast when peers would be overtaken.
   *
   * <p>Narrow dependency: reentrancy detection reads span storage only, so callers inject {@link
   * SpanAccessor} instead of the full {@link Tracer}.
   */
  boolean isReentrant(SpanAccessor spans, String key) {
    // invokedBy already treats a current span matching key as reentrant on its first iteration.
    return OrderedSpan.invokedBy(spans.getSpan(), key);
  }

  void runReentrantSync(Tracer tracer, String key, Runnable task) {
    runGuarded(
        key,
        () -> {
          OrderedTraceRunnable.forKey(tracer, key, true, task).run();
          return null;
        });
  }

  <R> R runReentrantSync(Tracer tracer, String key, Supplier<R> supplier) {
    return runGuarded(key, () -> OrderedTraceRunnable.runValue(tracer, key, supplier));
  }

  <R, E extends Throwable> R runReentrantChecked(
      Tracer tracer, String key, Class<E> type, ThrowingSupplier<R, E> supplier) throws E {
    return runGuarded(key, () -> OrderedTraceRunnable.runChecked(tracer, key, type, supplier));
  }

  private <R, E extends Throwable> R runGuarded(String key, ThrowingSupplier<R, E> body) throws E {
    guard.enter(
        MAX_REENTRANT_DEPTH,
        () ->
            new IllegalStateException(
                "Excessive nested sync depth for key '"
                    + KeySanitizer.sanitize(key)
                    + "': max "
                    + MAX_REENTRANT_DEPTH));
    try {
      recordReentrantInline();
      return body.get();
    } finally {
      guard.exit();
    }
  }

  private void recordReentrantInline() {
    // Zero-allocation saturating increment: hand-rolled get/CAS loop instead of
    // updateAndGet (which allocates its lambda on every call on this hot path).
    long current;
    do {
      current = reentrantInlineCount.get();
      if (current >= Long.MAX_VALUE) {
        return;
      }
    } while (!reentrantInlineCount.compareAndSet(current, current + 1));
  }
}
