package io.github.jinganix.peashooter.executor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DirectExecutor")
class DirectExecutorTest {

  @Nested
  @DisplayName("execute")
  class Execute {

    @Test
    @DisplayName("Given called -> should run task")
    void givenCalled() {
      // Given
      Runnable runnable = mock(Runnable.class);

      // When
      DirectExecutor.INSTANCE.execute(runnable);

      // Then
      verify(runnable, times(1)).run();
    }
  }
}
