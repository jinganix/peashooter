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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.trace.Span;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SpanAccessor scope restore")
class SpanAccessorScopeRestoreTest {

  @Test
  @DisplayName("should restore previous span when install fails")
  void shouldRestorePreviousWhenInstallFails() {
    Span previous = Span.ofIds("a".repeat(32), "b".repeat(16), null);
    AtomicReference<Span> current = new AtomicReference<>(previous);
    RuntimeException installFailure = new RuntimeException("install boom");
    SpanAccessor accessor =
        new SpanAccessor() {
          @Override
          public Span getSpan() {
            return current.get();
          }

          @Override
          public void setSpan(Span span) {
            if (span != null && span != previous) {
              // Simulate corrupting thread state before failing (unknown state).
              current.set(null);
              throw installFailure;
            }
            current.set(span);
          }

          @Override
          public void clearSpan() {
            current.set(null);
          }
        };

    assertThatThrownBy(() -> accessor.scope(Span.ofIds("c".repeat(32), "d".repeat(16), null)))
        .isSameAs(installFailure);
    assertThat(current.get()).isSameAs(previous);
  }

  @Test
  @DisplayName("should restore previous span when install sneaky-throws checked")
  void shouldRestorePreviousWhenInstallSneakyThrowsChecked() {
    Span previous = Span.ofIds("a".repeat(32), "b".repeat(16), null);
    AtomicReference<Span> current = new AtomicReference<>(previous);
    java.io.IOException installFailure = new java.io.IOException("install boom");
    SpanAccessor accessor =
        new SpanAccessor() {
          @Override
          public Span getSpan() {
            return current.get();
          }

          @Override
          @SuppressWarnings("unchecked")
          public void setSpan(Span span) {
            if (span != null && span != previous) {
              current.set(null);
              SpanAccessorScopeRestoreTest.<RuntimeException>sneakyThrow(installFailure);
            }
            current.set(span);
          }

          @Override
          public void clearSpan() {
            current.set(null);
          }
        };

    assertThatThrownBy(() -> accessor.scope(Span.ofIds("c".repeat(32), "d".repeat(16), null)))
        .isSameAs(installFailure);
    assertThat(current.get()).isSameAs(previous);
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
    throw (E) e;
  }

  @Test
  @DisplayName("should expose unchecked close without checked Exception")
  void shouldExposeUncheckedClose() throws Exception {
    // New contract: SpanAccessor.scope return type must narrow close() to no checked exceptions.
    java.lang.reflect.Method scopeMethod = SpanAccessor.class.getMethod("scope", Span.class);
    Class<?> scopeType = scopeMethod.getReturnType();
    java.lang.reflect.Method close = scopeType.getMethod("close");
    assertThat(close.getExceptionTypes()).isEmpty();
  }
}
