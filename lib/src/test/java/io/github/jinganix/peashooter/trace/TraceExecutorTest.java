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

package io.github.jinganix.peashooter.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.executor.TraceExecutor;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceExecutor")
class TraceExecutorTest {

  Tracer tracer = new DefaultTracer();

  Executor delegate = mock(Executor.class);

  TraceExecutor traceExecutor = new TraceExecutor(delegate, tracer);

  @Test
  @DisplayName("should expose the configured tracer")
  void shouldExposeTheConfiguredTracer() {
    // When / Then
    assertThat(traceExecutor.getTracer()).isEqualTo(tracer);
  }

  @Test
  @DisplayName("should wrap a plain runnable before delegating")
  void shouldWrapAPlainRunnableBeforeDelegating() {
    // Given
    Runnable runnable = () -> {};

    // When
    traceExecutor.execute(runnable);

    // Then
    verify(delegate, times(1)).execute(isA(TraceRunnable.class));
  }

  @Test
  @DisplayName("should preserve RejectionAware when wrapping a plain runnable")
  void shouldPreserveRejectionAwareWhenWrappingAPlainRunnable() {
    // Given a plain runnable that is also RejectionAware
    Runnable runnable =
        mock(
            Runnable.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(io.github.jinganix.peashooter.queue.RejectionAware.class));

    // When
    traceExecutor.execute(runnable);

    // Then the delegate must receive a RejectionAware wrapper
    org.mockito.ArgumentCaptor<Runnable> captor =
        org.mockito.ArgumentCaptor.forClass(Runnable.class);
    verify(delegate, times(1)).execute(captor.capture());
    assertThat(captor.getValue())
        .isInstanceOf(io.github.jinganix.peashooter.queue.RejectionAware.class);
  }

  @Test
  @DisplayName("should preserve rejection notification for custom TraceRunnable RejectionAware")
  void shouldPreserveDiscardNotificationForCustomTraceRunnableRejectionAware() {
    // Given a custom TraceRunnable that is also RejectionAware (passed through unwrapped,
    // still notifiable via its own RejectionAware interface)
    Runnable both =
        mock(
            TraceRunnable.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(io.github.jinganix.peashooter.queue.RejectionAware.class));
    traceExecutor.execute(both);

    // Then it must pass through as-is (still RejectionAware, no double wrap needed)
    org.mockito.ArgumentCaptor<Runnable> captor =
        org.mockito.ArgumentCaptor.forClass(Runnable.class);
    verify(delegate, times(1)).execute(captor.capture());
    assertThat(captor.getValue()).isSameAs(both);
    assertThat(captor.getValue())
        .isInstanceOf(io.github.jinganix.peashooter.queue.RejectionAware.class);
  }

  @Test
  @DisplayName("should forward rejection to RejectionAware delegate wrapped in TraceRunnable")
  void shouldForwardDiscardToRejectionAwareDelegateWrappedInTraceRunnable() {
    // Given a plain TraceRunnable whose delegate is RejectionAware (passed through
    // TraceExecutor unwrapped, then discarded by TaskQueue via dispatch on the wrapper)
    java.util.concurrent.atomic.AtomicReference<Throwable> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    class DelegateNoting implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        seen.set(cause);
      }
    }
    TraceRunnable wrapper = new TraceRunnable(tracer, new DelegateNoting());

    // When the queue discards the wrapper (dispatches on the wrapper itself)
    java.util.concurrent.RejectedExecutionException cause =
        new java.util.concurrent.RejectedExecutionException("boom");
    io.github.jinganix.peashooter.queue.RejectionAware.dispatch(wrapper, cause);

    // Then the delegate must observe the rejection instead of timing out
    assertThat(seen.get()).isSameAs(cause);
  }

  @Test
  @DisplayName("should delegate trace runnables without re-wrapping")
  void shouldDelegateTraceRunnablesWithoutReWrapping() {
    // Given
    Runnable runnable = new TraceRunnable(tracer, () -> {});

    // When
    traceExecutor.execute(runnable);

    // Then
    verify(delegate, times(1)).execute(runnable);
  }

  @Test
  @DisplayName("should forward rejection to RejectionAware delegate")
  void shouldForwardRejectionToRejectionAwareDelegate() {
    java.util.concurrent.atomic.AtomicReference<Throwable> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    class RejectionNoting implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        seen.set(cause);
      }
    }
    Runnable runnable = new RejectionNoting();
    traceExecutor.execute(runnable);
    org.mockito.ArgumentCaptor<Runnable> captor =
        org.mockito.ArgumentCaptor.forClass(Runnable.class);
    verify(delegate, times(1)).execute(captor.capture());
    RuntimeException cause = new RuntimeException("rejected");

    ((io.github.jinganix.peashooter.queue.RejectionAware) captor.getValue()).rejected(cause);

    assertThat(seen.get()).isSameAs(cause);
  }

  @Test
  @DisplayName("should forward Error to queue RejectionAware delegate")
  void shouldForwardErrorViaThrowableOverloadToQueueRejectionAwareDelegate() {
    java.util.concurrent.atomic.AtomicReference<Throwable> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    class ErrorNoting implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        seen.set(cause);
      }
    }
    Runnable runnable = new ErrorNoting();
    traceExecutor.execute(runnable);
    org.mockito.ArgumentCaptor<Runnable> captor =
        org.mockito.ArgumentCaptor.forClass(Runnable.class);
    verify(delegate, times(1)).execute(captor.capture());
    AssertionError failure = new AssertionError("executor boom");

    ((io.github.jinganix.peashooter.queue.RejectionAware) captor.getValue()).rejected(failure);

    assertThat(seen.get()).isSameAs(failure);
  }
}
