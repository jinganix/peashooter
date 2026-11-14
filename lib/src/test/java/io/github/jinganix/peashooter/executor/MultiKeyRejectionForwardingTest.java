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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.ExecutorSelector;
import io.github.jinganix.peashooter.ThrowingSupplier;
import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.queue.RejectionAware;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Multi-key rejection forwarding")
class MultiKeyRejectionForwardingTest {

  private static final List<String> KEYS = List.of("fwd-a", "fwd-b", "fwd-c");

  /** Runnable payload that records runs and queue rejections. */
  static final class AwareRunnable implements Runnable, RejectionAware {
    final AtomicInteger runs = new AtomicInteger();
    final AtomicInteger rejections = new AtomicInteger();
    final AtomicReference<Throwable> rejected = new AtomicReference<>();

    @Override
    public void run() {
      runs.incrementAndGet();
    }

    @Override
    public void rejected(Throwable cause) {
      rejections.incrementAndGet();
      rejected.set(cause);
    }
  }

  /** Supplier payload that records runs and queue rejections. */
  static final class AwareSupplier implements Supplier<String>, RejectionAware {
    final AtomicInteger runs = new AtomicInteger();
    final AtomicInteger rejections = new AtomicInteger();
    final AtomicReference<Throwable> rejected = new AtomicReference<>();

    @Override
    public String get() {
      runs.incrementAndGet();
      return "value";
    }

    @Override
    public void rejected(Throwable cause) {
      rejections.incrementAndGet();
      rejected.set(cause);
    }
  }

  /** Checked-supplier payload that records runs and queue rejections. */
  static final class AwareCheckedSupplier
      implements ThrowingSupplier<String, IOException>, RejectionAware {
    final AtomicInteger runs = new AtomicInteger();
    final AtomicInteger rejections = new AtomicInteger();
    final AtomicReference<Throwable> rejected = new AtomicReference<>();

    @Override
    public String get() throws IOException {
      runs.incrementAndGet();
      return "value";
    }

    @Override
    public void rejected(Throwable cause) {
      rejections.incrementAndGet();
      rejected.set(cause);
    }
  }

  /** Rejects the {@code target}-th key submission of a chain. */
  private static ExecutorSelector rejectNth(
      AtomicInteger calls, int target, RuntimeException failure, Executor fallback) {
    return (queue, sync) -> {
      if (calls.incrementAndGet() == target) {
        throw failure;
      }
      return fallback;
    };
  }

  private static OrderedTraceExecutor newExecutor(ExecutorSelector selector, DefaultTracer tracer) {
    return new OrderedTraceExecutor(new CaffeineTaskQueueProvider(), selector, tracer);
  }

  @ParameterizedTest(name = "reject key submission {0}")
  @ValueSource(ints = {1, 2, 3})
  @DisplayName("should notify a runnable whichever key rejects an async multi-key submission")
  void shouldNotifyRunnableWhicheverKeyRejectsAsyncMultiKeySubmission(int target) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      RuntimeException failure = new RuntimeException("reject " + target);
      AtomicInteger calls = new AtomicInteger();
      DefaultTracer tracer = new DefaultTracer();
      TraceExecutor fallback = new TraceExecutor(pool, tracer);
      OrderedTraceExecutor executor =
          newExecutor(rejectNth(calls, target, failure, fallback), tracer);
      AwareRunnable task = new AwareRunnable();

      // When the selected key's submission is rejected
      CompletableFuture<Void> future = executor.submitAsync(KEYS, task);

      // Then the outer future fails (a selector Exception is contained) and the payload observes it
      assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCause(failure);
      assertThat(task.runs.get()).isZero();
      assertThat(task.rejected.get()).isSameAs(failure);
      assertThat(task.rejections.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "should throw and notify when the outermost executor rejects an async multi-key call")
  void shouldThrowAndNotifyWhenOutermostExecutorRejectsAsyncMultiKeyCall() {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      RuntimeException failure = new RuntimeException("outer executor reject");
      AtomicInteger calls = new AtomicInteger();
      DefaultTracer tracer = new DefaultTracer();
      TraceExecutor fallback = new TraceExecutor(pool, tracer);
      OrderedTraceExecutor executor =
          newExecutor(
              (queue, sync) -> {
                if (calls.incrementAndGet() == 1) {
                  return command -> {
                    throw failure;
                  };
                }
                return fallback;
              },
              tracer);
      AwareRunnable task = new AwareRunnable();

      // When the outermost executor rejects the handoff
      // Then the caller gets no future and the payload observes the rejection
      assertThatThrownBy(() -> executor.submitAsync(KEYS, task)).isSameAs(failure);
      assertThat(task.runs.get()).isZero();
      assertThat(task.rejected.get()).isSameAs(failure);
      assertThat(task.rejections.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should notify a supplier when a middle key rejects a sync multi-key supply")
  void shouldNotifySupplierWhenMiddleKeyRejectsSyncMultiKeySupply() {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      RuntimeException failure = new RuntimeException("middle reject");
      AtomicInteger calls = new AtomicInteger();
      DefaultTracer tracer = new DefaultTracer();
      TraceExecutor fallback = new TraceExecutor(pool, tracer);
      OrderedTraceExecutor executor = newExecutor(rejectNth(calls, 2, failure, fallback), tracer);
      AwareSupplier supplier = new AwareSupplier();

      // When the middle key's submission is rejected
      // Then the caller sees the failure and the payload observes the rejection
      assertThatThrownBy(() -> executor.supply(KEYS, supplier)).isSameAs(failure);
      assertThat(supplier.runs.get()).isZero();
      assertThat(supplier.rejected.get()).isSameAs(failure);
      assertThat(supplier.rejections.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should notify a checked supplier when a middle key rejects a multi-key supply")
  void shouldNotifyCheckedSupplierWhenMiddleKeyRejectsMultiKeySupply() {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      RuntimeException failure = new RuntimeException("middle reject");
      AtomicInteger calls = new AtomicInteger();
      DefaultTracer tracer = new DefaultTracer();
      TraceExecutor fallback = new TraceExecutor(pool, tracer);
      OrderedTraceExecutor executor = newExecutor(rejectNth(calls, 2, failure, fallback), tracer);
      AwareCheckedSupplier supplier = new AwareCheckedSupplier();

      // When the middle key's submission is rejected
      // Then the caller sees the failure and the payload observes the rejection
      assertThatThrownBy(() -> executor.supplyChecked(KEYS, IOException.class, supplier))
          .isSameAs(failure);
      assertThat(supplier.runs.get()).isZero();
      assertThat(supplier.rejected.get()).isSameAs(failure);
      assertThat(supplier.rejections.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
