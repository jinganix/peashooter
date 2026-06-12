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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jinganix.peashooter.TaskQueueProvider;
import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.queue.LockableTaskQueueProvider;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import io.github.jinganix.peashooter.trace.TraceRunnable;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
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

  static OrderedTraceExecutor createExecutor(
      Executor executor, TaskQueueProvider taskQueueProvider) {
    TraceExecutor traceExecutor = new TraceExecutor(executor, TRACER);
    DefaultExecutorSelector selector = new DefaultExecutorSelector(traceExecutor);
    return new OrderedTraceExecutor(taskQueueProvider, selector, TRACER);
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
    @DisplayName("should reject empty key collection on executeSync")
    void shouldRejectEmptyKeyCollectionOnExecuteSync() {
      assertThatThrownBy(() -> executor.executeSync(Collections.emptyList(), () -> {}))
          .isInstanceOf(IllegalArgumentException.class)
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
  void shouldStillRunQueuedTaskAfterSyncCallerTimesOut() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(50, TimeUnit.MILLISECONDS);
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
    executor.setTimeout(10, TimeUnit.SECONDS);

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
    executor.setTimeout(10, TimeUnit.SECONDS);

    // When / Then
    assertThatThrownBy(() -> executor.supply("a", () -> "value"))
        .isInstanceOf(RejectedExecutionException.class)
        .hasMessageContaining("rejected");
  }

  @Test
  @DisplayName("should time out when sync work exceeds configured timeout")
  void shouldTimeOutWhenSyncWorkExceedsConfiguredTimeout() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(1, TimeUnit.MILLISECONDS);

    // When / Then
    assertThatThrownBy(() -> executor.executeSync("a", () -> sleep(100)))
        .isInstanceOf(RuntimeException.class)
        .matches(t -> t.getCause() instanceof TimeoutException);
  }

  @Test
  @DisplayName("should propagate error throwables from supply without timing out")
  void shouldPropagateErrorThrowablesFromSupplyWithoutTimingOut() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(10, TimeUnit.SECONDS);
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
  @DisplayName("should wrap non-runtime throwables from supply in runtime exception")
  void shouldWrapNonRuntimeThrowablesFromSupplyInRuntimeException() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    Throwable throwable = new Throwable("checked");

    // When / Then
    assertThatThrownBy(
            () ->
                executor.supply(
                    "a",
                    () -> {
                      sneakyThrow(throwable);
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class)
        .hasCause(throwable);
  }

  @Test
  @DisplayName("should propagate error throwables from executeSync without timing out")
  void shouldPropagateErrorThrowablesFromExecuteSyncWithoutTimingOut() {
    // Given
    OrderedTraceExecutor executor =
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
    executor.setTimeout(10, TimeUnit.SECONDS);
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
        return ExecutorForTests.executors().entrySet().stream()
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
      Executor executor = ExecutorForTests.executors().get(SINGLE_THREAD_EXECUTOR);
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
        return ExecutorForTests.executors().entrySet().stream()
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
                            createExecuteSync(x.getValue(), new LockableTaskQueueProvider())),
                        Arguments.of(
                            x.getKey(),
                            "supply",
                            createSupply(x.getValue(), new CaffeineTaskQueueProvider())),
                        Arguments.of(
                            x.getKey(),
                            "supply.lockable",
                            createSupply(x.getValue(), new LockableTaskQueueProvider()))));
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
        return ExecutorForTests.executors().entrySet().stream()
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
}
