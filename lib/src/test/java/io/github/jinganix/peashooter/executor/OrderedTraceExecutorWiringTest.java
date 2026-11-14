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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import io.github.jinganix.peashooter.trace.Span;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OrderedTraceExecutor wiring")
class OrderedTraceExecutorWiringTest {

  /** Tracer that counts beforeCall invocations. */
  static final class CountingTracer extends DefaultTracer {
    final AtomicInteger beforeCalls = new AtomicInteger();

    @Override
    public void beforeCall(Span span) {
      beforeCalls.incrementAndGet();
    }
  }

  @Test
  @DisplayName("should expose the tracer set through the builder")
  void shouldExposeTracerSetThroughBuilder() {
    // Given a custom tracer
    DefaultTracer tracer = new DefaultTracer();

    // When
    OrderedTraceExecutor executor =
        OrderedTraceExecutor.builder(DirectExecutor.INSTANCE).tracer(tracer).build();

    // Then the facade hands that tracer to submissions
    assertThat(executor.getTracer()).isSameAs(tracer);
  }

  @Test
  @DisplayName("should apply the sync timeout set through the builder")
  void shouldApplySyncTimeoutSetThroughBuilder() {
    // Given a custom timeout
    Duration timeout = Duration.ofMillis(2500);

    // When
    OrderedTraceExecutor executor =
        OrderedTraceExecutor.builder(DirectExecutor.INSTANCE).timeout(timeout).build();

    // Then
    assertThat(executor.getTimeout()).isEqualTo(timeout);
  }

  @Test
  @DisplayName("should fail fast from the max pool threads set through the builder")
  void shouldFailFastFromMaxPoolThreadsSetThroughBuilder() {
    // Given a builder that declares a single pool thread
    OrderedTraceExecutor executor =
        OrderedTraceExecutor.builder(DirectExecutor.INSTANCE).maxPoolThreads(() -> 1).build();

    // When a two-key chain would need two threads Then it is rejected before waiting
    assertThatThrownBy(() -> executor.executeSync(List.of("wire-a", "wire-b"), () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max pool threads is 1");
  }

  @Test
  @DisplayName("should submit through the queue provider set through the builder")
  void shouldSubmitThroughQueueProviderSetThroughBuilder() {
    // Given a provider that tracks its live entries
    CaffeineTaskQueueProvider queues = new CaffeineTaskQueueProvider();
    OrderedTraceExecutor executor =
        OrderedTraceExecutor.builder(DirectExecutor.INSTANCE).queues(queues).build();

    // When
    executor.executeAsync("wire-provider-key", () -> {});

    // Then the submission landed on that provider
    assertThat(queues.estimatedSize()).isEqualTo(1);
    assertThat(executor.isIdle("wire-provider-key")).isTrue();
  }

  @Test
  @DisplayName("should route every submission through the selector set through the builder")
  void shouldRouteEverySubmissionThroughSelectorSetThroughBuilder() {
    // Given a selector that records its use
    AtomicBoolean selected = new AtomicBoolean();
    OrderedTraceExecutor executor =
        OrderedTraceExecutor.builder(DirectExecutor.INSTANCE)
            .selector((queue, sync) -> {
              selected.set(true);
              return DirectExecutor.INSTANCE;
            })
            .build();

    // When
    executor.executeAsync("wire-selector-key", () -> {});

    // Then
    assertThat(selected).isTrue();
  }

  @Test
  @DisplayName("should skip the sizing guard when a max threads supplier fails with an exception")
  void shouldSkipSizingGuardWhenMaxThreadsSupplierFailsWithException() {
    // Given a supplier that reports a bug instead of a maximum
    OrderedTraceExecutor executor =
        OrderedTraceExecutor.builder(DirectExecutor.INSTANCE)
            .maxPoolThreads(() -> {
              throw new IllegalStateException("sizing supplier boom");
            })
            .build();

    // When the maximum is unknown Then a two-key chain must not be rejected
    assertThatCode(() -> executor.executeSync(List.of("wire-a", "wire-b"), () -> {}))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("should honor an explicit max threads supplier passed to the constructor")
  void shouldHonorExplicitMaxThreadsSupplierPassedToConstructor() {
    // Given explicit components with a one-thread maximum
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(),
            (queue, sync) -> DirectExecutor.INSTANCE,
            new DefaultTracer(),
            () -> 1);

    // When / Then
    assertThatThrownBy(() -> executor.executeSync(List.of("wire-a", "wire-b"), () -> {}))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("should trace the queue runner with the builder tracer, not the backing tracer")
  void shouldTraceQueueRunnerWithBuilderTracerNotBackingTracer() {
    // Given a TraceExecutor whose own tracer differs from the builder's tracer
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountingTracer backingTracer = new CountingTracer();
      CountingTracer builderTracer = new CountingTracer();
      OrderedTraceExecutor executor =
          OrderedTraceExecutor.builder(new TraceExecutor(pool, backingTracer))
              .tracer(builderTracer)
              .build();

      // When one ordered task runs
      executor.executeSync("wire-tracer-key", () -> {});

      // Then the queue runner and the task are traced by the builder tracer only
      assertThat(builderTracer.beforeCalls.get()).isPositive();
      assertThat(backingTracer.beforeCalls.get()).isZero();
    } finally {
      pool.shutdownNow();
    }
  }
}
