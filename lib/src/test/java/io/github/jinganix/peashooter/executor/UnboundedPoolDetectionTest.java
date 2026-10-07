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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.jinganix.peashooter.trace.DefaultTracer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pool-maximum introspection uses only public API: thread pools and fork-join pools report their
 * real maximum, every other executor (including virtual-thread per-task pools) is unknown and skips
 * fail-fast unless an explicit max-threads supplier is given.
 */
@DisplayName("unbounded pool detection")
class UnboundedPoolDetectionTest {

  /** Bounded delegate whose class name contains the old fuzzy keyword. */
  static final class FakeThreadPerTaskExecutor implements Executor {
    private final Executor delegate;

    FakeThreadPerTaskExecutor(Executor delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
      delegate.execute(command);
    }
  }

  /** Bounded delegate whose class name contains the old fuzzy keyword. */
  static final class FakeVirtualThreadPool implements Executor {
    private final Executor delegate;

    FakeVirtualThreadPool(Executor delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
      delegate.execute(command);
    }
  }

  @Test
  @DisplayName("should treat a real virtual-thread pool as unknown and skip fail-fast")
  void shouldTreatRealVirtualThreadPoolAsUnknownAndSkipFailFast() {
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    try {
      assertThat(MultiKeyGuard.maxThreadsOf(pool)).isEqualTo(MultiKeyGuard.UNKNOWN_MAX_THREADS);
      OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
      executor.setTimeout(Duration.ofSeconds(10));
      assertThatCode(() -> executor.executeSync(List.of("f12-va", "f12-vb"), () -> {}))
          .doesNotThrowAnyException();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should not treat a keyword-named bounded pool as unbounded")
  void shouldNotTreatKeywordNamedBoundedPoolAsUnbounded() {
    ThreadPoolExecutor backing =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    try {
      assertThat(MultiKeyGuard.maxThreadsOf(new FakeThreadPerTaskExecutor(backing)))
          .isEqualTo(MultiKeyGuard.UNKNOWN_MAX_THREADS);
      assertThat(MultiKeyGuard.maxThreadsOf(new FakeVirtualThreadPool(backing)))
          .isEqualTo(MultiKeyGuard.UNKNOWN_MAX_THREADS);
    } finally {
      backing.shutdownNow();
    }
  }

  @Test
  @DisplayName("should unwrap trace layers without string matching")
  void shouldUnwrapTraceLayersWithoutStringMatching() {
    ThreadPoolExecutor backing =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
    try {
      assertThat(
              MultiKeyGuard.maxThreadsOf(
                  new TraceExecutor(new FakeThreadPerTaskExecutor(backing), new DefaultTracer())))
          .isEqualTo(MultiKeyGuard.UNKNOWN_MAX_THREADS);
      assertThat(MultiKeyGuard.maxThreadsOf(new TraceExecutor(virtual, new DefaultTracer())))
          .isEqualTo(MultiKeyGuard.UNKNOWN_MAX_THREADS);
    } finally {
      backing.shutdownNow();
      virtual.shutdownNow();
    }
  }
}
