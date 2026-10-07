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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceInterruptedException")
class TraceInterruptedExceptionTest {

  @Test
  @DisplayName("should keep InterruptedException cause with its static type")
  void shouldKeepInterruptedCause() {
    InterruptedException cause = new InterruptedException("interrupted");
    TraceInterruptedException ex =
        new TraceInterruptedException("waiting for key 'a'", cause, "a", new CompletableFuture<>());

    assertThat(ex.getCause()).isSameAs(cause);
    assertThat(ex.getInterruptCause()).isSameAs(cause);
    assertThat(ex.getMessage()).contains("a");
  }

  @Test
  @DisplayName("should reject null cause")
  void shouldRejectNullCause() {
    assertThatThrownBy(
            () ->
                new TraceInterruptedException("interrupted", null, "a", new CompletableFuture<>()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("cause");
  }

  @Test
  @DisplayName("should build standard interruption failure from wait details")
  void shouldBuildStandardInterruptionFailureFromWaitDetails() {
    TraceInterruptedException ex =
        TraceInterruptedException.forInterrupt(
            new InterruptedException("waiting"), "a", new CompletableFuture<>());

    assertThat(ex.getMessage()).contains("a");
    assertThat(ex.getInterruptCause()).isNotNull();
    assertThat(ex.getCause()).isSameAs(ex.getInterruptCause());
  }

  @Test
  @DisplayName("should fail fast through the public accessor when cause is foreign")
  void shouldFailFastWhenCauseIsForeign() {
    // Given an extensible wait failure whose cause is foreign: the built-in constructors enforce
    // the exact cause type, so the supported way to reach the narrowing guard is a subclass that
    // supplies a different {@link #getCause()}.
    ForeignCauseWait ex = new ForeignCauseWait();

    // When narrowing through the accessor Then fail with context
    assertThatThrownBy(ex::narrowToInterrupt)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("an InterruptedException");
  }

  /**
   * Extensible {@link TraceWaitException} subclass whose {@link #getCause()} is foreign to the
   * narrowing witness. Exercises the guard through the documented extension point instead of
   * overwriting {@code Throwable.cause} with {@code sun.misc.Unsafe}.
   */
  static final class ForeignCauseWait extends TraceWaitException {

    private static final long serialVersionUID = 1L;

    ForeignCauseWait() {
      super("waiting for key 'a'", new InterruptedException("i"), "a", new CompletableFuture<>());
    }

    @Override
    public synchronized Throwable getCause() {
      return new IllegalArgumentException("foreign");
    }

    InterruptedException narrowToInterrupt() {
      return expectedCause(InterruptedException.class, "an InterruptedException");
    }
  }
}
