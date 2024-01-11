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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

@DisplayName("TraceExecutor")
class TraceExecutorTest {

  Tracer tracer = new DefaultTracer();

  ExecutorService delegate = mock(ExecutorService.class);

  TraceExecutor traceExecutor = new TraceExecutor(delegate, tracer);

  @Nested
  @DisplayName("getTracer")
  class GetTracer {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then return")
      void thenReturn() {
        assertThat(traceExecutor.getTracer()).isEqualTo(tracer);
      }
    }
  }

  @Nested
  @DisplayName("getSpan")
  class GetSpan {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then return")
      void thenReturn() {
        assertThat(traceExecutor.getSpan()).isEqualTo(tracer.getSpan());
      }
    }
  }

  @Nested
  @DisplayName("execute")
  class Execute {

    @Nested
    @DisplayName("when called by Runnable")
    class WhenCalledByRunnable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Runnable runnable = () -> {};
        traceExecutor.execute(runnable);
        verify(delegate, times(1)).execute(isA(TraceRunnable.class));
      }
    }

    @Nested
    @DisplayName("when called by TraceRunnable")
    class WhenCalledByTraceRunnable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Runnable runnable = new TraceRunnable(tracer, () -> {});
        traceExecutor.execute(runnable);
        verify(delegate, times(1)).execute(runnable);
      }
    }
  }

  @Nested
  @DisplayName("shutdown")
  class Shutdown {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        traceExecutor.shutdown();
        verify(delegate, times(1)).shutdown();
      }
    }
  }

  @Nested
  @DisplayName("shutdownNow")
  class ShutdownNow {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        traceExecutor.shutdownNow();
        verify(delegate, times(1)).shutdownNow();
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
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        assertThat(traceExecutor.isShutdown()).isFalse();
        verify(delegate, times(1)).isShutdown();
      }
    }
  }

  @Nested
  @DisplayName("isTerminated")
  class IsTerminated {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        assertThat(traceExecutor.isTerminated()).isFalse();
        verify(delegate, times(1)).isTerminated();
      }
    }
  }

  @Nested
  @DisplayName("awaitTermination")
  class AwaitTermination {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() throws InterruptedException {
        assertThat(traceExecutor.awaitTermination(1, TimeUnit.MILLISECONDS)).isFalse();
        verify(delegate, times(1)).awaitTermination(1, TimeUnit.MILLISECONDS);
      }
    }
  }

  @Nested
  @DisplayName("submit(Callable<T> task)")
  class Submit1 {

    @Nested
    @DisplayName("when called by Callable")
    class WhenCalledByCallable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Callable<Integer> callable = () -> 1;
        traceExecutor.submit(callable);
        verify(delegate, times(1))
            .submit(argThat((ArgumentMatcher<Callable<Integer>>) t -> t instanceof TraceCallable));
      }
    }

    @Nested
    @DisplayName("when called by TraceCallable")
    class WhenCalledByTraceCallable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Callable<Integer> callable = new TraceCallable<>(tracer, () -> 1);
        traceExecutor.submit(callable);
        verify(delegate, times(1)).submit(callable);
      }
    }
  }

  @Nested
  @DisplayName("submit(Runnable task, T result)")
  class Submit2 {
    @Nested
    @DisplayName("when called by Runnable")
    class WhenCalledByRunnable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Runnable runnable = () -> {};
        traceExecutor.submit(runnable, 1);
        verify(delegate, times(1)).submit(isA(TraceRunnable.class), eq(1));
      }
    }

    @Nested
    @DisplayName("when called by TraceRunnable")
    class WhenCalledByTraceRunnable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Runnable runnable = new TraceRunnable(tracer, () -> {});
        traceExecutor.submit(runnable, 1);
        verify(delegate, times(1)).submit(runnable, 1);
      }
    }
  }

  @Nested
  @DisplayName("submit(Runnable task)")
  class Submit3 {
    @Nested
    @DisplayName("when called by Runnable")
    class WhenCalledByRunnable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Runnable runnable = () -> {};
        traceExecutor.submit(runnable);
        verify(delegate, times(1)).submit(isA(TraceRunnable.class));
      }
    }

    @Nested
    @DisplayName("when called by TraceRunnable")
    class WhenCalledByTraceRunnable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Runnable runnable = new TraceRunnable(tracer, () -> {});
        traceExecutor.submit(runnable);
        verify(delegate, times(1)).submit(runnable);
      }
    }
  }

  @Nested
  @DisplayName("invokeAll(Collection<? extends Callable<T>> tasks)")
  class InvokeAll1 {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() throws InterruptedException {
        traceExecutor.invokeAll(Collections.emptyList());
        verify(delegate, times(1)).invokeAll(Collections.emptyList());
      }
    }
  }

  @Nested
  @DisplayName("invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)")
  class InvokeAll2 {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() throws InterruptedException {
        traceExecutor.invokeAll(Collections.emptyList(), 1, TimeUnit.MILLISECONDS);
        verify(delegate, times(1)).invokeAll(Collections.emptyList(), 1, TimeUnit.MILLISECONDS);
      }
    }
  }

  @Nested
  @DisplayName("invokeAny(Collection<? extends Callable<T>> tasks)")
  class InvokeAny1 {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() throws InterruptedException, ExecutionException {
        traceExecutor.invokeAny(Collections.emptyList());
        verify(delegate, times(1)).invokeAny(Collections.emptyList());
      }
    }
  }

  @Nested
  @DisplayName("invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)")
  class InvokeAny2 {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled()
          throws InterruptedException, ExecutionException, TimeoutException {
        traceExecutor.invokeAny(Collections.emptyList(), 1, TimeUnit.MILLISECONDS);
        verify(delegate, times(1)).invokeAny(Collections.emptyList(), 1, TimeUnit.MILLISECONDS);
      }
    }
  }
}
