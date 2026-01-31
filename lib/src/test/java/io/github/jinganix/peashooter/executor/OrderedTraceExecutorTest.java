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

import static io.github.jinganix.peashooter.utils.TestUtils.sleep;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.MockedConstruction;

@DisplayName("OrderedTraceExecutor")
class OrderedTraceExecutorTest {

  static String DIRECT_EXECUTOR = "DirectExecutor";

  static String SINGLE_THREAD_EXECUTOR = "SingleThreadExecutor";

  static Tracer TRACER = new DefaultTracer();

  static OrderedTraceExecutor createExecutor(
      Executor executor, TaskQueueProvider taskQueueProvider) {
    TraceExecutor traceExecutor = new TraceExecutor(executor, TRACER);
    DefaultExecutorSelector selector = new DefaultExecutorSelector(traceExecutor);
    return new OrderedTraceExecutor(taskQueueProvider, selector, TRACER);
  }

  static class ExecutorArgumentsProvider implements ArgumentsProvider {

    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
      return ExecutorForTests.executors().entrySet().stream()
          .map(
              x ->
                  Arguments.of(
                      x.getKey(), createExecutor(x.getValue(), new CaffeineTaskQueueProvider())));
    }
  }

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
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
      return ExecutorForTests.executors().entrySet().stream()
          .map(
              x ->
                  Arrays.asList(
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
                          createSupply(x.getValue(), new LockableTaskQueueProvider()))))
          .flatMap(List::stream);
    }
  }

  @Nested
  @DisplayName("constructor")
  class Constructor {

    @Nested
    @DisplayName("when ExecutorService provided")
    class WhenExecutorServiceProvided {

      @Test
      @DisplayName("then context provider concreted")
      void thenContextProviderConcreted() {
        try (MockedConstruction<TraceExecutor> provider = mockConstruction(TraceExecutor.class)) {
          assertThatCode(() -> new OrderedTraceExecutor(mock(ExecutorService.class)))
              .doesNotThrowAnyException();
          assertThat(provider.constructed().size()).isEqualTo(1);
        }
      }
    }

    @Nested
    @DisplayName("when TraceExecutor provided")
    class WhenTraceExecutorProvided {

      @Test
      @DisplayName("then context provider concreted")
      void thenContextProviderConcreted() {
        try (MockedConstruction<TraceExecutor> provider = mockConstruction(TraceExecutor.class)) {
          assertThatCode(() -> new OrderedTraceExecutor(mock(TraceExecutor.class)))
              .doesNotThrowAnyException();
          assertThat(provider.constructed().size()).isEqualTo(0);
        }
      }
    }
  }

  @Nested
  @DisplayName("setTimeout")
  class SetTimeout {

    @Nested
    @DisplayName("when set timeout to 1ms")
    class WhenSetTimeoutTo1Ms {

      @Test
      @DisplayName("then sync execution throw exception")
      void thenSyncExecutionThrowException() {
        OrderedTraceExecutor executor =
            createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
        executor.setTimeout(1, TimeUnit.MILLISECONDS);
        assertThatThrownBy(() -> executor.executeSync("a", () -> sleep(100)))
            .isInstanceOf(RuntimeException.class)
            .matches(t -> t.getCause() instanceof TimeoutException);
      }
    }
  }

  @Nested
  @DisplayName("executeAsync")
  class ExecuteAsync {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then call task async")
      @ArgumentsSource(ExecutorArgumentsProvider.class)
      void thenCallDelegate(String name, OrderedTraceExecutor executor) {
        long start = System.currentTimeMillis();
        executor.executeAsync("a", () -> sleep(100));
        if (DIRECT_EXECUTOR.equals(name)) {
          assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(100);
        } else {
          assertThat(System.currentTimeMillis() - start).isLessThan(100);
        }
      }
    }

    @Nested
    @DisplayName("when call sync")
    class WhenCallSync {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then should not timeout")
      @ArgumentsSource(ExecutorArgumentsProvider.class)
      void thenShouldNotTimeout(String name, OrderedTraceExecutor executor)
          throws InterruptedException {
        long millis = System.currentTimeMillis();
        AtomicReference<Long> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        executor.executeAsync(
            "a",
            () ->
                executor.executeSync(
                    "a",
                    () -> {
                      ref.set(System.currentTimeMillis() - millis);
                      latch.countDown();
                    }));

        latch.await();
        Assertions.assertThat(ref.get()).isLessThan(100);
      }
    }
  }

  @Nested
  @DisplayName("runSynchronously")
  class RunSynchronously {

    @Nested
    @DisplayName("when execute two same keys")
    class WhenExecuteTwoSameKeys {

      @ParameterizedTest(name = "{0}.{1}")
      @DisplayName("then call both sequentially")
      @ArgumentsSource(CallableArgumentsProvider.class)
      void thenCallBothSequentially(String _name, String _key, SyncCallable callable)
          throws ExecutionException, InterruptedException {
        AtomicReference<Long> start1 = new AtomicReference<>();
        AtomicReference<Long> start2 = new AtomicReference<>();
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
        assertThat(Math.abs(start2.get() - start1.get())).isGreaterThanOrEqualTo(100);
      }
    }

    @Nested
    @DisplayName("when execute two different keys")
    class WhenExecuteTwoDifferentKeys {

      @ParameterizedTest(name = "{0}.{1}")
      @DisplayName("then call both concurrently")
      @ArgumentsSource(CallableArgumentsProvider.class)
      void thenCallBothConcurrently(String name, String _key, SyncCallable callable)
          throws ExecutionException, InterruptedException {
        AtomicReference<Long> start1 = new AtomicReference<>();
        AtomicReference<Long> start2 = new AtomicReference<>();
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
        if (SINGLE_THREAD_EXECUTOR.equals(name)) {
          assertThat(Math.abs(start2.get() - start1.get())).isGreaterThanOrEqualTo(100);
        } else {
          assertThat(Math.abs(start2.get() - start1.get())).isLessThanOrEqualTo(100);
        }
      }
    }

    @Nested
    @DisplayName("when execute nested same keys")
    class WhenExecuteNestedSameKeys {

      @ParameterizedTest(name = "{0}.{1}")
      @DisplayName("then call tasks sequentially")
      @ArgumentsSource(CallableArgumentsProvider.class)
      void thenCallTasksSequentially(String _name, String _key, SyncCallable callable) {
        AtomicReference<Long> start = new AtomicReference<>();
        callable.call(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              return callable.call("a", () -> sleep(100));
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(200);
      }
    }

    @Nested
    @DisplayName("when execute nested same keys in different threads")
    class WhenExecuteNestedSameKeysInDifferentThreads {

      void executeSync(Runnable runnable) {
        try {
          runAsync(runnable, newSingleThreadExecutor()).get();
        } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
        }
      }

      @ParameterizedTest(name = "{0}.{1}")
      @DisplayName("then call tasks sequentially")
      @ArgumentsSource(CallableArgumentsProvider.class)
      void thenCallTasksSequentially(String _name, String _key, SyncCallable callable) {
        AtomicReference<Long> start = new AtomicReference<>();
        callable.call(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              executeSync(
                  new TraceRunnable(
                      TRACER,
                      () ->
                          callable.call(
                              "b",
                              () -> {
                                sleep(100);
                                executeSync(
                                    new TraceRunnable(
                                        TRACER, () -> callable.call("a", () -> sleep(100))));
                                return 0L;
                              })));
              return 0L;
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(300);
      }
    }

    @Nested
    @DisplayName("when execute nested different keys")
    class WhenExecuteNestedDifferentKeys {

      @ParameterizedTest(name = "{0}.{1}")
      @DisplayName("then call tasks sequentially")
      @ArgumentsSource(CallableArgumentsProvider.class)
      void thenCallTasksSequentially(String _name, String _key, SyncCallable callable) {
        AtomicReference<Long> start = new AtomicReference<>();
        callable.call(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              return callable.call("b", () -> sleep(100));
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(200);
      }
    }

    @Nested
    @DisplayName("when execute nested mixed keys")
    class WhenExecuteNestedMixedKeys {

      @ParameterizedTest(name = "{0}.{1}")
      @DisplayName("then call tasks sequentially")
      @ArgumentsSource(CallableArgumentsProvider.class)
      void thenCallTasksSequentially(String _name, String _key, SyncCallable callable) {
        AtomicReference<Long> start = new AtomicReference<>();
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
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(400);
      }
    }

    @Nested
    @DisplayName("when task has runtime error")
    class WhenSupplierHasHasRuntimeError {

      @ParameterizedTest(name = "{0}.{1}")
      @DisplayName("then throw exception")
      @ArgumentsSource(CallableArgumentsProvider.class)
      void thenThrowException(String _name, String _key, SyncCallable callable) {
        RuntimeException ex = new RuntimeException("error");
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
    }

    @Nested
    @DisplayName("when execution is interrupted")
    class WhenExecutionIsInterrupted {

      @ParameterizedTest(name = "{0}.{1}")
      @DisplayName("then throw exception")
      @ArgumentsSource(CallableArgumentsProvider.class)
      void thenThrowException(String _name, String _key, SyncCallable callable) {
        createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider())
            .executeAsync("a", () -> sleep(200));
        Thread.currentThread().interrupt();
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
    }
  }

  @Nested
  @DisplayName("executeSyncKeys")
  class ExecuteSyncKeys {

    @Nested
    @DisplayName("when execute two same keys")
    class WhenSupplyTwoSameKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run")
      @ArgumentsSource(ExecutorArgumentsProvider.class)
      void thenRun(String _name, OrderedTraceExecutor executor) {
        Runnable runnable = mock(Runnable.class);
        executor.executeSync(Arrays.asList("a", "a"), runnable);
        verify(runnable, times(1)).run();
      }
    }

    @Nested
    @DisplayName("when execute mixed keys")
    class WhenSupplyMixedKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run")
      @ArgumentsSource(ExecutorArgumentsProvider.class)
      void thenRun(String _name, OrderedTraceExecutor executor) {
        Runnable runnable = mock(Runnable.class);
        executor.executeSync(Arrays.asList("a", "b", "a", "b"), runnable);
        verify(runnable, times(1)).run();
      }
    }
  }

  @Nested
  @DisplayName("supplyKeys")
  class SupplyKeys {

    @Nested
    @DisplayName("when supply two same keys")
    class WhenSupplyTwoSameKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run")
      @ArgumentsSource(ExecutorArgumentsProvider.class)
      void thenRun(String _name, OrderedTraceExecutor executor) {
        assertThat(executor.supply(Arrays.asList("a", "a"), () -> 1)).isEqualTo(1);
      }
    }

    @Nested
    @DisplayName("when supply mixed keys")
    class WhenSupplyMixedKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run")
      @ArgumentsSource(ExecutorArgumentsProvider.class)
      void thenRun(String _name, OrderedTraceExecutor executor) {
        assertThat(executor.supply(Arrays.asList("a", "b", "a", "b"), () -> 1)).isEqualTo(1);
      }
    }
  }
}
