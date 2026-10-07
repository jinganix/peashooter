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

import static io.github.jinganix.peashooter.utils.TestUtils.awaitCountDown;
import static io.github.jinganix.peashooter.utils.TestUtils.sleep;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.ExecutorSelector;
import io.github.jinganix.peashooter.TaskQueueProvider;
import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.queue.ExecutionCountStats;
import io.github.jinganix.peashooter.queue.LockableTaskQueue;
import io.github.jinganix.peashooter.queue.TaskQueue;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import io.github.jinganix.peashooter.trace.TraceRunnable;
import io.github.jinganix.peashooter.utils.InMemoryTaskQueueProvider;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.support.ParameterDeclarations;
import org.mockito.MockedConstruction;

@DisplayName("OrderedTraceExecutor")
class OrderedTraceExecutorTest {

  private static final String DIRECT_EXECUTOR = "DirectExecutor";

  private static final String SINGLE_THREAD_EXECUTOR = "SingleThreadExecutor";

  private static final Tracer TRACER = new DefaultTracer();

  private static final ExecutorFixture EXECUTORS = new ExecutorFixture();

  @AfterAll
  static void closeOwnedExecutors() {
    EXECUTORS.close();
  }

  static OrderedTraceExecutor createExecutor(
      Executor executor, TaskQueueProvider taskQueueProvider) {
    TraceExecutor traceExecutor = new TraceExecutor(executor, TRACER);
    DefaultExecutorSelector selector = new DefaultExecutorSelector(traceExecutor);
    return new OrderedTraceExecutor(taskQueueProvider, selector, TRACER);
  }

  private static final ScheduledExecutorService UNLOCKED_RESCHEDULER =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
          });

  static TaskQueueProvider unlockedQueues() {
    return new InMemoryTaskQueueProvider(
        key ->
            new LockableTaskQueue(new ExecutionCountStats(), UNLOCKED_RESCHEDULER) {
              @Override
              protected boolean tryLock(ExecutionStats stats) {
                return true;
              }

              @Override
              protected boolean shouldYield(ExecutionStats stats) {
                return false;
              }

              @Override
              protected void unlock() {}
            });
  }

  @Nested
  @DisplayName("when validating arguments")
  class WhenValidatingArguments {

    private final OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());

    @Test
    @DisplayName("should reject null key on executeAsync")
    void shouldRejectNullKeyOnExecuteAsync() {
      assertThatThrownBy(() -> executor.executeAsync(null, () -> {}))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("key");
    }

    @Test
    @DisplayName("should reject null key on executeSync")
    void shouldRejectNullKeyOnExecuteSync() {
      assertThatThrownBy(() -> executor.executeSync((String) null, () -> {}))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("key");
    }

    @Test
    @DisplayName("should reject null element in multi-key executeSync")
    void shouldRejectNullElementInMultiKeyExecuteSync() {
      assertThatThrownBy(() -> executor.executeSync(Arrays.asList("a", null), () -> {}))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("key");
    }

    @Test
    @DisplayName("should report index when multi-key contains null")
    void shouldReportIndexWhenMultiKeyContainsNull() {
      assertThatThrownBy(() -> executor.executeSync(Arrays.asList("a", null, "b"), () -> {}))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("1");
    }

    @Test
    @DisplayName("should expose only Duration timeout accessors without lossy overloads")
    void shouldExposeOnlyDurationTimeoutAccessorsWithoutLossyOverloads()
        throws NoSuchMethodException {
      // Timeout configuration is one canonical shape: Duration in, Duration out. The
      // long+TimeUnit overloads are deleted (not deprecated): getTimeout(unit) truncates
      // toward zero and setTimeout saturates huge values, both silently lossy.
      assertThat(
              Arrays.stream(OrderedTraceExecutor.class.getMethods())
                  .filter(m -> m.getName().equals("setTimeout"))
                  .map(m -> m.getParameterTypes()))
          .containsExactly(new Class<?>[] {java.time.Duration.class});
      assertThat(
              Arrays.stream(OrderedTraceExecutor.class.getMethods())
                  .filter(m -> m.getName().equals("getTimeout"))
                  .map(m -> m.getParameterTypes()))
          .containsExactly(new Class<?>[] {});
    }

    @Test
    @DisplayName("should expose only keys-first multi-key overloads without varargs shims")
    void shouldExposeOnlyKeysFirstMultiKeyOverloads() throws NoSuchMethodException {
      // The multi-key surface is one canonical shape per operation: keys first (matching the
      // single-key order), Collection only. Varargs *All shims are deleted, not deprecated.
      assertThat(
              Arrays.stream(OrderedTraceExecutor.class.getMethods())
                  .map(java.lang.reflect.Method::getName))
          .doesNotContain("executeSyncAll", "supplyAll", "supplyCheckedAll");
      assertThat(
              OrderedTraceExecutor.class.getMethod(
                  "executeSync", java.util.Collection.class, Runnable.class))
          .isNotNull();
      assertThat(
              OrderedTraceExecutor.class.getMethod(
                  "supply", java.util.Collection.class, Supplier.class))
          .isNotNull();
      assertThat(
              OrderedTraceExecutor.class.getMethod(
                  "supplyChecked",
                  java.util.Collection.class,
                  Class.class,
                  io.github.jinganix.peashooter.ThrowingSupplier.class))
          .isNotNull();
    }

    @Test
    @DisplayName("should run reverse-ordered multi-key calls in sorted order")
    void shouldRunReverseOrderedMultiKeyCallsInSortedOrder() {
      // Given reverse-ordered keys via the canonical keys-first shape
      java.util.concurrent.atomic.AtomicInteger calls =
          new java.util.concurrent.atomic.AtomicInteger();
      executor.executeSync(java.util.List.of("b", "a"), calls::incrementAndGet);
      executor.executeSync(java.util.List.of("b", "a"), calls::incrementAndGet);
      Integer first = executor.supply(java.util.List.of("b", "a"), () -> 1);
      Integer second = executor.supply(java.util.List.of("b", "a"), () -> 2);

      // Then
      assertThat(calls.get()).isEqualTo(2);
      assertThat(first).isEqualTo(1);
      assertThat(second).isEqualTo(2);
    }

    @Test
    @DisplayName("should support multi-key executeSync")
    void shouldSupportMultiKeyExecuteSync() {
      // When keys given in reverse order, deadlock-free sorting must still run once
      java.util.concurrent.atomic.AtomicInteger calls =
          new java.util.concurrent.atomic.AtomicInteger();
      executor.executeSync(java.util.List.of("b", "a", "a"), () -> calls.incrementAndGet());

      // Then
      assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("should reject empty key collection on executeSync")
    void shouldRejectEmptyKeyCollectionOnExecuteSync() {
      assertThatThrownBy(() -> executor.executeSync(Collections.emptyList(), () -> {}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("keys");
    }

    @Test
    @DisplayName("should reject null key collection on multi-key paths")
    void shouldRejectNullKeyCollectionOnMultiKeyPaths() {
      assertThatThrownBy(() -> executor.executeSync((Collection<String>) null, () -> {}))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("keys");
      assertThatThrownBy(() -> executor.supply((Collection<String>) null, () -> 1))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("keys");
    }

    @Test
    @DisplayName("should reject empty key collection on supply")
    void shouldRejectEmptyKeyCollectionOnSupply() {
      assertThatThrownBy(() -> executor.supply(Collections.emptyList(), () -> 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("keys");
    }

    @Test
    @DisplayName("should reject null key on supply")
    void shouldRejectNullKeyOnSupply() {
      assertThatThrownBy(() -> executor.supply((String) null, () -> 1))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("key");
    }

    @Test
    @DisplayName("should reject empty key on single-key paths")
    void shouldRejectEmptyKeyOnSingleKeyPaths() {
      assertThatThrownBy(() -> executor.executeAsync("", () -> {}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("empty");
      assertThatThrownBy(() -> executor.executeSync("", () -> {}))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> executor.supply("", () -> 1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject empty element in multi-key paths")
    void shouldRejectEmptyElementInMultiKeyPaths() {
      assertThatThrownBy(() -> executor.executeSync(Arrays.asList("a", ""), () -> {}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("empty");
      assertThatThrownBy(() -> executor.supply(Arrays.asList(""), () -> 1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject blank element in multi-key paths")
    void shouldRejectBlankElementInMultiKeyPaths() {
      assertThatThrownBy(() -> executor.executeSync(Arrays.asList("a", " "), () -> {}))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("should construct trace executor when built from executor service")
  void shouldConstructTraceExecutorWhenBuiltFromExecutorService() {
    // When / Then
    try (MockedConstruction<TraceExecutor> provider = mockConstruction(TraceExecutor.class)) {
      assertThatCode(() -> new OrderedTraceExecutor(mock(ExecutorService.class)))
          .doesNotThrowAnyException();
      assertThat(provider.constructed()).hasSize(1);
    }
  }

  @Test
  @DisplayName("should reuse trace executor when built from existing trace executor")
  void shouldReuseTraceExecutorWhenBuiltFromExistingTraceExecutor() {
    // When / Then
    try (MockedConstruction<TraceExecutor> provider = mockConstruction(TraceExecutor.class)) {
      assertThatCode(() -> new OrderedTraceExecutor(mock(TraceExecutor.class)))
          .doesNotThrowAnyException();
      assertThat(provider.constructed()).isEmpty();
    }
  }

  @Test
  @DisplayName("should still run queued task after sync caller times out")
  void shouldStillRunQueuedTaskAfterSyncCallerTimesOut() { // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofMillis(50));
    java.util.concurrent.atomic.AtomicBoolean finished =
        new java.util.concurrent.atomic.AtomicBoolean();

    // When
    assertThatThrownBy(
            () ->
                executor.executeSync(
                    "a",
                    () -> {
                      sleep(200);
                      finished.set(true);
                    }))
        .isInstanceOf(RuntimeException.class)
        .matches(t -> t.getCause() instanceof TimeoutException);

    // Then
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(finished::get);
  }

  @Test
  @DisplayName("should surface executor rejection on executeSync instead of timing out")
  void shouldSurfaceExecutorRejectionOnExecuteSyncInsteadOfTimingOut() {
    // Given
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException("rejected")).when(rejecting).execute(any());
    OrderedTraceExecutor executor = createExecutor(rejecting, new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofSeconds(10));

    // When / Then
    assertThatThrownBy(() -> executor.executeSync("a", () -> {}))
        .isInstanceOf(RejectedExecutionException.class)
        .hasMessageContaining("rejected");
  }

  @Test
  @DisplayName("should surface executor rejection on supply instead of timing out")
  void shouldSurfaceExecutorRejectionOnSupplyInsteadOfTimingOut() {
    // Given
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException("rejected")).when(rejecting).execute(any());
    OrderedTraceExecutor executor = createExecutor(rejecting, new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofSeconds(10));

    // When / Then
    assertThatThrownBy(() -> executor.supply("a", () -> "value"))
        .isInstanceOf(RejectedExecutionException.class)
        .hasMessageContaining("rejected");
  }

  @Test
  @DisplayName("should release submit pin when selector throws")
  void shouldReleaseSubmitPinWhenSelectorThrows() {
    // Given an executor whose selector violates its no-throw contract
    CaffeineTaskQueueProvider queues = new CaffeineTaskQueueProvider();
    ExecutorSelector throwing =
        (queue, sync) -> {
          throw new IllegalStateException("selector boom");
        };
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, throwing, new DefaultTracer());

    // When / Then the original failure propagates (never degrades to a timeout)
    assertThatThrownBy(() -> executor.executeSync("k", () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("selector boom");

    // And the abandoned submit fence is released instead of pinning the entry forever
    assertThat(queues.invalidateIfIdle("k")).isTrue();
  }

  @Test
  @DisplayName("should complete async submission when provider fence release throws")
  void shouldCompleteAsyncSubmissionWhenProviderFenceReleaseThrows() throws Exception {
    // Given a selector that throws while the provider's fence release also throws
    TaskQueueProvider queues =
        new InMemoryTaskQueueProvider(key -> new TaskQueue()) {
          @Override
          public void abortSubmit(String key, TaskQueue queue) {
            throw new IllegalStateException("fence release boom");
          }
        };
    ExecutorSelector throwing =
        (queue, sync) -> {
          throw new IllegalStateException("selector boom");
        };
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, throwing, new DefaultTracer());

    // When
    CompletableFuture<Void> future = executor.submitAsync("k", () -> {});

    // Then the submission completes with the selector failure instead of hanging forever
    assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasRootCauseMessage("selector boom");
    assertThat(future).isCompletedExceptionally();
  }

  @Test
  @DisplayName("should propagate selector failure when provider fence release throws")
  void shouldPropagateSelectorFailureWhenProviderFenceReleaseThrows() {
    // Given a selector that throws while the provider's fence release also throws
    TaskQueueProvider queues =
        new InMemoryTaskQueueProvider(key -> new TaskQueue()) {
          @Override
          public void abortSubmit(String key, TaskQueue queue) {
            throw new IllegalStateException("fence release boom");
          }
        };
    ExecutorSelector throwing =
        (queue, sync) -> {
          throw new IllegalStateException("selector boom");
        };
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, throwing, new DefaultTracer());

    // When / Then the original selector failure propagates with the fence failure suppressed
    assertThatThrownBy(() -> executor.executeSync("k", () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("selector boom")
        .satisfies(
            failure ->
                assertThat(failure.getSuppressed())
                    .anySatisfy(
                        suppressed ->
                            assertThat(suppressed).hasMessageContaining("fence release boom")));
  }

  @Test
  @DisplayName("should release submit pin when selector returns null")
  void shouldReleaseSubmitPinWhenSelectorReturnsNull() {
    // Given an executor whose selector violates its non-null contract
    CaffeineTaskQueueProvider queues = new CaffeineTaskQueueProvider();
    ExecutorSelector nullReturning = (queue, sync) -> null;
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(queues, nullReturning, new DefaultTracer());

    // When / Then the misuse fails fast instead of degrading to a timeout
    assertThatThrownBy(() -> executor.executeSync("k", () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("null");

    // And the abandoned submit fence is released instead of pinning the entry forever
    assertThat(queues.invalidateIfIdle("k")).isTrue();
  }

  @Test
  @DisplayName("should propagate original selector failure when rejection callback throws")
  void shouldPropagateOriginalSelectorFailureWhenRejectionCallbackThrows() {
    // Given a selector failing with A and a task whose rejection callback throws B
    CaffeineTaskQueueProvider queues = new CaffeineTaskQueueProvider();
    IllegalStateException selectorFailure = new IllegalStateException("selector boom");
    ExecutorSelector throwing =
        (queue, sync) -> {
          throw selectorFailure;
        };
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, throwing, new DefaultTracer());
    final class HostileTask
        implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        throw new IllegalArgumentException("callback boom");
      }
    }
    Runnable hostile = new HostileTask();

    // When / Then the original selector failure propagates, not the callback failure
    assertThatThrownBy(() -> executor.executeSync("k", hostile)).isSameAs(selectorFailure);

    // And the abandoned submit fence is still released
    assertThat(queues.invalidateIfIdle("k")).isTrue();
  }

  @Test
  @DisplayName("should not mask selector failure when rejection callback throws Error")
  void shouldNotMaskSelectorFailureWhenRejectionCallbackThrowsError() {
    // Given a selector failing with A and a task whose rejection callback throws an Error
    CaffeineTaskQueueProvider queues = new CaffeineTaskQueueProvider();
    IllegalStateException selectorFailure = new IllegalStateException("selector boom");
    ExecutorSelector throwing =
        (queue, sync) -> {
          throw selectorFailure;
        };
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, throwing, new DefaultTracer());
    final class HostileErrorTask
        implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        throw new AssertionError("callback boom");
      }
    }

    // When / Then the original selector failure propagates with the callback Error suppressed
    assertThatThrownBy(() -> executor.executeSync("k", new HostileErrorTask()))
        .isSameAs(selectorFailure);
    assertThat(selectorFailure.getSuppressed())
        .anySatisfy(suppressed -> assertThat(suppressed).isInstanceOf(AssertionError.class));

    // And the abandoned submit fence is still released
    assertThat(queues.invalidateIfIdle("k")).isTrue();
  }

  @Test
  @DisplayName("should surface selector Error unwrapped")
  void shouldSurfaceSelectorErrorUnwrapped() {
    // Given an executor whose selector fails with an Error
    CaffeineTaskQueueProvider queues = new CaffeineTaskQueueProvider();
    AssertionError failure = new AssertionError("selector boom");
    ExecutorSelector throwing =
        (queue, sync) -> {
          throw failure;
        };
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, throwing, new DefaultTracer());

    // When / Then the original Error surfaces and the fence is still released
    assertThatThrownBy(() -> executor.executeSync("k", () -> {})).isSameAs(failure);
    assertThat(queues.invalidateIfIdle("k")).isTrue();
  }

  @Test
  @DisplayName("should wrap sneaky selector failure in CompletionException")
  void shouldWrapSneakySelectorFailureInCompletionException() {
    // Given an executor whose selector smuggles a checked failure past its signature
    CaffeineTaskQueueProvider queues = new CaffeineTaskQueueProvider();
    ExecutorSelector throwing =
        (queue, sync) -> {
          sneakyThrow(new java.io.IOException("sneaky selector boom"));
          throw new AssertionError("unreachable");
        };
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, throwing, new DefaultTracer());

    // When / Then the smuggled failure surfaces wrapped and the fence is still released
    assertThatThrownBy(() -> executor.executeSync("k", () -> {}))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCauseInstanceOf(java.io.IOException.class);
    assertThat(queues.invalidateIfIdle("k")).isTrue();
  }

  @Test
  @DisplayName("should return failed future when submitAsync selector throws")
  void shouldReturnFailedFutureWhenSubmitAsyncSelectorThrows() {
    // Given an executor whose selector violates its no-throw contract
    CaffeineTaskQueueProvider queues = new CaffeineTaskQueueProvider();
    IllegalStateException selectorFailure = new IllegalStateException("selector boom");
    ExecutorSelector throwing =
        (queue, sync) -> {
          throw selectorFailure;
        };
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, throwing, new DefaultTracer());

    // When / Then submitAsync must return the failed future instead of throwing it away:
    // the failure is already delivered via RejectionAware, the caller keeps the handle
    CompletableFuture<Void> future = executor.submitAsync("k", () -> {});
    assertThat(future).isCompletedExceptionally();
    assertThatThrownBy(future::join).hasCause(selectorFailure);

    // And the abandoned submit fence is still released
    assertThat(queues.invalidateIfIdle("k")).isTrue();
  }

  @Test
  @DisplayName("should propagate Error when submitAsync selector fails fatally")
  void shouldPropagateErrorWhenSubmitAsyncSelectorFailsFatally() {
    // Given a selector failing with a fatal Error (e.g. AssertionError from a broken selector)
    CaffeineTaskQueueProvider queues = new CaffeineTaskQueueProvider();
    AssertionError fatal = new AssertionError("selector boom");
    ExecutorSelector throwing =
        (queue, sync) -> {
          throw fatal;
        };
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, throwing, new DefaultTracer());

    // When / Then the fatal Error propagates instead of degrading to a failed future
    assertThatThrownBy(() -> executor.submitAsync("k", () -> {})).isSameAs(fatal);

    // And the abandoned submit fence is still released
    assertThat(queues.invalidateIfIdle("k")).isTrue();
  }

  @Test
  @DisplayName("should surface span setup failure on executeSync instead of timing out")
  void shouldSurfaceSpanSetupFailureOnExecuteSyncInsteadOfTimingOut() {
    // Given a tracer whose span ids fail on the runner thread
    Tracer brokenIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw new IllegalStateException("id boom");
          }
        };
    TraceExecutor traceExecutor = new TraceExecutor(newSingleThreadExecutor(), brokenIds);
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(), new DefaultExecutorSelector(traceExecutor), brokenIds);
    executor.setTimeout(Duration.ofSeconds(2));

    // When / Then the setup failure surfaces fast, never as a timeout
    assertThatThrownBy(() -> executor.executeSync("k", () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("id boom");
  }

  @Test
  @DisplayName("should complete async future when span setup fails")
  void shouldCompleteAsyncFutureWhenSpanSetupFails() throws Exception {
    // Given a tracer whose span ids fail on the runner thread
    Tracer brokenIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw new IllegalStateException("id boom");
          }
        };
    TraceExecutor traceExecutor = new TraceExecutor(newSingleThreadExecutor(), brokenIds);
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(), new DefaultExecutorSelector(traceExecutor), brokenIds);

    // When a task is submitted async Then its future fails instead of hanging forever
    CompletableFuture<Void> future = executor.submitAsync("k", () -> {});
    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("should not masquerade sneaky setup failure as checked type")
  void shouldNotMasqueradeSneakySetupFailureAsCheckedType() {
    // Given a tracer smuggling a checked failure past its signature on the runner thread
    Tracer sneakyIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            OrderedTraceExecutorTest.sneakyThrow(new java.io.IOException("sneaky id boom"));
            throw new AssertionError("unreachable");
          }
        };
    TraceExecutor traceExecutor = new TraceExecutor(newSingleThreadExecutor(), sneakyIds);
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(), new DefaultExecutorSelector(traceExecutor), sneakyIds);
    executor.setTimeout(Duration.ofSeconds(5));

    // When / Then a checked supply must not catch the smuggled IOException as its own E:
    // infrastructure failures surface wrapped instead of masquerading under a false type
    assertThatThrownBy(
            () ->
                executor.<String, CustomChecked>supplyChecked(
                    "k",
                    CustomChecked.class,
                    () -> {
                      throw new CustomChecked("task failure");
                    }))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCauseInstanceOf(java.io.IOException.class);
  }

  @Test
  @DisplayName("should not masquerade sneaky setup failure as checked type when reentrant")
  void shouldNotMasqueradeSneakySetupFailureAsCheckedTypeWhenReentrant() {
    // Given a tracer whose first span id succeeds (outer span) but later ones smuggle checked
    java.util.concurrent.atomic.AtomicInteger spanIds =
        new java.util.concurrent.atomic.AtomicInteger();
    // Given a tracer whose first two span ids succeed (runner wrapper + outer spans) but
    // later ones smuggle checked failures (the reentrant inner span)
    Tracer flakyIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            int n = spanIds.incrementAndGet();
            if (n <= 2) {
              return "1111111111111111";
            }
            OrderedTraceExecutorTest.sneakyThrow(new java.io.IOException("sneaky id boom"));
            throw new AssertionError("unreachable");
          }
        };
    TraceExecutor traceExecutor = new TraceExecutor(newSingleThreadExecutor(), flakyIds);
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(), new DefaultExecutorSelector(traceExecutor), flakyIds);
    executor.setTimeout(Duration.ofSeconds(10));
    AtomicReference<Throwable> nestedFailure = new AtomicReference<>();

    // When a reentrant same-key checked supply hits the setup failure Then it must surface
    // wrapped instead of masquerading as the supplier's checked type
    executor.executeSync(
        "k",
        () -> {
          try {
            sneakyCall(
                () ->
                    executor.<String, CustomChecked>supplyChecked(
                        "k",
                        CustomChecked.class,
                        () -> {
                          throw new CustomChecked("task failure");
                        }));
          } catch (Throwable e) {
            nestedFailure.set(e);
          }
        });
    assertThat(nestedFailure.get())
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCauseInstanceOf(java.io.IOException.class);
  }

  @Test
  @DisplayName("should run untraced when tracer storage fails")
  void shouldRunUntracedWhenTracerStorageFails() {
    // Given a tracer whose span storage throws on install
    Tracer brokenStorage =
        new DefaultTracer() {
          @Override
          public void setSpan(io.github.jinganix.peashooter.trace.Span span) {
            throw new IllegalStateException("storage boom");
          }
        };
    TraceExecutor traceExecutor = new TraceExecutor(newSingleThreadExecutor(), brokenStorage);
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(),
            new DefaultExecutorSelector(traceExecutor),
            brokenStorage);
    executor.setTimeout(Duration.ofSeconds(2));

    // When / Then work still runs (untraced) instead of stranding until timeout
    AtomicReference<String> result = new AtomicReference<>();
    executor.executeSync("k", () -> result.set("done"));
    assertThat(result.get()).isEqualTo("done");
  }

  @Test
  @DisplayName("should time out when sync work exceeds configured timeout")
  void shouldTimeOutWhenSyncWorkExceedsConfiguredTimeout() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofMillis(1));

    // When / Then
    assertThatThrownBy(() -> executor.executeSync("a", () -> sleep(100)))
        .isInstanceOf(RuntimeException.class)
        .matches(t -> t.getCause() instanceof TimeoutException);
  }

  @Test
  @DisplayName("should time out immediately when timeout is zero and work is queued")
  void shouldTimeOutImmediatelyWhenTimeoutIsZeroAndWorkIsQueued() {
    // Given one pool thread occupied by key "a" and a zero timeout (no wait)
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ZERO);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    executor.executeAsync(
        "a",
        () -> {
          try {
            releaseBlocker.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    // When a sync call queues behind the blocker Then it fails immediately instead of waiting
    long startNanos = System.nanoTime();
    try {
      assertThatThrownBy(() -> executor.executeSync("a", () -> {}))
          .isInstanceOf(TraceTimeoutException.class)
          .matches(t -> t.getCause() instanceof TimeoutException);
    } finally {
      releaseBlocker.countDown();
    }

    // And the wait was immediate (covers the non-positive wait path in deadlineOf)
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    assertThat(elapsedMillis).isLessThan(500);
  }

  @Test
  @DisplayName("should sanitize control characters in timeout message")
  void shouldSanitizeControlCharactersInTimeoutMessage() {
    // Given one pool thread occupied and a key carrying log-forging controls
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ZERO);
    String key = "a\nforged\rline\u0000\u0007\u001B[2J\t\u007F\u0085\u2028\u2029";
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    executor.executeAsync(
        key,
        () -> {
          try {
            releaseBlocker.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      // When a sync call queues behind the blocker Then the timeout message stays on one line
      assertThatThrownBy(() -> executor.executeSync(key, () -> {}))
          .isInstanceOf(TraceTimeoutException.class)
          .satisfies(
              ex -> {
                assertThat(ex.getMessage())
                    .doesNotContain("\n", "\r", "\u0000", "\u0007", "\u001B");
                assertThat(((TraceTimeoutException) ex).getKey()).isEqualTo(key);
              });
    } finally {
      releaseBlocker.countDown();
    }
  }

  @Test
  @DisplayName("should bound multi-key sync wait by one global timeout")
  void shouldBoundMultiKeySyncWaitByOneGlobalTimeout() {
    // Given one pool thread occupied by key "a" and a 1s timeout
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofSeconds(1));
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    executor.executeAsync(
        "a",
        () -> {
          try {
            releaseBlocker.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    // When a two-key sync call waits on the blocked key
    long startNanos = System.nanoTime();
    try {
      assertThatThrownBy(() -> executor.executeSync(Arrays.asList("a", "b"), () -> {}))
          .isInstanceOf(TraceTimeoutException.class)
          .matches(t -> t.getCause() instanceof TimeoutException);
    } finally {
      releaseBlocker.countDown();
    }

    // Then the wait is ~1 timeout, not one timeout per level (~2s before the shared deadline)
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    assertThat(elapsedMillis).isLessThan(1800);
  }

  @Test
  @DisplayName("should propagate typed checked failure via supplyChecked")
  void shouldPropagateTypedCheckedFailureViaSupplyChecked() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    java.io.IOException failure = new java.io.IOException("typed");

    // When / Then the checked failure keeps its static type (no RuntimeException re-wrap)
    assertThatThrownBy(
            () ->
                executor.<String, java.io.IOException>supplyChecked(
                    "a",
                    java.io.IOException.class,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("should propagate declared checked failure via supplyChecked")
  void shouldPropagateSneakyCheckedFailureViaSupplyChecked() {
    // Given a supplier throwing its declared checked failure
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    java.io.IOException failure = new java.io.IOException("declared");

    // When / Then supplyChecked unwraps the declared failure via its Class witness
    assertThatThrownBy(
            () ->
                executor.<String, java.io.IOException>supplyChecked(
                    "a",
                    java.io.IOException.class,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("should pass RuntimeException through supplyChecked unwrapped")
  void shouldPassRuntimeExceptionThroughSupplyCheckedUnwrapped() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    RuntimeException failure = new RuntimeException("boom");

    // When / Then
    assertThatThrownBy(
            () ->
                executor.<String, RuntimeException>supplyChecked(
                    "a",
                    RuntimeException.class,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("should support multi-key sync in sorted order")
  void shouldSupportMultiKeySyncInSortedOrder() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    AtomicInteger calls = new AtomicInteger();

    // When keys given in reverse order through the canonical keys-first shape
    executor.executeSync(java.util.List.of("b", "a"), () -> calls.incrementAndGet());
    Integer value = executor.supply(java.util.List.of("b", "a"), () -> 1);

    // Then
    assertThat(calls.get()).isEqualTo(1);
    assertThat(value).isEqualTo(1);
  }

  @Test
  @DisplayName("should return value via single-key supplyChecked")
  void shouldReturnValueViaSingleKeySupplyChecked() throws Exception {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());

    // When / Then the success path completes the waiter normally
    assertThat(
            executor.<String, java.io.IOException>supplyChecked(
                "a", java.io.IOException.class, () -> "ok"))
        .isEqualTo("ok");
  }

  @Test
  @DisplayName("should run checked supplier across multiple key collection shapes")
  void shouldRunCheckedSupplierAcrossMultipleKeyCollectionShapes() throws Exception {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());

    // When keys given in reverse order via a List and a Set
    String viaList =
        executor.<String, java.io.IOException>supplyChecked(
            java.util.List.of("b", "a"), java.io.IOException.class, () -> "list");
    String viaSet =
        executor.<String, java.io.IOException>supplyChecked(
            java.util.Set.of("b", "a"), java.io.IOException.class, () -> "set");

    // Then both order the keys and return values
    assertThat(viaList).isEqualTo("list");
    assertThat(viaSet).isEqualTo("set");
  }

  @Test
  @DisplayName("should propagate typed checked failure via multi-key supplyChecked")
  void shouldPropagateTypedCheckedFailureViaMultiKeySupplyChecked() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    java.io.IOException failure = new java.io.IOException("typed");

    // When / Then the checked failure keeps its static type across key nesting
    assertThatThrownBy(
            () ->
                executor.<String, java.io.IOException>supplyChecked(
                    java.util.List.of("b", "a"),
                    java.io.IOException.class,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("should run checked supplier inline when reentrant on the same key")
  void shouldRunCheckedSupplierInlineWhenReentrantOnTheSameKey()
      throws Exception { // Given an executor with an observable tracer
    Tracer tracer = new DefaultTracer();
    Executor executor = EXECUTORS.executors().get(SINGLE_THREAD_EXECUTOR);
    TraceExecutor traceExecutor = new TraceExecutor(executor, tracer);
    OrderedTraceExecutor orderedExecutor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(), new DefaultExecutorSelector(traceExecutor), tracer);
    AtomicReference<io.github.jinganix.peashooter.trace.Span> outerSpan = new AtomicReference<>();
    AtomicReference<io.github.jinganix.peashooter.trace.Span> innerSpan = new AtomicReference<>();

    // When nested supplyChecked on the same key runs inline via invokedBy
    String nested =
        orderedExecutor.<String, java.io.IOException>supplyChecked(
            "a",
            java.io.IOException.class,
            () -> {
              outerSpan.set(tracer.getSpan());
              String inner =
                  orderedExecutor.<String, java.io.IOException>supplyChecked(
                      "a",
                      java.io.IOException.class,
                      () -> {
                        innerSpan.set(tracer.getSpan());
                        return "nested";
                      });
              return inner;
            });

    // Then the value and the child span prove the inline reentrant path (not a queued round-trip)
    assertThat(nested).isEqualTo("nested");
    assertThat(outerSpan.get()).isNotNull();
    assertThat(innerSpan.get()).isNotNull().isNotSameAs(outerSpan.get());
  }

  @Test
  @DisplayName("should throw TraceInterruptedException when checked supply is interrupted")
  void shouldThrowTraceInterruptedExceptionWhenCheckedSupplyIsInterrupted() {
    // Given a blocked checked supply
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.executeAsync("a", () -> sleep(200));
    Thread.currentThread().interrupt();

    // When / Then the waiter surfaces interruption without swallowing the flag
    assertThatThrownBy(
            () ->
                executor.<String, RuntimeException>supplyChecked(
                    "a",
                    RuntimeException.class,
                    () -> {
                      sleep(100);
                      return null;
                    }))
        .isInstanceOf(TraceInterruptedException.class);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    Thread.interrupted();
  }

  @Test
  @DisplayName("should time out checked supply when work exceeds configured timeout")
  void shouldTimeOutCheckedSupplyWhenWorkExceedsConfiguredTimeout() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofMillis(1));

    // When / Then
    assertThatThrownBy(
            () ->
                executor.<String, RuntimeException>supplyChecked(
                    "a",
                    RuntimeException.class,
                    () -> {
                      sleep(100);
                      return null;
                    }))
        .isInstanceOf(TraceTimeoutException.class);
  }

  @Test
  @DisplayName("should surface Error cause from checked supply without wrapping")
  void shouldSurfaceErrorCauseFromCheckedSupplyWithoutWrapping() {
    // Given a supplier failing with an Error
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    AssertionError failure = new AssertionError("boom");

    // When / Then the original Error surfaces, not a wrapper
    org.junit.jupiter.api.Assertions.assertTimeout(
        Duration.ofMillis(500),
        (org.junit.jupiter.api.function.ThrowingSupplier<Void>)
            () -> {
              assertThatThrownBy(
                      () ->
                          executor.<String, RuntimeException>supplyChecked(
                              "a",
                              RuntimeException.class,
                              () -> {
                                throw failure;
                              }))
                  .isSameAs(failure);
              return null;
            });
  }

  @Test
  @DisplayName("should forward rejection to RejectionAware checked supplier on discard")
  void shouldForwardRejectionToRejectionAwareCheckedSupplierOnDiscard() { // Given a rejecting
    // executor and a
    // rejection-aware checked
    // supplier
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException("rejected")).when(rejecting).execute(any());
    OrderedTraceExecutor executor = createExecutor(rejecting, new CaffeineTaskQueueProvider());
    AtomicReference<Throwable> rejectedCause = new AtomicReference<>();
    CountDownLatch rejectedLatch = new CountDownLatch(1);
    io.github.jinganix.peashooter.ThrowingSupplier<String, RuntimeException> supplier =
        new RejectionAwareCheckedSupplier(rejectedCause, rejectedLatch);

    // When the checked supply is discarded Then the future fails and the supplier is notified
    assertThatThrownBy(
            () ->
                executor.<String, RuntimeException>supplyChecked(
                    "a", RuntimeException.class, supplier))
        .isInstanceOf(RejectedExecutionException.class);
    awaitCountDown(rejectedLatch);
    assertThat(rejectedCause.get()).isInstanceOf(RejectedExecutionException.class);
  }

  static final class RejectionAwareCheckedSupplier
      implements io.github.jinganix.peashooter.ThrowingSupplier<String, RuntimeException>,
          io.github.jinganix.peashooter.queue.RejectionAware {
    private final AtomicReference<Throwable> causeRef;
    private final CountDownLatch latch;

    RejectionAwareCheckedSupplier(AtomicReference<Throwable> causeRef, CountDownLatch latch) {
      this.causeRef = causeRef;
      this.latch = latch;
    }

    @Override
    public String get() {
      return "unused";
    }

    @Override
    public void rejected(Throwable cause) {
      causeRef.set(cause);
      latch.countDown();
    }
  }

  @Test
  @DisplayName("should saturate deadline when timeout overflows nanoTime addition")
  void shouldSaturateDeadlineWhenTimeoutOverflowsNanoTimeAddition() {
    // Given the maximum expressible timeout (now + MAX_VALUE overflows)
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofNanos(Long.MAX_VALUE));
    AtomicInteger calls = new AtomicInteger();

    // When / Then work still runs under the saturated deadline
    executor.executeSync("a", calls::incrementAndGet);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("should saturate deadline when large timeout overflows nanoTime addition")
  void shouldSaturateDeadlineWhenLargeTimeoutOverflowsNanoTimeAddition() {
    // Given a near-maximum timeout whose now + wait overflows to negative on any JVM with
    // a positive nanoTime (i.e. normal uptime): covers the add-overflow saturating branch
    // in deadlineOf, distinct from the Long.MAX_VALUE early-return above.
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofNanos(Long.MAX_VALUE - 1));
    AtomicInteger calls = new AtomicInteger();

    // When / Then work still runs instead of degrading to an immediate timeout
    executor.executeSync("a", calls::incrementAndGet);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("should wait untimed when deadline is saturated to wait forever")
  void shouldWaitUntimedWhenDeadlineIsSaturated() {
    // Given an unbounded wait (saturating deadline): the timed CompletableFuture.get
    // overload adds the timeout to nanoTime inside the JDK and may overflow to an
    // immediate timeout on some implementations, so saturation must wait untimed.
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(Duration.ofNanos(Long.MAX_VALUE));

    // When supplying Then the untimed wait returns the value instead of timing out
    assertThat(executor.supply("a", () -> "ok")).isEqualTo("ok");
  }

  @Test
  @DisplayName("should wrap hostile executor rejection from checked supply without masquerading")
  void shouldWrapHostileExecutorRejectionFromCheckedSupplyWithoutMasquerading() {
    // Given an executor throwing its rejection past the Executor signature
    java.io.IOException failure = new java.io.IOException("rejection");
    Executor rejecting = mock(Executor.class);
    doAnswer(
            invocation -> {
              sneakyThrow(failure);
              return null;
            })
        .when(rejecting)
        .execute(any());
    OrderedTraceExecutor executor = createExecutor(rejecting, new CaffeineTaskQueueProvider());

    // When / Then the infrastructure rejection surfaces wrapped (never masquerading as the
    // delegate's declared failure): the cause chain still carries the original rejection
    assertThatThrownBy(
            () ->
                executor.<String, java.io.IOException>supplyChecked(
                    "a",
                    java.io.IOException.class,
                    () -> {
                      throw failure;
                    }))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasStackTraceContaining("rejection");
  }

  @Test
  @DisplayName("should propagate reentrant checked supplier failure")
  void shouldPropagateReentrantCheckedSupplierFailure() {
    // Given an executor with an observable tracer and a nested failure
    Tracer tracer = new DefaultTracer();
    Executor executor = EXECUTORS.executors().get(SINGLE_THREAD_EXECUTOR);
    TraceExecutor traceExecutor = new TraceExecutor(executor, tracer);
    OrderedTraceExecutor orderedExecutor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(), new DefaultExecutorSelector(traceExecutor), tracer);
    RuntimeException failure = new RuntimeException("nested boom");
    AtomicReference<io.github.jinganix.peashooter.trace.Span> innerSpan = new AtomicReference<>();

    // When the nested checked supplier fails Then the failure propagates from the inline path
    assertThatThrownBy(
            () ->
                orderedExecutor.<String, RuntimeException>supplyChecked(
                    "a",
                    RuntimeException.class,
                    () ->
                        sneakyCall(
                            () ->
                                orderedExecutor.<String, RuntimeException>supplyChecked(
                                    "a",
                                    RuntimeException.class,
                                    () -> {
                                      innerSpan.set(tracer.getSpan());
                                      throw failure;
                                    }))))
        .isSameAs(failure);
    assertThat(innerSpan.get()).isNotNull();
  }

  @Test
  @DisplayName("should propagate reentrant checked failure with its static type")
  void shouldPropagateReentrantCheckedFailureWithItsStaticType() {
    // Given an executor with an observable tracer and a nested checked failure
    Tracer tracer = new DefaultTracer();
    Executor executor = EXECUTORS.executors().get(SINGLE_THREAD_EXECUTOR);
    TraceExecutor traceExecutor = new TraceExecutor(executor, tracer);
    OrderedTraceExecutor orderedExecutor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(), new DefaultExecutorSelector(traceExecutor), tracer);
    java.io.IOException failure = new java.io.IOException("nested typed");

    // When the nested checked supplier fails Then the typed failure propagates inline
    assertThatThrownBy(
            () ->
                orderedExecutor.<String, java.io.IOException>supplyChecked(
                    "a",
                    java.io.IOException.class,
                    () ->
                        sneakyCall(
                            () ->
                                orderedExecutor.<String, java.io.IOException>supplyChecked(
                                    "a",
                                    java.io.IOException.class,
                                    () -> {
                                      throw failure;
                                    }))))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("should report idle without creating a queue for a fresh key")
  void shouldReportIdleWithoutCreatingAQueueForAFreshKey() {
    // Given a provider that never saw the key
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    OrderedTraceExecutor executor = createExecutor(newSingleThreadExecutor(), provider);

    // When / Then probing idleness reports idle and materializes nothing
    assertThat(executor.isIdle("fresh")).isTrue();
    assertThat(provider.invalidateIfIdle("fresh")).isFalse();
  }

  @Test
  @DisplayName("should expose per-key idle probe")
  void shouldExposePerKeyIdleProbe() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    assertThat(executor.isIdle("k")).isTrue();

    // When work is in flight on the key
    executor.executeAsync(
        "k",
        () -> {
          started.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    awaitCountDown(started);

    // Then busy, and idle again after draining
    assertThat(executor.isIdle("k")).isFalse();
    release.countDown();
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> executor.isIdle("k"));
  }

  @Test
  @DisplayName("should throw TraceInterruptedException when sync call is interrupted")
  void shouldThrowTraceInterruptedExceptionWhenSyncCallIsInterrupted() {
    // Given an occupied key and a pre-interrupted waiter
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.executeAsync("a", () -> sleep(200));
    Thread.currentThread().interrupt();

    // When / Then interruption surfaces as the dedicated type with the flag restored
    try {
      assertThatThrownBy(() -> executor.executeSync("a", () -> {}))
          .isInstanceOf(TraceInterruptedException.class)
          .matches(
              t ->
                  t.getCause() instanceof InterruptedException
                      && ((TraceInterruptedException) t).getInterruptCause() == t.getCause());
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("should still run sync task when tracer beforeCall throws")
  void shouldStillRunSyncTaskWhenTracerBeforeCallThrows() {
    DefaultTracer storage = new DefaultTracer();
    Tracer throwing =
        new Tracer() {
          @Override
          public io.github.jinganix.peashooter.trace.Span getSpan() {
            return storage.getSpan();
          }

          @Override
          public void setSpan(io.github.jinganix.peashooter.trace.Span span) {
            storage.setSpan(span);
          }

          @Override
          public void clearSpan() {
            storage.clearSpan();
          }

          @Override
          public String nextTraceId() {
            return storage.nextTraceId();
          }

          @Override
          public void beforeCall(io.github.jinganix.peashooter.trace.Span span) {
            throw new RuntimeException("tracer boom");
          }

          @Override
          public void afterCall(io.github.jinganix.peashooter.trace.Span span, Throwable e) {}

          @Override
          public String nextSpanId() {
            return storage.nextSpanId();
          }
        };
    TraceExecutor traceExecutor = new TraceExecutor(newSingleThreadExecutor(), throwing);
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(), new DefaultExecutorSelector(traceExecutor), throwing);
    executor.setTimeout(java.time.Duration.ofMillis(200));
    AtomicInteger calls = new AtomicInteger();

    // When tracer fails, the task must still run instead of timing out
    executor.executeSync("a", calls::incrementAndGet);

    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("should complete submitAsync future exceptionally on task failure")
  void shouldCompleteSubmitAsyncFutureExceptionallyOnTaskFailure() {
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    RuntimeException boom = new RuntimeException("task boom");

    java.util.concurrent.CompletableFuture<Void> future =
        executor.submitAsync(
            "a",
            () -> {
              throw boom;
            });

    assertThatThrownBy(future::join).hasCause(boom);
  }

  @Test
  @DisplayName("should surface rejection from submitAsync instead of swallowing it")
  void shouldCompleteSubmitAsyncFutureExceptionallyOnRejection() {
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException("rejected")).when(rejecting).execute(any());
    OrderedTraceExecutor executor = createExecutor(rejecting, new CaffeineTaskQueueProvider());

    // When the runner scheduling is rejected Then only the triggering submit fails visibly
    assertThatThrownBy(() -> executor.submitAsync("a", () -> {}))
        .isInstanceOf(RejectedExecutionException.class)
        .hasMessageContaining("rejected");
  }

  @Test
  @DisplayName("should propagate inline task Error from submitAsync after completing future")
  void shouldPropagateInlineTaskErrorFromSubmitAsyncAfterCompletingFuture() {
    // Given a custom selector routing async work inline plus a task failing with an Error
    // (the callback already failed the future, so the catch guarantees propagation instead
    // of silently losing the failure without a handle)
    AssertionError failure = new AssertionError("task boom");
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(), (queue, sync) -> DirectExecutor.INSTANCE, TRACER);

    // When submitAsync runs the runner inline Then the original Error propagates
    assertThatThrownBy(
            () ->
                executor.submitAsync(
                    "a",
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("should forward rejection to RejectionAware task on submitAsync rejection")
  void shouldForwardRejectionToRejectionAwareTaskOnSubmitAsyncDiscard() {
    // Given a rejecting executor and a RejectionAware task
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException("rejected")).when(rejecting).execute(any());
    OrderedTraceExecutor executor = createExecutor(rejecting, new CaffeineTaskQueueProvider());
    AtomicReference<Throwable> rejectedCause = new AtomicReference<>();
    CountDownLatch rejectedLatch = new CountDownLatch(1);
    Runnable rejectionAwareTask = new RunnableWithRejection(() -> {}, rejectedCause, rejectedLatch);

    // When submitAsync is rejected Then it surfaces to the submitter and the task is notified
    assertThatThrownBy(() -> executor.submitAsync("a", rejectionAwareTask))
        .isInstanceOf(RejectedExecutionException.class);

    // And the inner task still observes rejection
    awaitCountDown(rejectedLatch);
    assertThat(rejectedCause.get()).isInstanceOf(RejectedExecutionException.class);
  }

  static final class RunnableWithRejection
      implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
    private final Runnable delegate;
    private final AtomicReference<Throwable> causeRef;
    private final CountDownLatch latch;

    RunnableWithRejection(
        Runnable delegate, AtomicReference<Throwable> causeRef, CountDownLatch latch) {
      this.delegate = delegate;
      this.causeRef = causeRef;
      this.latch = latch;
    }

    @Override
    public void run() {
      delegate.run();
    }

    @Override
    public void rejected(Throwable cause) {
      causeRef.set(cause);
      latch.countDown();
    }
  }

  @Test
  @DisplayName("should surface executor Error on executeSync without wrapping")
  void shouldSurfaceExecutorErrorOnExecuteSyncWithoutWrapping() {
    // Given an executor that fails with an Error
    AssertionError failure = new AssertionError("executor boom");
    Executor failing = mock(Executor.class);
    doThrow(failure).when(failing).execute(any());
    OrderedTraceExecutor executor = createExecutor(failing, new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofSeconds(10));

    // When / Then the original Error surfaces, not a RuntimeException wrapper
    assertThatThrownBy(() -> executor.executeSync("a", () -> {}))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("executor boom");
  }

  @Test
  @DisplayName("should forward Error to RejectionAware task and surface it from submitAsync")
  void shouldForwardErrorToRejectionAwareTaskAndFailFutureWithError() {
    // Given a rejecting-with-Error executor and a RejectionAware task
    AssertionError failure = new AssertionError("executor boom");
    Executor failing = mock(Executor.class);
    doThrow(failure).when(failing).execute(any());
    OrderedTraceExecutor executor = createExecutor(failing, new CaffeineTaskQueueProvider());
    AtomicReference<Throwable> rejectedCause = new AtomicReference<>();
    CountDownLatch rejectedLatch = new CountDownLatch(1);
    Runnable rejectionAwareTask = new ThrowableAwareRunnable(rejectedCause, rejectedLatch);

    // When submitAsync is rejected with an Error Then it surfaces and the task observes it
    assertThatThrownBy(() -> executor.submitAsync("a", rejectionAwareTask)).isSameAs(failure);
    awaitCountDown(rejectedLatch);
    assertThat(rejectedCause.get()).isSameAs(failure);
  }

  static final class ThrowableAwareRunnable
      implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
    private final AtomicReference<Throwable> causeRef;
    private final CountDownLatch latch;

    ThrowableAwareRunnable(AtomicReference<Throwable> causeRef, CountDownLatch latch) {
      this.causeRef = causeRef;
      this.latch = latch;
    }

    @Override
    public void run() {}

    @Override
    public void rejected(Throwable cause) {
      causeRef.set(cause);
      latch.countDown();
    }
  }

  @Test
  @DisplayName("should complete submitAsync future exceptionally on task Error")
  void shouldCompleteSubmitAsyncFutureExceptionallyOnTaskError() {
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    AssertionError failure = new AssertionError("task boom");

    java.util.concurrent.CompletableFuture<Void> future =
        executor.submitAsync(
            "a",
            () -> {
              throw failure;
            });

    assertThatThrownBy(future::join).hasCause(failure);
  }

  @Test
  @DisplayName("should saturate huge Duration timeout instead of overflowing")
  void shouldSaturateHugeDurationTimeoutInsteadOfOverflowing() {
    // Given a Duration beyond nanos range (toNanos would throw ArithmeticException)
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());

    // When setting a huge Duration timeout
    executor.setTimeout(java.time.Duration.ofSeconds(Long.MAX_VALUE));

    // Then it saturates to MAX nanos, never negative, never throws
    java.time.Duration timeout = executor.getTimeout();
    assertThat(timeout.isNegative()).isFalse();
    assertThat(timeout.toNanos()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName("should throw TraceTimeoutException on sync timeout")
  void shouldThrowTraceTimeoutExceptionOnSyncTimeout() {
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofMillis(1));

    assertThatThrownBy(() -> executor.executeSync("a", () -> sleep(100)))
        .isInstanceOf(io.github.jinganix.peashooter.executor.TraceTimeoutException.class)
        .matches(t -> t.getCause() instanceof TimeoutException);
  }

  @Test
  @DisplayName("should include the key in the timeout message")
  void shouldIncludeTheKeyInTheTimeoutMessage() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofMillis(1));

    // When / Then microseconds of debugging saved per incident
    assertThatThrownBy(() -> executor.executeSync("my-key", () -> sleep(100)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("my-key")
        .matches(t -> t.getCause() instanceof TimeoutException);
  }

  @Test
  @DisplayName("should report configured and remaining waits in the timeout message")
  void shouldReportConfiguredAndRemainingWaitsInTheTimeoutMessage() {
    // Given a short sync timeout
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofMillis(1));

    // When the wait expires Then the message must show both the configured wait and the
    // remaining wait at timeout so nested multi-key timeouts (shared deadline, small
    // remaining) are not misread as having waited the full configured duration
    assertThatThrownBy(() -> executor.executeSync("my-key", () -> sleep(100)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("my-key")
        .hasMessageContaining("remaining");
  }

  @Test
  @DisplayName("should reject negative timeout")
  void shouldRejectNegativeTimeout() {
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());

    assertThatThrownBy(() -> executor.setTimeout(java.time.Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> executor.setTimeout(java.time.Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> executor.setTimeout((java.time.Duration) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should run multi-key sync with single key via List and Set")
  void shouldRunMultiKeySyncWithSingleKeyViaListAndSet() {
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    AtomicInteger calls = new AtomicInteger();

    // Collection overload with singleton List (covers List fast path)
    executor.executeSync(java.util.List.of("only"), calls::incrementAndGet);
    // Collection overload with singleton non-List (covers iterator fast path)
    executor.executeSync(java.util.Set.of("only"), calls::incrementAndGet);

    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("should round-trip timeout via Duration without truncation")
  void shouldRoundTripTimeoutViaDurationWithoutTruncation() {
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofMillis(1500));

    assertThat(executor.getTimeout()).isEqualTo(java.time.Duration.ofMillis(1500));
    assertThat(executor.getTimeout()).isEqualTo(java.time.Duration.ofMillis(1500));
  }

  @Test
  @DisplayName("should complete submitAsync future on task success")
  void shouldCompleteSubmitAsyncFutureOnTaskSuccess() throws Exception {
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();

    java.util.concurrent.CompletableFuture<Void> future =
        executor.submitAsync(
            "a",
            () -> {
              ran.set(true);
            });

    future.get(5, TimeUnit.SECONDS);
    assertThat(ran.get()).isTrue();
  }

  @Test
  @DisplayName("should propagate error throwables from supply without timing out")
  void shouldPropagateErrorThrowablesFromSupplyWithoutTimingOut() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofSeconds(10));
    Error error = new AssertionError("fail");

    // When / Then
    org.junit.jupiter.api.Assertions.assertTimeout(
        Duration.ofMillis(500),
        (ThrowingSupplier<Void>)
            () -> {
              assertThatThrownBy(
                      () ->
                          executor.supply(
                              "a",
                              () -> {
                                throw error;
                              }))
                  .isEqualTo(error);
              return null;
            });
  }

  @Test
  @DisplayName("should restore interrupt flag when sync call is interrupted")
  void shouldRestoreInterruptFlagWhenSyncCallIsInterrupted() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.executeAsync("a", () -> sleep(200));
    Thread.currentThread().interrupt();

    // When
    assertThatThrownBy(
            () ->
                executor.supply(
                    "a",
                    () -> {
                      sleep(100);
                      return 0L;
                    }))
        .isInstanceOf(RuntimeException.class);

    // Then
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    Thread.interrupted();
  }

  @Test
  @DisplayName("should wrap non-runtime throwables from supply in completion exception")
  void shouldWrapNonRuntimeThrowablesFromSupplyInCompletionException() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    Throwable throwable = new Throwable("checked");

    // When / Then the smuggled checked failure surfaces as CompletionException (not bare
    // RuntimeException), preserving the cause for inspection
    assertThatThrownBy(
            () ->
                executor.supply(
                    "a",
                    () -> {
                      sneakyThrow(throwable);
                      return null;
                    }))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(throwable);
  }

  @Test
  @DisplayName("should propagate error throwables from executeSync without timing out")
  void shouldPropagateErrorThrowablesFromExecuteSyncWithoutTimingOut() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(java.time.Duration.ofSeconds(10));
    Error error = new AssertionError("fail");

    // When / Then
    org.junit.jupiter.api.Assertions.assertTimeout(
        Duration.ofMillis(500),
        (ThrowingSupplier<Void>)
            () -> {
              assertThatThrownBy(
                      () ->
                          executor.executeSync(
                              "a",
                              () -> {
                                throw error;
                              }))
                  .isEqualTo(error);
              return null;
            });
  }

  @Nested
  @DisplayName("when executing asynchronously")
  class WhenExecutingAsynchronously {

    static class ExecutorArgumentsProvider implements ArgumentsProvider {

      @Override
      public Stream<? extends Arguments> provideArguments(
          ParameterDeclarations parameters, ExtensionContext context) {
        return EXECUTORS.executors().entrySet().stream()
            .map(
                x ->
                    Arguments.of(
                        x.getKey(), createExecutor(x.getValue(), new CaffeineTaskQueueProvider())));
      }
    }

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("should block caller only for direct executor when task sleeps")
    void shouldBlockCallerOnlyForDirectExecutorWhenTaskSleeps(
        String name, OrderedTraceExecutor executor) {
      // When
      long start = System.currentTimeMillis();
      executor.executeAsync("a", () -> sleep(100));

      // Then
      if (DIRECT_EXECUTOR.equals(name)) {
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(100);
      } else {
        assertThat(System.currentTimeMillis() - start).isLessThan(100);
      }
    }

    @Test
    @DisplayName("should create child span on reentrant same-key sync")
    void shouldCreateChildSpanOnReentrantSameKeySync() {
      // Given
      Tracer tracer = new DefaultTracer();
      Executor executor = EXECUTORS.executors().get(SINGLE_THREAD_EXECUTOR);
      TraceExecutor traceExecutor = new TraceExecutor(executor, tracer);
      OrderedTraceExecutor orderedExecutor =
          new OrderedTraceExecutor(
              new CaffeineTaskQueueProvider(), new DefaultExecutorSelector(traceExecutor), tracer);
      AtomicReference<io.github.jinganix.peashooter.trace.Span> outerSpan = new AtomicReference<>();
      AtomicReference<io.github.jinganix.peashooter.trace.Span> innerSpan = new AtomicReference<>();

      // When: nested executeSync on the same key runs inline via invokedBy
      orderedExecutor.executeSync(
          "a",
          () -> {
            outerSpan.set(tracer.getSpan());
            orderedExecutor.executeSync("a", () -> innerSpan.set(tracer.getSpan()));
          });

      // Then
      assertThat(outerSpan.get()).isNotNull();
      assertThat(innerSpan.get()).isNotNull().isNotSameAs(outerSpan.get());
    }

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("should not time out when nested sync runs on the same key")
    void shouldNotTimeOutWhenNestedSyncRunsOnTheSameKey(
        String name, OrderedTraceExecutor executor) {
      // Given
      long startMillis = System.currentTimeMillis();
      AtomicReference<Long> elapsed = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);

      // When
      executor.executeAsync(
          "a",
          () ->
              executor.executeSync(
                  "a",
                  () -> {
                    elapsed.set(System.currentTimeMillis() - startMillis);
                    latch.countDown();
                  }));
      awaitCountDown(latch);

      // Then
      assertThat(elapsed.get()).isLessThan(100);
    }
  }

  @Nested
  @DisplayName("when enforcing per-key ordering")
  class WhenEnforcingPerKeyOrdering {

    abstract static class SyncCallable {

      final OrderedTraceExecutor executor;

      SyncCallable(OrderedTraceExecutor executor) {
        this.executor = executor;
      }

      abstract Long call(String key, Supplier<Long> supplier);
    }

    static class CallableArgumentsProvider implements ArgumentsProvider {

      SyncCallable createExecuteSync(Executor executor, TaskQueueProvider taskQueueProvider) {
        OrderedTraceExecutor traceExecutor = createExecutor(executor, taskQueueProvider);
        return new SyncCallable(traceExecutor) {
          @Override
          Long call(String key, Supplier<Long> supplier) {
            this.executor.executeSync(key, supplier::get);
            return 0L;
          }
        };
      }

      SyncCallable createSupply(Executor executor, TaskQueueProvider taskQueueProvider) {
        OrderedTraceExecutor traceExecutor = createExecutor(executor, taskQueueProvider);
        return new SyncCallable(traceExecutor) {
          @Override
          Long call(String key, Supplier<Long> supplier) {
            return this.executor.supply(key, supplier);
          }
        };
      }

      @Override
      public Stream<? extends Arguments> provideArguments(
          ParameterDeclarations parameters, ExtensionContext context) {
        return EXECUTORS.executors().entrySet().stream()
            .flatMap(
                x ->
                    Stream.of(
                        Arguments.of(
                            x.getKey(),
                            "executeSync",
                            createExecuteSync(x.getValue(), new CaffeineTaskQueueProvider())),
                        Arguments.of(
                            x.getKey(),
                            "executeSync.lockable",
                            createExecuteSync(x.getValue(), unlockedQueues())),
                        Arguments.of(
                            x.getKey(),
                            "supply",
                            createSupply(x.getValue(), new CaffeineTaskQueueProvider())),
                        Arguments.of(
                            x.getKey(),
                            "supply.lockable",
                            createSupply(x.getValue(), unlockedQueues()))));
      }
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("should serialize work when the same key is used twice")
    void shouldSerializeWorkWhenTheSameKeyIsUsedTwice(
        String name, String mode, SyncCallable callable)
        throws ExecutionException, InterruptedException {
      // Given
      AtomicReference<Long> start1 = new AtomicReference<>();
      AtomicReference<Long> start2 = new AtomicReference<>();

      // When
      CompletableFuture.allOf(
              runAsync(
                  () ->
                      callable.call(
                          "a",
                          () -> {
                            start1.set(System.currentTimeMillis());
                            return sleep(100);
                          })),
              runAsync(
                  () ->
                      callable.call(
                          "a",
                          () -> {
                            start2.set(System.currentTimeMillis());
                            return sleep(100);
                          })))
          .get();

      // Then
      assertThat(Math.abs(start2.get() - start1.get())).isGreaterThanOrEqualTo(100);
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("should run concurrently when different keys are used")
    void shouldRunConcurrentlyWhenDifferentKeysAreUsed(
        String name, String mode, SyncCallable callable)
        throws ExecutionException, InterruptedException {
      // Given
      AtomicReference<Long> start1 = new AtomicReference<>();
      AtomicReference<Long> start2 = new AtomicReference<>();

      // When
      CompletableFuture.allOf(
              runAsync(
                  () ->
                      callable.call(
                          "a",
                          () -> {
                            start1.set(System.currentTimeMillis());
                            return sleep(100);
                          })),
              runAsync(
                  () ->
                      callable.call(
                          "b",
                          () -> {
                            start2.set(System.currentTimeMillis());
                            return sleep(100);
                          })))
          .get();

      // Then
      if (SINGLE_THREAD_EXECUTOR.equals(name)) {
        assertThat(Math.abs(start2.get() - start1.get())).isGreaterThanOrEqualTo(100);
      } else {
        assertThat(Math.abs(start2.get() - start1.get())).isLessThanOrEqualTo(100);
      }
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("should serialize nested work on the same key")
    void shouldSerializeNestedWorkOnTheSameKey(String name, String mode, SyncCallable callable) {
      // Given
      AtomicReference<Long> start = new AtomicReference<>();

      // When
      callable.call(
          "a",
          () -> {
            start.set(System.currentTimeMillis());
            sleep(100);
            return callable.call("a", () -> sleep(100));
          });

      // Then
      assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(200);
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("should serialize nested cross-key work from different threads")
    void shouldSerializeNestedCrossKeyWorkFromDifferentThreads(
        String name, String mode, SyncCallable callable) {
      // Given
      AtomicReference<Long> start = new AtomicReference<>();

      // When
      callable.call(
          "a",
          () -> {
            start.set(System.currentTimeMillis());
            sleep(100);
            executeSyncOnAnotherThread(
                new TraceRunnable(
                    TRACER,
                    () ->
                        callable.call(
                            "b",
                            () -> {
                              sleep(100);
                              executeSyncOnAnotherThread(
                                  new TraceRunnable(
                                      TRACER, () -> callable.call("a", () -> sleep(100))));
                              return 0L;
                            })));
            return 0L;
          });

      // Then
      assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(300);
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("should serialize nested work on different keys")
    void shouldSerializeNestedWorkOnDifferentKeys(String name, String mode, SyncCallable callable) {
      // Given
      AtomicReference<Long> start = new AtomicReference<>();

      // When
      callable.call(
          "a",
          () -> {
            start.set(System.currentTimeMillis());
            sleep(100);
            return callable.call("b", () -> sleep(100));
          });

      // Then
      assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(200);
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("should serialize nested mixed-key chains")
    void shouldSerializeNestedMixedKeyChains(String name, String mode, SyncCallable callable) {
      // Given
      AtomicReference<Long> start = new AtomicReference<>();

      // When
      callable.call(
          "a",
          () -> {
            start.set(System.currentTimeMillis());
            sleep(100);
            return callable.call(
                "b",
                () -> {
                  sleep(100);
                  return callable.call(
                      "a",
                      () -> {
                        sleep(100);
                        return callable.call("b", () -> sleep(100));
                      });
                });
          });

      // Then
      assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(400);
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("should propagate runtime errors from tasks")
    void shouldPropagateRuntimeErrorsFromTasks(String name, String mode, SyncCallable callable) {
      // Given
      RuntimeException ex = new RuntimeException("error");

      // When / Then
      assertThatThrownBy(
              () ->
                  callable.call(
                      "a",
                      () -> {
                        throw ex;
                      }))
          .isInstanceOf(RuntimeException.class)
          .matches(t -> t == ex);
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("should fail when the calling thread is interrupted")
    void shouldFailWhenTheCallingThreadIsInterrupted(
        String name, String mode, SyncCallable callable) {
      // Given
      createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider())
          .executeAsync("a", () -> sleep(200));
      Thread.currentThread().interrupt();

      // When / Then
      assertThatThrownBy(
              () ->
                  callable.call(
                      "a",
                      () -> {
                        sleep(100);
                        return 0L;
                      }))
          .isInstanceOf(RuntimeException.class);
    }

    private void executeSyncOnAnotherThread(Runnable runnable) {
      try {
        runAsync(runnable, newSingleThreadExecutor()).get();
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Nested
  @DisplayName("when locking multiple keys")
  class WhenLockingMultipleKeys {

    @Test
    @DisplayName("should not bypass queue for nested multi-key overlapping held key")
    void shouldNotBypassQueueForNestedMultiKeyOverlappingHeldKey() {
      // Given a thread holding "a" via single-key sync
      OrderedTraceExecutor executor =
          createExecutor(Executors.newFixedThreadPool(4), new CaffeineTaskQueueProvider());
      executor.setTimeout(Duration.ofMillis(500));

      // When it nests a multi-key call overlapping the held key Then the multi-key path must
      // not bypass the queue inline (which would skip sorted acquisition and break the
      // global order promise): it queues behind itself and times out instead of succeeding.
      // RED before the fix: nested multi-key ran inline via isReentrant and completed.
      assertThatThrownBy(
              () ->
                  executor.executeSync(
                      "a", () -> executor.executeSync(Arrays.asList("a", "b"), () -> {})))
          .isInstanceOf(TraceTimeoutException.class);
    }

    @Test
    @DisplayName("should not deadlock when two threads use opposite key orders")
    void shouldNotDeadlockWhenTwoThreadsUseOppositeKeyOrders() throws InterruptedException {
      // Given
      OrderedTraceExecutor executor =
          createExecutor(Executors.newFixedThreadPool(4), new CaffeineTaskQueueProvider());
      CountDownLatch done = new CountDownLatch(2);
      AtomicInteger completed = new AtomicInteger();
      Runnable work =
          () -> {
            completed.incrementAndGet();
            done.countDown();
          };

      // When
      org.junit.jupiter.api.Assertions.assertTimeout(
          Duration.ofSeconds(5),
          () -> {
            Thread thread1 = new Thread(() -> executor.executeSync(Arrays.asList("a", "b"), work));
            Thread thread2 = new Thread(() -> executor.executeSync(Arrays.asList("b", "a"), work));
            thread1.start();
            thread2.start();
            thread1.join();
            thread2.join();
          });

      // Then
      assertThat(completed.get()).isEqualTo(2);
      awaitCountDown(done);
    }
  }

  @Nested
  @DisplayName("when batching duplicate keys")
  class WhenBatchingDuplicateKeys {

    static class ExecutorArgumentsProvider implements ArgumentsProvider {

      @Override
      public Stream<? extends Arguments> provideArguments(
          ParameterDeclarations parameters, ExtensionContext context) {
        return EXECUTORS.executors().entrySet().stream()
            .map(
                x ->
                    Arguments.of(
                        x.getKey(), createExecutor(x.getValue(), new CaffeineTaskQueueProvider())));
      }
    }

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("should run once when executeSync receives duplicate keys")
    void shouldRunOnceWhenExecuteSyncReceivesDuplicateKeys(
        String name, OrderedTraceExecutor executor) {
      // Given
      Runnable runnable = mock(Runnable.class);

      // When
      executor.executeSync(Arrays.asList("a", "a"), runnable);

      // Then
      verify(runnable, times(1)).run();
    }

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("should run once when executeSync receives mixed duplicate keys")
    void shouldRunOnceWhenExecuteSyncReceivesMixedDuplicateKeys(
        String name, OrderedTraceExecutor executor) {
      // Given
      Runnable runnable = mock(Runnable.class);

      // When
      executor.executeSync(Arrays.asList("a", "b", "a", "b"), runnable);

      // Then
      verify(runnable, times(1)).run();
    }

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("should return value once when supply receives duplicate keys")
    void shouldReturnValueOnceWhenSupplyReceivesDuplicateKeys(
        String name, OrderedTraceExecutor executor) {
      // When / Then
      assertThat(executor.supply(Arrays.asList("a", "a"), () -> 1)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("should return value once when supply receives mixed duplicate keys")
    void shouldReturnValueOnceWhenSupplyReceivesMixedDuplicateKeys(
        String name, OrderedTraceExecutor executor) {
      // When / Then
      assertThat(executor.supply(Arrays.asList("a", "b", "a", "b"), () -> 1)).isEqualTo(1);
    }
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
    throw (E) throwable;
  }

  static class CustomChecked extends Exception {

    private static final long serialVersionUID = 1L;

    CustomChecked(String message) {
      super(message);
    }
  }

  @SuppressWarnings("unchecked")
  private static <R> R sneakyCall(io.github.jinganix.peashooter.ThrowingSupplier<R, ?> supplier) {
    try {
      return supplier.get();
    } catch (Throwable t) {
      sneakyThrow(t);
      return null;
    }
  }
}
