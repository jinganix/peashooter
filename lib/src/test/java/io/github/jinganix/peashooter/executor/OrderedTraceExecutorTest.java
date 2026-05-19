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
import org.junit.jupiter.params.support.ParameterDeclarations;
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
    public Stream<? extends Arguments> provideArguments(
        ParameterDeclarations parameters, ExtensionContext context) {
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
    public Stream<? extends Arguments> provideArguments(
        ParameterDeclarations parameters, ExtensionContext context) {
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

    @Test
    @DisplayName("Given ExecutorService provided -> should construct TraceExecutor")
    void givenExecutorServiceProvided() {
      // When / Then
      try (MockedConstruction<TraceExecutor> provider = mockConstruction(TraceExecutor.class)) {
        assertThatCode(() -> new OrderedTraceExecutor(mock(ExecutorService.class)))
            .doesNotThrowAnyException();
        assertThat(provider.constructed().size()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("Given TraceExecutor provided -> should not construct new TraceExecutor")
    void givenTraceExecutorProvided() {
      // When / Then
      try (MockedConstruction<TraceExecutor> provider = mockConstruction(TraceExecutor.class)) {
        assertThatCode(() -> new OrderedTraceExecutor(mock(TraceExecutor.class)))
            .doesNotThrowAnyException();
        assertThat(provider.constructed().size()).isEqualTo(0);
      }
    }
  }

  @Nested
  @DisplayName("setTimeout")
  class SetTimeout {

    @Test
    @DisplayName("Given timeout set to 1ms -> should throw TimeoutException")
    void givenTimeoutSetTo1Ms() {
      // Given
      OrderedTraceExecutor executor =
          createExecutor(newSingleThreadExecutor(), new CaffeineTaskQueueProvider());
      executor.setTimeout(1, TimeUnit.MILLISECONDS);

      // When / Then
      assertThatThrownBy(() -> executor.executeSync("a", () -> sleep(100)))
          .isInstanceOf(RuntimeException.class)
          .matches(t -> t.getCause() instanceof TimeoutException);
    }
  }

  @Nested
  @DisplayName("executeAsync")
  class ExecuteAsync {

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("Given called -> should call task async")
    void givenCalled(String name, OrderedTraceExecutor executor) {
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

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("Given call sync nested -> should not timeout")
    void givenCallSyncNested(String name, OrderedTraceExecutor executor)
        throws InterruptedException {
      // Given
      long millis = System.currentTimeMillis();
      AtomicReference<Long> ref = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);

      // When
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

      // Then
      Assertions.assertThat(ref.get()).isLessThan(100);
    }
  }

  @Nested
  @DisplayName("runSynchronously")
  class RunSynchronously {

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("Given execute two same keys -> should call both sequentially")
    void givenExecuteTwoSameKeys(String _name, String _key, SyncCallable callable)
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
    @DisplayName("Given execute two different keys -> should call both concurrently")
    void givenExecuteTwoDifferentKeys(String name, String _key, SyncCallable callable)
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
    @DisplayName("Given execute nested same keys -> should call tasks sequentially")
    void givenExecuteNestedSameKeys(String _name, String _key, SyncCallable callable) {
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

    void executeSync(Runnable runnable) {
      try {
        runAsync(runnable, newSingleThreadExecutor()).get();
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
      }
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName(
        "Given execute nested same keys in different threads -> should call tasks sequentially")
    void givenExecuteNestedSameKeysInDifferentThreads(
        String _name, String _key, SyncCallable callable) {
      // Given
      AtomicReference<Long> start = new AtomicReference<>();

      // When
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

      // Then
      assertThat(System.currentTimeMillis() - start.get()).isGreaterThanOrEqualTo(300);
    }

    @ParameterizedTest(name = "{0}.{1}")
    @ArgumentsSource(CallableArgumentsProvider.class)
    @DisplayName("Given execute nested different keys -> should call tasks sequentially")
    void givenExecuteNestedDifferentKeys(String _name, String _key, SyncCallable callable) {
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
    @DisplayName("Given execute nested mixed keys -> should call tasks sequentially")
    void givenExecuteNestedMixedKeys(String _name, String _key, SyncCallable callable) {
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
    @DisplayName("Given task has runtime error -> should throw exception")
    void givenTaskHasRuntimeError(String _name, String _key, SyncCallable callable) {
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
    @DisplayName("Given execution is interrupted -> should throw exception")
    void givenExecutionIsInterrupted(String _name, String _key, SyncCallable callable) {
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
  }

  @Nested
  @DisplayName("executeSync")
  class ExecuteSync {

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("Given execute two same keys -> should run")
    void givenExecuteTwoSameKeys(String _name, OrderedTraceExecutor executor) {
      // Given
      Runnable runnable = mock(Runnable.class);

      // When
      executor.executeSync(Arrays.asList("a", "a"), runnable);

      // Then
      verify(runnable, times(1)).run();
    }

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("Given execute mixed keys -> should run")
    void givenExecuteMixedKeys(String _name, OrderedTraceExecutor executor) {
      // Given
      Runnable runnable = mock(Runnable.class);

      // When
      executor.executeSync(Arrays.asList("a", "b", "a", "b"), runnable);

      // Then
      verify(runnable, times(1)).run();
    }
  }

  @Nested
  @DisplayName("supply")
  class Supply {

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("Given supply two same keys -> should run")
    void givenSupplyTwoSameKeys(String _name, OrderedTraceExecutor executor) {
      // When / Then
      assertThat(executor.supply(Arrays.asList("a", "a"), () -> 1)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(ExecutorArgumentsProvider.class)
    @DisplayName("Given supply mixed keys -> should run")
    void givenSupplyMixedKeys(String _name, OrderedTraceExecutor executor) {
      // When / Then
      assertThat(executor.supply(Arrays.asList("a", "b", "a", "b"), () -> 1)).isEqualTo(1);
    }
  }
}
