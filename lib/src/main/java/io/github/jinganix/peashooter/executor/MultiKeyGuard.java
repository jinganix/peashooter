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

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.IntSupplier;

/**
 * Fail-fast sizing guard for multi-key self-deadlock.
 *
 * <p>Nested single-key levels each occupy a pool thread while waiting for the next level, so a pool
 * with fewer max threads than distinct keys cannot complete the chain and would otherwise wait
 * until the configured timeout (sync) or hang (async, unbounded wait). Throws {@link
 * IllegalStateException} instead when the maximum is known and insufficient.
 *
 * <p>Only {@link ThreadPoolExecutor} and {@link ForkJoinPool} maxima are introspectable via public
 * API. Every other executor (virtual-thread per-task pools, delegated wrappers, custom executors)
 * reports unknown and skips the check: construct with an explicit max-threads supplier to get
 * fail-fast sizing there. The explicit supplier is composed at construction time by the caller and
 * always wins over introspection.
 *
 * <p><b>Conservative by design:</b> one pool thread is counted per distinct key, ignoring the
 * idle-queue inline hint ({@link DefaultExecutorSelector}) and the calling thread (which holds the
 * outermost sync level): work that would succeed via inlining or on the caller's own thread still
 * fail-fasts. Size the pool for the distinct key count instead of relying on those shortcuts.
 *
 * <p>The maximum arrives as a single injected {@link IntSupplier} composed by the caller: this
 * guard never disassembles selectors or executors itself, so no Demeter chain ({@code selector ->
 * traceExecutor -> delegate}) lives here. Only the known {@link TraceExecutor} decorator is
 * unwrapped; every other wrapper stays opaque.
 */
final class MultiKeyGuard {

  /** Unknown pool maximum sentinel. */
  static final int UNKNOWN_MAX_THREADS = -1;

  private final IntSupplier maxPoolThreads;

  MultiKeyGuard(IntSupplier maxPoolThreads) {
    this.maxPoolThreads = Objects.requireNonNull(maxPoolThreads, "maxPoolThreads");
  }

  void checkMultiKeyPoolSize(int distinctKeys) {
    int max = resolveMaxPoolThreads();
    if (max < 0 || max >= distinctKeys) {
      return;
    }
    throw new IllegalStateException(
        "Multi-key call needs >= "
            + distinctKeys
            + " pool threads for "
            + distinctKeys
            + " distinct keys, but only "
            + max
            + " can run concurrently; size the backing pool >= "
            + distinctKeys
            + " (raise corePoolSize: workers past it appear only while the handoff queue refuses"
            + " offers) or construct with an explicit max-threads supplier");
  }

  /**
   * Resolves the injected maximum. The supplier is composed at construction time by the caller
   * ({@link OrderedTraceExecutor#composeMaxThreads}): a negative value means unknown, and a
   * throwing supplier surfaces its failure at submit time, so this guard performs no second
   * normalization.
   */
  private int resolveMaxPoolThreads() {
    int supplied = maxPoolThreads.getAsInt();
    return supplied >= 0 ? supplied : UNKNOWN_MAX_THREADS;
  }

  /**
   * Introspects the maximum parallelism of {@code executor}, unwrapping known decorator layers.
   *
   * <p>Only {@link TraceExecutor} decorators are unwrapped (via {@code instanceof}, no reflection):
   * any other wrapping executor is opaque and reports unknown unless the caller supplies an
   * explicit max-threads supplier at construction, which always wins over introspection. Reference
   * cycles cannot form through the single known decorator type; the walk is still depth-bounded for
   * safety.
   *
   * <p>Only the innermost {@link ThreadPoolExecutor} / {@link ForkJoinPool} reports a maximum;
   * anything else is unknown. {@link ForkJoinPool#getParallelism} is the target parallelism
   * (approximate: work-stealing may briefly run more threads under blocking compensation), so it
   * sizes fail-fast conservatively rather than exactly. A {@link ThreadPoolExecutor} reports the
   * parallelism it is <em>guaranteed</em> to reach, never the configured maximum: {@code execute()}
   * offers to the work queue instead of adding a worker, so a queue that accepts the offer leaves
   * the pool at {@code corePoolSize} (or 1 when core is 0, since the first task still adds a
   * worker). Workers past core appear only while offers are being refused, which is transient (a
   * bounded queue overflows) and cannot be relied on by a chain that holds every thread. Only a
   * {@link SynchronousQueue} refuses every offer and always grows to {@code maximumPoolSize}.
   * Over-reporting would hide exactly the self-deadlock this guard exists to reject.
   */
  static int maxThreadsOf(Executor executor) {
    Executor current = executor;
    for (int depth = 0; depth < 32 && current != null; depth++) {
      if (current instanceof ThreadPoolExecutor pool) {
        return pool.getQueue() instanceof SynchronousQueue
            ? pool.getMaximumPoolSize()
            : Math.max(1, pool.getCorePoolSize());
      }
      if (current instanceof ForkJoinPool forkJoin) {
        return forkJoin.getParallelism();
      }
      if (current instanceof TraceExecutor traced) {
        Executor next = traced.getDelegate();
        if (next == null || next == current) {
          return UNKNOWN_MAX_THREADS;
        }
        current = next;
        continue;
      }
      return UNKNOWN_MAX_THREADS;
    }
    return UNKNOWN_MAX_THREADS;
  }
}
