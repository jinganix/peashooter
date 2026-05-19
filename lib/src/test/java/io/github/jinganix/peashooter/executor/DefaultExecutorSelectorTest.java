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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("DefaultTraceExecutorProvider")
class DefaultExecutorSelectorTest {

  TaskQueue queue = mock(TaskQueue.class);

  TraceRunnable task = mock(TraceRunnable.class);

  Tracer tracer = mock(Tracer.class);

  TraceExecutor traceExecutor = new TraceExecutor(mock(Executor.class), tracer);

  DefaultExecutorSelector provider = new DefaultExecutorSelector(traceExecutor);

  @Nested
  @DisplayName("getExecutor")
  class GetExecutor {

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideGetExecutorScenarios")
    @DisplayName("should return correct executor based on conditions")
    void testGetExecutor(
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

    private static Stream<Arguments> provideGetExecutorScenarios() {
      Span mockSpan = mock(Span.class);
      return Stream.of(
          Arguments.of(
              "Given is sync and span not null and queue empty -> should return DirectExecutor",
              mockSpan,
              true,
              true,
              DirectExecutor.INSTANCE),
          Arguments.of(
              "Given sync is false -> should return traceExecutor", mockSpan, true, false, null),
          Arguments.of("Given span is null -> should return traceExecutor", null, true, true, null),
          Arguments.of(
              "Given queue is not empty -> should return traceExecutor",
              mockSpan,
              false,
              true,
              null));
    }
  }
}
