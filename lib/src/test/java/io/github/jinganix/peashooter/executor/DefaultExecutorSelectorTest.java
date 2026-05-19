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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultTraceExecutorProvider")
class DefaultExecutorSelectorTest {

  TaskQueue queue = mock(TaskQueue.class);

  TraceRunnable task = mock(TraceRunnable.class);

  Tracer tracer = mock(Tracer.class);

  TraceExecutor traceExecutor = new TraceExecutor(mock(Executor.class), tracer);

  DefaultExecutorSelector provider = new DefaultExecutorSelector(traceExecutor);

  @Nested
  @DisplayName("getExecutor when is sync and span not null and queue is empty")
  class GetExecutorWhenIsSyncAndSpanNotNullAndQueueIsEmpty {

    @Test
    @DisplayName("Given is sync and span not null and queue empty -> should return DirectExecutor")
    void givenIsSyncAndSpanNotNullAndQueueEmpty() {
      // Given
      when(traceExecutor.getSpan()).thenReturn(mock(Span.class));
      when(queue.isEmpty()).thenReturn(true);

      // When / Then
      assertThat(provider.getExecutor(queue, task, true)).isEqualTo(DirectExecutor.INSTANCE);
    }
  }

  @Nested
  @DisplayName("getExecutor when sync is false")
  class GetExecutorWhenSyncIsFalse {

    @Test
    @DisplayName("Given sync is false -> should return traceExecutor")
    void givenSyncIsFalse() {
      // Given
      when(traceExecutor.getSpan()).thenReturn(mock(Span.class));
      when(queue.isEmpty()).thenReturn(true);

      // When / Then
      assertThat(provider.getExecutor(queue, task, false)).isEqualTo(traceExecutor);
    }
  }

  @Nested
  @DisplayName("getExecutor when span is null")
  class GetExecutorWhenSpanIsNull {

    @Test
    @DisplayName("Given span is null -> should return traceExecutor")
    void givenSpanIsNull() {
      // Given
      when(traceExecutor.getSpan()).thenReturn(null);
      when(queue.isEmpty()).thenReturn(true);

      // When / Then
      assertThat(provider.getExecutor(queue, task, true)).isEqualTo(traceExecutor);
    }
  }

  @Nested
  @DisplayName("getExecutor when queue is not empty")
  class GetExecutorWhenQueueIsNotEmpty {

    @Test
    @DisplayName("Given queue is not empty -> should return traceExecutor")
    void givenQueueIsNotEmpty() {
      // Given
      when(traceExecutor.getSpan()).thenReturn(mock(Span.class));
      when(queue.isEmpty()).thenReturn(false);

      // When / Then
      assertThat(provider.getExecutor(queue, task, true)).isEqualTo(traceExecutor);
    }
  }
}
