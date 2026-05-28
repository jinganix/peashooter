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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.queue.TaskQueue;
import io.github.jinganix.peashooter.trace.Span;
import io.github.jinganix.peashooter.trace.TraceRunnable;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("DefaultExecutorSelector")
class DefaultExecutorSelectorTest {

  TaskQueue queue = mock(TaskQueue.class);

  TraceRunnable task = mock(TraceRunnable.class);

  Tracer tracer = mock(Tracer.class);

  TraceExecutor traceExecutor = new TraceExecutor(mock(Executor.class), tracer);

  DefaultExecutorSelector provider = new DefaultExecutorSelector(traceExecutor);

  @ParameterizedTest(name = "{0}")
  @MethodSource("executorSelectionScenarios")
  @DisplayName("should select executor based on span, queue, and sync flag")
  void shouldSelectExecutorBasedOnSpanQueueAndSyncFlag(
      String scenario, Span span, boolean queueEmpty, boolean sync, Executor expectedExecutor) {
    // Given
    when(traceExecutor.getSpan()).thenReturn(span);
    when(queue.isEmpty()).thenReturn(queueEmpty);

    // When
    Executor result = provider.getExecutor(queue, task, sync);

    // Then
    if (expectedExecutor == null) {
      assertThat(result).isEqualTo(traceExecutor);
    } else {
      assertThat(result).isEqualTo(expectedExecutor);
    }
  }

  private static Stream<Arguments> executorSelectionScenarios() {
    Span mockSpan = mock(Span.class);
    return Stream.of(
        Arguments.of(
            "should return direct executor when sync, span present, and queue empty",
            mockSpan,
            true,
            true,
            DirectExecutor.INSTANCE),
        Arguments.of("should return trace executor when not sync", mockSpan, true, false, null),
        Arguments.of("should return trace executor when span is null", null, true, true, null),
        Arguments.of(
            "should return trace executor when queue is not empty", mockSpan, false, true, null));
  }
}
