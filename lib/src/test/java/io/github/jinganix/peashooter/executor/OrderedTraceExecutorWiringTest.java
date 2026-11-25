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
  @DisplayName("should reject a negative builder timeout without building")
  void shouldRejectNegativeBuilderTimeoutWithoutBuilding() {
    assertThatThrownBy(() ->
            OrderedTraceExecutor.builder(DirectExecutor.INSTANCE).timeout(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be negative");
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
        .hasMessageContaining("only 1 can run concurrently");
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
  @DisplayName("should reject illegal keys on isIdle even when the provider is lax")
  void shouldRejectIllegalKeysOnIsIdleEvenWhenProviderIsLax() {
    // Given a provider that skips validation (foreign implementations must not weaken the facade)
    io.github.jinganix.peashooter.TaskQueueProvider lax =
        new io.github.jinganix.peashooter.TaskQueueProvider() {
          @Override
          public boolean isIdle(String key) {
            return true;
          }

          @Override
          public boolean hasPending(String key) {
            return false;
          }

          @Override
          public io.github.jinganix.peashooter.queue.TaskQueue getForSubmit(String key) {
            throw new UnsupportedOperationException("not needed here");
          }

          @Override
          public void abortSubmit(
              String key, io.github.jinganix.peashooter.queue.TaskQueue queue) {}
        };
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            lax, (queue, sync) -> DirectExecutor.INSTANCE, new DefaultTracer());

    // When / Then illegal keys fail on the facade instead of reporting a false idle
    assertThatThrownBy(() -> executor.isIdle(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> executor.isIdle("  ")).isInstanceOf(IllegalArgumentException.class);
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
  @DisplayName("should surface a max threads supplier failure instead of skipping the sizing guard")
  void shouldSurfaceMaxThreadsSupplierFailureInsteadOfSkippingSizingGuard() {
    // Given a supplier that reports a bug instead of a maximum
    OrderedTraceExecutor executor =
        OrderedTraceExecutor.builder(DirectExecutor.INSTANCE)
            .maxPoolThreads(() -> {
              throw new IllegalStateException("sizing supplier boom");
            })
            .build();

    // When the maximum cannot be resolved Then the bug surfaces instead of silently
    // disabling fail-fast (which would turn a fast rejection into a timeout or hang)
    assertThatThrownBy(() -> executor.executeSync(List.of("wire-a", "wire-b"), () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sizing supplier boom");
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
  @DisplayName("should build from a selector alone without a backing pool")
  void shouldBuildFromSelectorAloneWithoutBackingPool() {
    // Given a selector that decides scheduling on its own
    AtomicBoolean ran = new AtomicBoolean();
    OrderedTraceExecutor executor =
        OrderedTraceExecutor.builder((queue, sync) -> DirectExecutor.INSTANCE).build();

    // When sync and fire-and-forget work is submitted Then no dummy pool is needed
    executor.executeSync("wire-nosrc-key", () -> ran.set(true));
    assertThat(ran).isTrue();
    assertThat(executor.getTracer()).isNotNull();
  }

  @Test
  @DisplayName("should run a fire-and-forget multi-key chain in sorted order")
  void shouldRunFireAndForgetMultiKeyChainInSortedOrder() {
    // Given a pool big enough for a two-key chain
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
      AtomicBoolean ran = new AtomicBoolean();

      // When fire-and-forget work holds two keys (unsorted input)
      executor.executeAsync(List.of("wire-ff-b", "wire-ff-a"), () -> ran.set(true));

      // Then a same-key barrier runs only after the chain, proving the chain ran
      executor.supply("wire-ff-a", () -> null);
      assertThat(ran).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should fail fast when fire-and-forget multi-key selection fails")
  void shouldFailFastWhenFireAndForgetMultiKeySelectionFails() {
    // Given a selector that reports a bug instead of an executor
    OrderedTraceExecutor executor =
        OrderedTraceExecutor.builder((queue, sync) -> DirectExecutor.INSTANCE)
            .selector((queue, sync) -> {
              throw new IllegalStateException("selector boom");
            })
            .build();

    // When the outermost selection fails Then the caller observes it instead of a dropped future
    assertThatThrownBy(
            () -> executor.executeAsync(List.of("wire-ff-fail-b", "wire-ff-fail-a"), () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("selector boom");
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
