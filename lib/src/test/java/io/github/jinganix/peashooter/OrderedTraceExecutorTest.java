/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * https://github.com/jinganix/peashooter
 */

package io.github.jinganix.peashooter;

import static io.github.jinganix.peashooter.TestUtils.sleep;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

@DisplayName("OrderedTraceExecutor")
class OrderedTraceExecutorTest {

  static String SINGLE_THREAD_EXECUTOR = "SingleThreadExecutor";

  static OrderedTraceExecutor EXECUTOR = createExecutor(Executors.newFixedThreadPool(5));

  static OrderedTraceExecutor createExecutor(ExecutorService executorService) {
    Tracer tracer = new DefaultTracer();
    TraceExecutor traceExecutor = new TraceExecutor(executorService, tracer);
    DefaultTraceContextProvider supplier = new DefaultTraceContextProvider(traceExecutor);
    return new OrderedTraceExecutor(new DefaultTaskQueues(), supplier);
  }

  static class TestArgumentsProvider implements ArgumentsProvider {

    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
      return ExecutorForTests.executors().entrySet().stream()
          .map(x -> Arguments.of(x.getKey(), createExecutor(x.getValue())));
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
        OrderedTraceExecutor executor = createExecutor(Executors.newSingleThreadExecutor());
        executor.setTimeout(1, TimeUnit.MILLISECONDS);
        assertThatThrownBy(() -> executor.executeSync("a", () -> sleep(100)))
            .isInstanceOf(RuntimeException.class)
            .matches(t -> t.getCause() instanceof TimeoutException);
      }
    }
  }

  @Nested
  @DisplayName("isShutdown")
  class IsShutdown {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then return")
      void thenReturn() {
        TraceExecutor executor =
            new TraceExecutor(Executors.newSingleThreadExecutor(), new DefaultTracer());
        TaskQueues queues = mock(TaskQueues.class);
        assertThat(
                new OrderedTraceExecutor(queues, new DefaultTraceContextProvider(executor))
                    .isShutdown())
            .isFalse();
      }
    }
  }

  @Nested
  @DisplayName("remove")
  class Remove {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then removed")
      void thenRemoved() {
        TraceExecutor executor =
            new TraceExecutor(Executors.newSingleThreadExecutor(), new DefaultTracer());
        TaskQueues queues = mock(TaskQueues.class);
        new OrderedTraceExecutor(queues, new DefaultTraceContextProvider(executor)).remove("a");
        verify(queues, times(1)).remove("a");
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
      @DisplayName("then run task async")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenCallDelegate(String _name, OrderedTraceExecutor executor) {
        long start = System.currentTimeMillis();
        executor.executeAsync("a", () -> sleep(100));
        assertThat(System.currentTimeMillis() - start).isLessThan(100);
      }
    }
  }

  @Nested
  @DisplayName("ExecuteSync")
  class ExecuteSync {

    @Nested
    @DisplayName("when execute two same keys")
    class WhenExecuteTwoSameKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run both sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunBothSequentially(String _name, OrderedTraceExecutor executor)
          throws ExecutionException, InterruptedException {
        AtomicReference<Long> start1 = new AtomicReference<>();
        AtomicReference<Long> start2 = new AtomicReference<>();
        CompletableFuture.allOf(
                runAsync(
                    () ->
                        executor.executeSync(
                            "a",
                            () -> {
                              start1.set(System.currentTimeMillis());
                              sleep(100);
                            })),
                runAsync(
                    () ->
                        executor.executeSync(
                            "a",
                            () -> {
                              start2.set(System.currentTimeMillis());
                              sleep(100);
                            })))
            .get();
        assertThat(Math.abs(start2.get() - start1.get())).isGreaterThanOrEqualTo(100);
      }
    }

    @Nested
    @DisplayName("when execute two different keys")
    class WhenExecuteTwoDifferentKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run both concurrently")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunBothConcurrently(String name, OrderedTraceExecutor executor)
          throws ExecutionException, InterruptedException {
        AtomicReference<Long> start1 = new AtomicReference<>();
        AtomicReference<Long> start2 = new AtomicReference<>();
        CompletableFuture.allOf(
                runAsync(
                    () ->
                        executor.executeSync(
                            "a",
                            () -> {
                              start1.set(System.currentTimeMillis());
                              sleep(100);
                            })),
                runAsync(
                    () ->
                        executor.executeSync(
                            "b",
                            () -> {
                              start2.set(System.currentTimeMillis());
                              sleep(100);
                            })))
            .get();
        if (name.equals(SINGLE_THREAD_EXECUTOR)) {
          assertThat(Math.abs(start2.get() - start1.get())).isGreaterThanOrEqualTo(100);
        } else {
          assertThat(Math.abs(start2.get() - start1.get())).isLessThanOrEqualTo(100);
        }
      }
    }

    @Nested
    @DisplayName("when execute nested same keys")
    class WhenExecuteNestedSameKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run tasks sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunTasksSequentially(String _name, OrderedTraceExecutor executor) {
        AtomicReference<Long> start = new AtomicReference<>();
        executor.executeSync(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              executor.executeSync("a", () -> sleep(100));
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(200);
      }
    }

    @Nested
    @DisplayName("when execute nested same keys in different threads")
    class WhenExecuteNestedSameKeysInDifferentThreads {

      void executeSync(Runnable runnable) {
        try {
          runAsync(runnable, Executors.newSingleThreadExecutor()).get();
        } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
        }
      }

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run tasks sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunTasksSequentially(String _name, OrderedTraceExecutor executor) {
        AtomicReference<Long> start = new AtomicReference<>();
        executor.executeSync(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              executeSync(
                  new TraceRunnable(
                      executor.getTracer(),
                      () ->
                          executor.executeSync(
                              "b",
                              () -> {
                                sleep(100);
                                executeSync(
                                    new TraceRunnable(
                                        executor.getTracer(),
                                        () -> executor.executeSync("a", () -> sleep(100))));
                              })));
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(300);
      }
    }

    @Nested
    @DisplayName("when execute nested different keys")
    class WhenExecuteNestedDifferentKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run tasks sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunTasksSequentially(String _name, OrderedTraceExecutor executor) {
        AtomicReference<Long> start = new AtomicReference<>();
        executor.executeSync(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              executor.executeSync("b", () -> sleep(100));
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(200);
      }
    }

    @Nested
    @DisplayName("when execute nested mixed keys")
    class WhenExecuteNestedMixedKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run tasks sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunTasksSequentially(String _name, OrderedTraceExecutor executor) {
        AtomicReference<Long> start = new AtomicReference<>();
        executor.executeSync(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              executor.executeSync(
                  "b",
                  () -> {
                    sleep(100);
                    executor.executeSync(
                        "a",
                        () -> {
                          sleep(100);
                          executor.executeSync("b", () -> sleep(100));
                        });
                  });
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(400);
      }
    }

    @Nested
    @DisplayName("when supplier has runtime error")
    class WhenSupplierHasHasRuntimeError {

      @Test
      @DisplayName("then throw exception")
      void thenThrowException() {
        RuntimeException ex = new RuntimeException("error");
        assertThatThrownBy(
                () ->
                    EXECUTOR.executeSync(
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

      @Test
      @DisplayName("then throw exception")
      void thenThrowException() {
        EXECUTOR.executeAsync("a", () -> sleep(200));
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> EXECUTOR.executeSync("a", () -> {}))
            .isInstanceOf(RuntimeException.class);
      }
    }
  }

  @Nested
  @DisplayName("supply")
  class Supply {

    @Nested
    @DisplayName("when supply two same keys")
    class WhenSupplyTwoSameKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run both sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunBothSequentially(String _name, OrderedTraceExecutor executor)
          throws ExecutionException, InterruptedException {
        AtomicReference<Long> start1 = new AtomicReference<>();
        AtomicReference<Long> start2 = new AtomicReference<>();
        CompletableFuture.allOf(
                supplyAsync(
                    () ->
                        executor.supply(
                            "a",
                            () -> {
                              start1.set(System.currentTimeMillis());
                              return sleep(100);
                            })),
                supplyAsync(
                    () ->
                        executor.supply(
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
    @DisplayName("when supply two different keys")
    class WhenSupplyTwoDifferentKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run both concurrently")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunBothConcurrently(String name, OrderedTraceExecutor executor)
          throws ExecutionException, InterruptedException {
        AtomicReference<Long> start1 = new AtomicReference<>();
        AtomicReference<Long> start2 = new AtomicReference<>();
        CompletableFuture.allOf(
                supplyAsync(
                    () ->
                        executor.supply(
                            "a",
                            () -> {
                              start1.set(System.currentTimeMillis());
                              return sleep(100);
                            })),
                supplyAsync(
                    () ->
                        executor.supply(
                            "b",
                            () -> {
                              start2.set(System.currentTimeMillis());
                              return sleep(100);
                            })))
            .get();
        if (name.equals(SINGLE_THREAD_EXECUTOR)) {
          assertThat(Math.abs(start2.get() - start1.get())).isGreaterThanOrEqualTo(100);
        } else {
          assertThat(Math.abs(start2.get() - start1.get())).isLessThanOrEqualTo(100);
        }
      }
    }

    @Nested
    @DisplayName("when supply nested same keys")
    class WhenSupplyNestedSameKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run tasks sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunTasksSequentially(String _name, OrderedTraceExecutor executor) {
        AtomicReference<Long> start = new AtomicReference<>();
        executor.supply(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              return executor.supply("a", () -> sleep(100));
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(200);
      }
    }

    @Nested
    @DisplayName("when supply nested same keys in different threads")
    class WhenSupplyNestedSameKeysInDifferentThreads {

      <R> R supplySync(Callable<R> callable) {
        try {
          return supplyAsync(
                  () -> {
                    try {
                      return callable.call();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  },
                  Executors.newSingleThreadExecutor())
              .get();
        } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
        }
      }

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run tasks sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunTasksSequentially(String _name, OrderedTraceExecutor executor) {
        AtomicReference<Long> start = new AtomicReference<>();
        executor.supply(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              return supplySync(
                  new TraceCallable<>(
                      executor.getTracer(),
                      () ->
                          executor.supply(
                              "b",
                              () -> {
                                sleep(100);
                                return supplySync(
                                    new TraceCallable<>(
                                        executor.getTracer(),
                                        () -> executor.supply("a", () -> sleep(100))));
                              })));
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(300);
      }
    }

    @Nested
    @DisplayName("when supply nested different keys")
    class WhenSupplyNestedDifferentKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run tasks sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunTasksSequentially(String _name, OrderedTraceExecutor executor) {
        AtomicReference<Long> start = new AtomicReference<>();
        executor.supply(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              return executor.supply("b", () -> sleep(100));
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(200);
      }
    }

    @Nested
    @DisplayName("when supply nested mixed keys")
    class WhenSupplyNestedMixedKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run tasks sequentially")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenRunTasksSequentially(String _name, OrderedTraceExecutor executor) {
        AtomicReference<Long> start = new AtomicReference<>();
        executor.supply(
            "a",
            () -> {
              start.set(System.currentTimeMillis());
              sleep(100);
              return executor.supply(
                  "b",
                  () -> {
                    sleep(100);
                    return executor.supply(
                        "a",
                        () -> {
                          sleep(100);
                          return executor.supply("b", () -> sleep(100));
                        });
                  });
            });
        assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(400);
      }
    }

    @Nested
    @DisplayName("when supplier has runtime error")
    class WhenSupplierHasHasRuntimeError {

      @Test
      @DisplayName("then throw exception")
      void thenThrowException() {
        RuntimeException ex = new RuntimeException("error");
        assertThatThrownBy(
                () ->
                    EXECUTOR.supply(
                        "a",
                        () -> {
                          throw ex;
                        }))
            .isInstanceOf(RuntimeException.class)
            .matches((Predicate<Throwable>) throwable -> throwable == ex);
      }
    }

    @Nested
    @DisplayName("when execution is interrupted")
    class WhenExecutionIsInterrupted {

      @Test
      @DisplayName("then throw exception")
      void thenThrowException() {
        EXECUTOR.executeAsync("a", () -> sleep(200));
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> EXECUTOR.supply("a", () -> sleep(10)))
            .isInstanceOf(RuntimeException.class);
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
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenSupply(String _name, OrderedTraceExecutor executor) {
        assertThat(executor.supply(Arrays.asList("a", "a"), () -> 1)).isEqualTo(1);
      }
    }

    @Nested
    @DisplayName("when supply mixed keys")
    class WhenSupplyMixedKeys {

      @ParameterizedTest(name = "{0}")
      @DisplayName("then run")
      @ArgumentsSource(TestArgumentsProvider.class)
      void thenSupply(String _name, OrderedTraceExecutor executor) {
        assertThat(executor.supply(Arrays.asList("a", "b", "a", "b"), () -> 1)).isEqualTo(1);
      }
    }
  }
}
