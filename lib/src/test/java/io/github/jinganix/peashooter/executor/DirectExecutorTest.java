package io.github.jinganix.peashooter.executor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DirectExecutor")
class DirectExecutorTest {

  @Test
  @DisplayName("should run the task on the calling thread when execute is invoked")
  void shouldRunTheTaskOnTheCallingThreadWhenExecuteIsInvoked() {
    // Given
    Runnable runnable = mock(Runnable.class);

    // When
    DirectExecutor.INSTANCE.execute(runnable);

    // Then
    verify(runnable, times(1)).run();
  }
}
