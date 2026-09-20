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

package io.github.jinganix.peashooter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jinganix.peashooter.trace.Span;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceCallback")
class TraceCallbackTest {

  @Test
  @DisplayName("should delegate exceptions to the exception callback")
  void shouldDelegateExceptionsToTheExceptionCallback() {
    // Given
    AtomicReference<Exception> observed = new AtomicReference<>();
    TraceCallback callback = spanRecorder(observed);
    RuntimeException failure = new RuntimeException("boom");
    Span span = new Span("trace", "span", null);

    // When
    callback.afterCall(span, (Throwable) failure);

    // Then
    assertThat(observed.get()).isEqualTo(failure);
  }

  @Test
  @DisplayName("should report null error to the exception callback when an error is thrown")
  void shouldReportNullErrorWhenAnErrorIsThrown() {
    // Given
    AtomicReference<Exception> observed = new AtomicReference<>();
    AtomicReference<Boolean> called = new AtomicReference<>(false);
    TraceCallback callback =
        new TraceCallback() {
          @Override
          public void beforeCall(Span span) {}

          @Override
          public void afterCall(Span span, Exception e) {
            called.set(true);
            observed.set(e);
          }
        };
    Span span = new Span("trace", "span", null);

    // When
    callback.afterCall(span, (Throwable) new OutOfMemoryError());

    // Then
    assertThat(called.get()).isTrue();
    assertThat(observed.get()).isNull();
  }

  private static TraceCallback spanRecorder(AtomicReference<Exception> observed) {
    return new TraceCallback() {
      @Override
      public void beforeCall(Span span) {}

      @Override
      public void afterCall(Span span, Exception e) {
        observed.set(e);
      }
    };
  }
}
