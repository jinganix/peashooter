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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceCallable")
class TraceCallableTest {

  @Test
  @DisplayName("should complete without error when delegate succeeds")
  void shouldCompleteWithoutErrorWhenDelegateSucceeds() {
    // When
    TraceCallable<Integer> traceCallable = new TraceCallable<>(new DefaultTracer(), () -> 0);

    // Then
    assertThatCode(traceCallable::call).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("should restore parent span when delegate throws error")
  void shouldRestoreParentSpanWhenDelegateThrowsError() {
    // Given
    DefaultTracer tracer = new DefaultTracer();
    Span parent = Span.child(tracer, null);
    tracer.setSpan(parent);
    TraceCallable<Integer> traceCallable =
        new TraceCallable<>(
            tracer,
            () -> {
              throw new OutOfMemoryError();
            });

    // When / Then
    assertThatThrownBy(traceCallable::call).isInstanceOf(OutOfMemoryError.class);
    assertThat(tracer.getSpan()).isEqualTo(parent);
  }

  @Test
  @DisplayName("should suppress afterCall failure when delegate already failed")
  void shouldSuppressAfterCallFailureWhenDelegateAlreadyFailed() throws Exception {
    // Given a delegate that fails and an afterCall that also fails (parity with TraceRunnable)
    DefaultTracer tracer = new DefaultTracer();
    RuntimeException delegateFailure = new RuntimeException("delegate boom");
    RuntimeException afterFailure = new RuntimeException("afterCall boom");
    io.github.jinganix.peashooter.Tracer recording =
        new io.github.jinganix.peashooter.Tracer() {
          @Override
          public Span getSpan() {
            return tracer.getSpan();
          }

          @Override
          public void setSpan(Span span) {
            tracer.setSpan(span);
          }

          @Override
          public void clearSpan() {
            tracer.clearSpan();
          }

          @Override
          public String nextTraceId() {
            return tracer.nextTraceId();
          }

          @Override
          public void beforeCall(Span span) {}

          @Override
          public void afterCall(Span span, Throwable e) {
            throw afterFailure;
          }

          @Override
          public String nextSpanId() {
            return tracer.nextSpanId();
          }
        };
    TraceCallable<Integer> callable =
        new TraceCallable<>(
            recording,
            () -> {
              throw delegateFailure;
            });

    // When / Then delegate failure wins, afterCall failure is suppressed
    try {
      callable.call();
      assertThat(false).as("expected delegate failure").isTrue();
    } catch (RuntimeException e) {
      assertThat(e).isEqualTo(delegateFailure);
      assertThat(e.getSuppressed()).containsExactly(afterFailure);
    }
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should restore interrupt flag when delegate throws InterruptedException")
  void shouldRestoreInterruptFlagWhenDelegateThrowsInterruptedException() {
    // Given
    DefaultTracer tracer = new DefaultTracer();
    InterruptedException interrupted = new InterruptedException("sleep interrupted");
    TraceCallable<Integer> traceCallable =
        new TraceCallable<>(
            tracer,
            () -> {
              throw interrupted;
            });

    // When
    try {
      traceCallable.call();
    } catch (InterruptedException e) {
      // expected
    } catch (Exception e) {
      throw new AssertionError("expected InterruptedException", e);
    }

    // Then interrupt status must be restored (sleep-style contract)
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    // cleanup for other tests
    Thread.interrupted();
    assertThat(tracer.getSpan()).isNull();
  }

  @Test
  @DisplayName("should propagate delegate error when delegate fails")
  void shouldPropagateDelegateErrorWhenDelegateFails() {
    // Given
    RuntimeException exception = new RuntimeException();
    TraceCallable<Integer> traceCallable =
        new TraceCallable<>(
            new DefaultTracer(),
            () -> {
              throw exception;
            });

    // When / Then
    assertThatThrownBy(traceCallable::call).isEqualTo(exception);
  }

  @Test
  @DisplayName("should wrap smuggled direct throwable in CompletionException")
  void shouldPropagateSmuggledDirectThrowableWithoutWrapping() {
    // Given a delegate smuggling a direct Throwable past the Callable signature
    // (Callable declares throws Exception: only Exceptions propagate unwrapped)
    Throwable failure = new Throwable("smuggled");
    TraceCallable<Integer> traceCallable =
        new TraceCallable<>(
            new DefaultTracer(),
            () -> {
              sneakyThrow(failure);
              return null;
            });

    // When / Then the foreign checked failure surfaces wrapped, never masquerading as Exception
    assertThatThrownBy(traceCallable::call)
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(failure);
  }

  @Test
  @DisplayName("should rethrow afterCall failure when delegate succeeds")
  void shouldRethrowAfterCallFailureWhenDelegateSucceeds() throws Exception {
    DefaultTracer tracer = new DefaultTracer();
    RuntimeException afterFailure = new RuntimeException("afterCall boom");
    io.github.jinganix.peashooter.Tracer recording =
        new io.github.jinganix.peashooter.Tracer() {
          @Override
          public Span getSpan() {
            return tracer.getSpan();
          }

          @Override
          public void setSpan(Span span) {
            tracer.setSpan(span);
          }

          @Override
          public void clearSpan() {
            tracer.clearSpan();
          }

          @Override
          public String nextTraceId() {
            return tracer.nextTraceId();
          }

          @Override
          public void beforeCall(Span span) {}

          @Override
          public void afterCall(Span span, Throwable e) {
            throw afterFailure;
          }

          @Override
          public String nextSpanId() {
            return tracer.nextSpanId();
          }
        };

    assertThatThrownBy(new TraceCallable<>(recording, () -> 1)::call).isEqualTo(afterFailure);
    tracer.clearSpan();
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
    throw (E) throwable;
  }
}
