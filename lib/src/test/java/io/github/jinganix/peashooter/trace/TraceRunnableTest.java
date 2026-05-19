package io.github.jinganix.peashooter.trace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TraceRunnable")
class TraceRunnableTest {

  @Nested
  @DisplayName("run")
  class Run {

    @Test
    @DisplayName("Given delegate has no error -> should not throw exception")
    void givenDelegateHasNoError() {
      // When
      TraceRunnable traceRunnable = new TraceRunnable(new DefaultTracer(), () -> {});

      // Then
      assertThatCode(traceRunnable::run).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Given delegate has error -> should throw exception")
    void givenDelegateHasError() {
      // Given
      RuntimeException exception = new RuntimeException();
      TraceRunnable traceRunnable =
          new TraceRunnable(
              new DefaultTracer(),
              () -> {
                throw exception;
              });

      // When / Then
      assertThatThrownBy(traceRunnable::run).isEqualTo(exception);
    }
  }
}
