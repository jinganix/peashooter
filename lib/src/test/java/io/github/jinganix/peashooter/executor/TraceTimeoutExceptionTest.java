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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceTimeoutException")
class TraceTimeoutExceptionTest {

  @Test
  @DisplayName("should keep TimeoutException cause")
  void shouldKeepTimeoutExceptionCause() {
    TimeoutException cause = new TimeoutException("timed out");
    TraceTimeoutException ex =
        new TraceTimeoutException("slow key 'a'", cause, "a", new CompletableFuture<>());

    assertThat(ex.getCause()).isSameAs(cause);
    assertThat(ex.getTimeoutCause()).isSameAs(cause);
    assertThat(ex.getMessage()).contains("a");
  }

  @Test
  @DisplayName("should reject null cause")
  void shouldRejectNullCause() {
    assertThatThrownBy(
            () ->
                new TraceTimeoutException(
                    "slow", (TimeoutException) null, "a", new CompletableFuture<>()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("cause");
  }

  @Test
  @DisplayName("should expose the TimeoutException cause with its static type")
  void shouldExposeTimeoutCauseWithStaticType() {
    TimeoutException cause = new TimeoutException("timed out");
    TraceTimeoutException ex =
        new TraceTimeoutException("slow key 'a'", cause, "a", new CompletableFuture<>());

    assertThat(ex.getTimeoutCause()).isSameAs(cause);
  }

  @Test
  @DisplayName("should build standard timeout failure from wait details")
  void shouldBuildStandardTimeoutFailureFromWaitDetails() {
    TraceTimeoutException ex =
        TraceTimeoutException.forTimeout(
            "a", 1_000_000L, 0L, new TimeoutException("timed out"), new CompletableFuture<>());

    assertThat(ex.getMessage()).contains("a");
    assertThat(ex.getTimeoutCause()).isNotNull();
    assertThat(ex.getCause()).isSameAs(ex.getTimeoutCause());
  }

  @Test
  @DisplayName("should be catchable as public TraceWaitException base")
  void shouldBeCatchableAsPublicBase() {
    // Given timeout and interrupt failures
    TraceTimeoutException timeout =
        new TraceTimeoutException(
            "slow",
            new java.util.concurrent.TimeoutException("t"),
            "slow",
            new CompletableFuture<>());
    TraceInterruptedException interrupted =
        new TraceInterruptedException(
            "intr", new InterruptedException("i"), "intr", new CompletableFuture<>());

    // When caught as base Then both are handled together
    // And base is a public extensible abstract class (not sealed) with documented extension
    assertThat(java.lang.reflect.Modifier.isPublic(TraceWaitException.class.getModifiers()))
        .isTrue();
    assertThat(java.lang.reflect.Modifier.isAbstract(TraceWaitException.class.getModifiers()))
        .isTrue();
    assertThat(TraceWaitException.class.isSealed()).isFalse();
    int handled = 0;
    for (RuntimeException e : new RuntimeException[] {timeout, interrupted}) {
      try {
        throw e;
      } catch (TraceWaitException expected) {
        handled++;
      }
    }
    assertThat(handled).isEqualTo(2);
  }

  @Test
  @DisplayName("should stay extensible for custom wait failures")
  void shouldStayExtensibleForCustomWaitFailures() {
    TraceWaitException custom =
        new TraceWaitException(
            "custom", new TimeoutException("t"), "k", new CompletableFuture<>()) {};
    assertThat(custom.getKey()).isEqualTo("k");
    assertThat(custom.getFuture()).isNotNull();
    try {
      throw custom;
    } catch (TraceWaitException expected) {
      assertThat(expected).isSameAs(custom);
    }
  }

  @Test
  @DisplayName("should be final so built-in wait failures stay stable")
  void shouldBeFinalSoSealedBaseStaysExhaustive() {
    assertThat(java.lang.reflect.Modifier.isFinal(TraceTimeoutException.class.getModifiers()))
        .isTrue();
    assertThat(java.lang.reflect.Modifier.isFinal(TraceInterruptedException.class.getModifiers()))
        .isTrue();
  }

  @Test
  @DisplayName("should pin a stable serialVersionUID on every serializable wait failure type")
  void shouldPinStableSerialVersionUidOnEveryWaitFailureType() throws Exception {
    // Given the serializable wait-failure hierarchy (base plus both concrete failures)
    List<Class<?>> types =
        List.of(
            TraceWaitException.class, TraceTimeoutException.class, TraceInterruptedException.class);

    // When / Then every type declares its own private static final serialVersionUID of 1
    for (Class<?> type : types) {
      Field uid = type.getDeclaredField("serialVersionUID");
      assertThat(Modifier.isPrivate(uid.getModifiers())).isTrue();
      assertThat(Modifier.isStatic(uid.getModifiers())).isTrue();
      assertThat(Modifier.isFinal(uid.getModifiers())).isTrue();
      uid.setAccessible(true);
      assertThat(uid.getLong(null)).isEqualTo(1L);
    }
  }

  @Test
  @DisplayName("should fail fast through the public accessor when cause is foreign")
  void shouldFailFastWhenCauseIsForeign() {
    // Given an extensible wait failure whose cause is foreign: the built-in constructors enforce
    // the exact cause type, so the supported way to reach the narrowing guard is a subclass that
    // supplies a different {@link #getCause()}.
    ForeignCauseWait ex = new ForeignCauseWait();

    // When narrowing through the accessor Then fail with context
    assertThatThrownBy(ex::narrowToTimeout)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("a TimeoutException");
  }

  /**
   * Extensible {@link TraceWaitException} subclass whose {@link #getCause()} is foreign to the
   * narrowing witness. Exercises the guard through the documented extension point instead of
   * overwriting {@code Throwable.cause} with {@code sun.misc.Unsafe}.
   */
  static final class ForeignCauseWait extends TraceWaitException {

    private static final long serialVersionUID = 1L;

    ForeignCauseWait() {
      super("slow", new TimeoutException("t"), "slow", new CompletableFuture<>());
    }

    @Override
    public synchronized Throwable getCause() {
      return new IllegalArgumentException("foreign");
    }

    TimeoutException narrowToTimeout() {
      return expectedCause(TimeoutException.class, "a TimeoutException");
    }
  }
}
