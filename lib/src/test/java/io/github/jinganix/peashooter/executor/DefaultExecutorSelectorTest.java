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
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("DefaultExecutorSelector")
class DefaultExecutorSelectorTest {

  TaskQueue queue = mock(TaskQueue.class);

  Tracer tracer = mock(Tracer.class);

  TraceExecutor traceExecutor = new TraceExecutor(mock(Executor.class), tracer);

  DefaultExecutorSelector provider = new DefaultExecutorSelector(traceExecutor);

  @ParameterizedTest(name = "{0}")
  @MethodSource("executorSelectionScenarios")
  @DisplayName("should select executor based on span, queue, and sync flag")
  void shouldSelectExecutorBasedOnSpanQueueAndSyncFlag(
      String scenario, Span span, boolean queueEmpty, boolean sync, Executor expectedExecutor) {
    // Given
    when(tracer.getSpan()).thenReturn(span);
    when(queue.isIdle()).thenReturn(queueEmpty);

    // When
    Executor result = provider.getExecutor(queue, sync);

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

  @Test
  @DisplayName("should always route through trace executor when inline sync is disabled")
  void shouldAlwaysRouteThroughTraceExecutorWhenInlineSyncIsDisabled() {
    // Given an otherwise inline-eligible nested sync (span present, idle queue)
    when(tracer.getSpan()).thenReturn(mock(Span.class));
    when(queue.isIdle()).thenReturn(true);
    DefaultExecutorSelector disabled = new DefaultExecutorSelector(traceExecutor, false);

    // When / Then the fast path is off even though all inline conditions hold
    assertThat(disabled.getExecutor(queue, true)).isEqualTo(traceExecutor);
  }

  @Test
  @DisplayName("should fall back to trace executor when the idle hint throws")
  void shouldFallBackToTraceExecutorWhenTheIdleHintThrows() {
    // Given a tracer and queue whose hint reads fail (custom implementations may throw)
    when(tracer.getSpan()).thenThrow(new IllegalStateException("span boom"));
    when(queue.isIdle()).thenThrow(new IllegalStateException("idle boom"));

    // When / Then selection never throws: the racy hint degrades to the safe executor
    assertThat(provider.getExecutor(queue, true)).isEqualTo(traceExecutor);
  }

  @Test
  @DisplayName("should propagate Error from the idle hint instead of swallowing it")
  void shouldPropagateErrorFromTheIdleHintInsteadOfSwallowingIt() {
    // Given a fatal Error inside the hint read (e.g. AssertionError/OOM from a broken tracer)
    AssertionError fatal = new AssertionError("hint boom");
    when(tracer.getSpan()).thenThrow(fatal);
    when(queue.isIdle()).thenReturn(true);

    // When / Then the fatal error propagates instead of degrading to the safe executor
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.getExecutor(queue, true))
        .isSameAs(fatal);
  }

  @Test
  @DisplayName("should route idle-hint hits to direct executor without counting reentrant bypasses")
  void shouldRouteIdleHintHitsWithoutCountingReentrantBypasses() {
    // Given an inline-eligible hint (span present, idle queue, sync)
    when(tracer.getSpan()).thenReturn(mock(Span.class));
    when(queue.isIdle()).thenReturn(true);

    // When the hint fires Then it routes to the direct executor
    assertThat(provider.getExecutor(queue, true)).isEqualTo(DirectExecutor.INSTANCE);
    assertThat(provider.getExecutor(queue, true)).isEqualTo(DirectExecutor.INSTANCE);

    // And non-hint selections (async, busy queue, absent span, disabled flag) route to trace
    when(queue.isIdle()).thenReturn(false);
    assertThat(provider.getExecutor(queue, true)).isEqualTo(traceExecutor);
    when(queue.isIdle()).thenReturn(true);
    when(tracer.getSpan()).thenReturn(null);
    assertThat(provider.getExecutor(queue, true)).isEqualTo(traceExecutor);
    assertThat(provider.getExecutor(queue, false)).isEqualTo(traceExecutor);
    DefaultExecutorSelector disabled = new DefaultExecutorSelector(traceExecutor, false);
    when(tracer.getSpan()).thenReturn(mock(Span.class));
    assertThat(disabled.getExecutor(queue, true)).isEqualTo(traceExecutor);
  }

  @Test
  @DisplayName("should keep channel-2 reentrant bypass on the calling thread when hint is disabled")
  void shouldKeepChannel2ReentrantBypassOnCallingThreadWhenHintIsDisabled() {
    // Given an executor whose Channel 1 hint is disabled
    io.github.jinganix.peashooter.trace.DefaultTracer realTracer =
        new io.github.jinganix.peashooter.trace.DefaultTracer();
    TraceExecutor realTraceExecutor = new TraceExecutor(DirectExecutor.INSTANCE, realTracer);
    DefaultExecutorSelector disabled = new DefaultExecutorSelector(realTraceExecutor, false);
    io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider queues =
        new io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider();
    OrderedTraceExecutor executor = new OrderedTraceExecutor(queues, disabled, realTracer);
    Thread caller = Thread.currentThread();
    java.util.concurrent.atomic.AtomicReference<Thread> innermost =
        new java.util.concurrent.atomic.AtomicReference<>();
    int depth = 200;

    // When nesting same-key sync 200 deep Then every level runs inline on the calling
    // thread (deadlock-avoidance bypass, not the hint): no timeout, no thread hop,
    // and iterative chain walk keeps stack depth constant.
    java.util.function.IntConsumer[] recurse = new java.util.function.IntConsumer[1];
    recurse[0] =
        level -> {
          if (level == depth) {
            innermost.set(Thread.currentThread());
            return;
          }
          assertThat(Thread.currentThread()).isSameAs(caller);
          executor.executeSync("k", () -> recurse[0].accept(level + 1));
          assertThat(Thread.currentThread()).isSameAs(caller);
        };
    recurse[0].accept(0);

    assertThat(innermost.get()).isSameAs(caller);
  }

  @Test
  @DisplayName("should not expose an unused trace executor accessor")
  void shouldNotExposeUnusedTraceExecutorAccessor() {
    boolean hasAccessor =
        java.util.Arrays.stream(DefaultExecutorSelector.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("getTraceExecutor"));
    assertThat(hasAccessor).as("unused getTraceExecutor accessor must be gone").isFalse();
  }
}
