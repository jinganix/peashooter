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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Multi-key pool fail-fast")
class MultiKeyPoolFailFastTest {

  private static ThreadPoolExecutor singleThreadPool() {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    pool.setThreadFactory(
        runnable -> {
          Thread thread = new Thread(runnable, "single-pool");
          thread.setDaemon(true);
          return thread;
        });
    return pool;
  }

  @Test
  @DisplayName("should fail fast when a single-thread pool runs a two-key sync")
  void shouldFailFastWhenSingleThreadPoolRunsTwoKeySync() {
    ThreadPoolExecutor pool = singleThreadPool();
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
    executor.setTimeout(Duration.ofSeconds(2));
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    executor.executeAsync(
        "multi-a",
        () -> {
          try {
            releaseBlocker.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      // When the only pool thread is held by key multi-a and 2 distinct keys are requested
      long start = System.nanoTime();
      assertThatThrownBy(() -> executor.executeSync(List.of("multi-a", "multi-b"), () -> {}))
          // Then fail-fast with a clear sizing error instead of waiting out the 2s timeout
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("2")
          .hasMessageContaining("1");
      assertThatElapsedWellBelowTimeout(System.nanoTime() - start, TimeUnit.SECONDS.toNanos(2));
    } finally {
      releaseBlocker.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should not fail fast when a virtual-thread pool runs a two-key sync")
  void shouldNotFailFastWhenVirtualThreadPoolRunsTwoKeySync() {
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
    executor.setTimeout(Duration.ofSeconds(10));
    CountDownLatch blockerDone = new CountDownLatch(1);
    executor.executeAsync(
        "multi-va",
        () -> {
          try {
            Thread.sleep(300);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            blockerDone.countDown();
          }
        });
    try {
      // When the same contended shape runs on unbounded virtual threads
      // Then no false positive: the chain waits out the brief blocker and completes
      assertThatCode(() -> executor.executeSync(List.of("multi-va", "multi-vb"), () -> {}))
          .doesNotThrowAnyException();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should fail fast when component construction has an undersized pool")
  void shouldFailFastWhenComponentConstructionHasUndersizedPool() {
    ThreadPoolExecutor pool = singleThreadPool();
    try {
      TraceExecutor trace = new TraceExecutor(pool, new DefaultTracer());
      OrderedTraceExecutor executor =
          new OrderedTraceExecutor(
              new CaffeineTaskQueueProvider(),
              new DefaultExecutorSelector(trace),
              new DefaultTracer());
      executor.setTimeout(Duration.ofSeconds(2));
      CountDownLatch releaseBlocker = new CountDownLatch(1);
      executor.executeAsync(
          "multi-ca",
          () -> {
            try {
              releaseBlocker.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      try {
        // When component construction wraps the same undersized pool
        long start = System.nanoTime();
        assertThatThrownBy(() -> executor.executeSync(List.of("multi-ca", "multi-cb"), () -> {}))
            // Then the same fail-fast applies (selector delegate introspected)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("2");
        assertThatElapsedWellBelowTimeout(System.nanoTime() - start, TimeUnit.SECONDS.toNanos(2));
      } finally {
        releaseBlocker.countDown();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should not fail fast when component construction has virtual threads")
  void shouldNotFailFastWhenComponentConstructionHasVirtualThreads() {
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    try {
      TraceExecutor trace = new TraceExecutor(pool, new DefaultTracer());
      OrderedTraceExecutor executor =
          new OrderedTraceExecutor(
              new CaffeineTaskQueueProvider(),
              new DefaultExecutorSelector(trace),
              new DefaultTracer());
      executor.setTimeout(Duration.ofSeconds(10));
      assertThatCode(() -> executor.executeSync(List.of("multi-cva", "multi-cvb"), () -> {}))
          .doesNotThrowAnyException();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should fail fast when an explicit supplier reports an undersized wrapped pool")
  void shouldFailFastWhenExplicitSupplierReportsUndersizedWrappedPool() {
    // Delegated wrappers hide ThreadPoolExecutor: the explicit supplier closes the gap.
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      OrderedTraceExecutor executor = new OrderedTraceExecutor(pool, () -> 1);
      executor.setTimeout(Duration.ofSeconds(2));
      long start = System.nanoTime();
      assertThatThrownBy(() -> executor.executeSync(List.of("multi-wa", "multi-wb"), () -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("2");
      assertThatElapsedWellBelowTimeout(System.nanoTime() - start, TimeUnit.SECONDS.toNanos(2));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should not fail fast when an explicit supplier reports sufficient threads")
  void shouldNotFailFastWhenExplicitSupplierReportsSufficientThreads() {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      OrderedTraceExecutor executor = new OrderedTraceExecutor(pool, () -> 8);
      executor.setTimeout(Duration.ofSeconds(10));
      assertThatCode(() -> executor.executeSync(List.of("multi-sa", "multi-sb"), () -> {}))
          .doesNotThrowAnyException();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should not fail fast when a builder custom selector routes to a sufficient pool")
  void shouldNotFailFastWhenBuilderCustomSelectorRoutesToSufficientPool() {
    ThreadPoolExecutor smallBacking = singleThreadPool();
    ExecutorService largePool =
        Executors.newFixedThreadPool(
            4,
            runnable -> {
              Thread thread = new Thread(runnable, "large-pool");
              thread.setDaemon(true);
              return thread;
            });
    try {
      TraceExecutor routed = new TraceExecutor(largePool, new DefaultTracer());
      OrderedTraceExecutor executor =
          OrderedTraceExecutor.builder(smallBacking).selector((queue, sync) -> routed).build();
      executor.setTimeout(Duration.ofSeconds(10));

      // When a custom selector routes work to a pool with enough threads
      // Then the guard must not size from the otherwise-unused backing executor
      assertThatCode(() -> executor.executeSync(List.of("mk-a", "mk-b"), () -> {}))
          .doesNotThrowAnyException();
    } finally {
      smallBacking.shutdownNow();
      largePool.shutdownNow();
    }
  }

  private static void assertThatElapsedWellBelowTimeout(long elapsedNanos, long timeoutNanos) {
    // Fail-fast must return in well under half the configured timeout.
    if (elapsedNanos >= timeoutNanos / 2) {
      throw new AssertionError(
          "expected fail-fast well below timeout but took " + elapsedNanos + "ns");
    }
  }
}
