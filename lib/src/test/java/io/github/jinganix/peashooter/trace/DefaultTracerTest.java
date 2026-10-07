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

package io.github.jinganix.peashooter.trace;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import io.github.jinganix.peashooter.Tracer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultTracer")
class DefaultTracerTest {

  @Test
  @DisplayName("should not touch thread state on afterCall")
  void shouldNotTouchThreadStateOnAfterCall() {
    // Given a child span installed on the thread (afterCall is observation only,
    // TraceScope owns the single restore)
    DefaultTracer tracer = new DefaultTracer();
    Span parent = Span.child(tracer, null);
    tracer.setSpan(parent);
    Span child = Span.child(tracer, parent);
    tracer.setSpan(child);

    // When the outcome is reported, with success and with failure
    tracer.afterCall(child, null);
    assertThat(tracer.getSpan()).isSameAs(child);
    tracer.afterCall(child, new RuntimeException("boom"));

    // Then the thread state is untouched
    assertThat(tracer.getSpan()).isSameAs(child);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should ignore null span on afterCall without touching thread state")
  void shouldIgnoreNullSpanOnAfterCallWithoutTouchingThreadState() {
    // Given an installed span (afterCall must never touch thread state, even for null input)
    DefaultTracer tracer = new DefaultTracer();
    Span root = Span.child(tracer, null);
    tracer.setSpan(root);

    // When / Then a null span is ignored instead of restoring or throwing
    tracer.afterCall(null, new RuntimeException("boom"));
    assertThat(tracer.getSpan()).isSameAs(root);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should write thread state exactly once per scope")
  void shouldWriteThreadStateExactlyOncePerScope() {
    // Given a counting tracer (install + single restore = 2 writes; a restoring
    // afterCall would add a third write)
    java.util.concurrent.atomic.AtomicInteger writes =
        new java.util.concurrent.atomic.AtomicInteger();
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            writes.incrementAndGet();
            super.setSpan(span);
          }

          @Override
          public void clearSpan() {
            writes.incrementAndGet();
            super.clearSpan();
          }
        };
    Span previous = Span.child(tracer, null);
    tracer.setSpan(previous);
    writes.set(0);

    // When a scope runs to completion
    TraceScope.run(tracer, () -> Span.child(tracer, null), () -> {});

    // Then exactly one install and one restore happened, and the previous span is back
    assertThat(writes.get()).isEqualTo(2);
    assertThat(tracer.getSpan()).isSameAs(previous);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should clear span when setSpan receives null")
  void shouldClearSpanWhenSetSpanReceivesNull() {
    DefaultTracer tracer = new DefaultTracer();
    tracer.setSpan(Span.child(tracer, null));

    tracer.setSpan(null);

    assertThat(tracer.getSpan()).isNull();
  }

  @Test
  @DisplayName("should deliver Error distinctly to Throwable afterCall override")
  void shouldDeliverErrorDistinctlyToThrowableAfterCallOverride() {
    // Given a subclass observing the Throwable variant (the migration target)
    java.util.concurrent.atomic.AtomicReference<Throwable> observed =
        new java.util.concurrent.atomic.AtomicReference<>();
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void afterCall(Span span, Throwable e) {
            observed.set(e);
            super.afterCall(span, e);
          }
        };
    Span root = Span.child(tracer, null);
    tracer.setSpan(root);

    // When an Error is reported
    AssertionError failure = new AssertionError("boom");
    tracer.afterCall(root, failure);

    // Then the override observes the Error itself, not a null standing for success
    assertThat(observed.get()).isSameAs(failure);
  }

  @Test
  @DisplayName("should generate trace ids via nextTraceId")
  void shouldGenerateTraceIdsViaNextTraceId() {
    // Given
    DefaultTracer tracer = new DefaultTracer();

    // When / Then ids come from the single generator
    assertThat(tracer.nextTraceId()).matches("[0-9a-f]{32}");
  }

  @Test
  @DisplayName("should return null span when span was cleared")
  void shouldReturnNullSpanWhenSpanWasCleared() {
    // Given
    Tracer tracer = new DefaultTracer();
    tracer.clearSpan();

    // When / Then
    assertThat(tracer.getSpan()).isNull();
  }

  @Test
  @DisplayName("should return the span that was set")
  void shouldReturnTheSpanThatWasSet() {
    // Given
    Tracer tracer = new DefaultTracer();
    tracer.clearSpan();
    Span span = Span.child(new DefaultTracer(), null);

    // When
    tracer.setSpan(span);

    // Then
    assertThat(tracer.getSpan()).isEqualTo(span);
  }

  @Test
  @DisplayName("should isolate spans across tracer instances on the same thread")
  void shouldIsolateSpansAcrossTracerInstancesOnTheSameThread() {
    // Given two independent tracers sharing one thread (e.g. two executors)
    Tracer first = new DefaultTracer();
    Tracer second = new DefaultTracer();
    first.clearSpan();
    second.clearSpan();
    Span span = Span.child(first, null);

    // When
    first.setSpan(span);

    // Then the other instance must not observe it
    assertThat(second.getSpan()).isNull();
    assertThat(first.getSpan()).isEqualTo(span);
    first.clearSpan();
  }

  @Test
  @DisplayName("should generate unique ids when called from multiple threads")
  void shouldGenerateUniqueIdsWhenCalledFromMultipleThreads()
      throws InterruptedException, ExecutionException {
    // Given
    Tracer tracer = new DefaultTracer();
    List<Callable<List<String>>> callables =
        IntStream.range(0, 5)
            .mapToObj(
                (IntFunction<Callable<List<String>>>)
                    x ->
                        () -> {
                          List<String> values = new ArrayList<>(1000);
                          for (int i = 0; i < 1000; i++) {
                            values.add(tracer.nextTraceId());
                          }
                          return values;
                        })
            .collect(Collectors.toList());

    ExecutorService executorService = Executors.newFixedThreadPool(8);

    // When
    List<Future<List<String>>> futureList = executorService.invokeAll(callables);
    List<String> values = new ArrayList<>();
    for (Future<List<String>> future : futureList) {
      values.addAll(future.get());
    }

    // Then
    assertThat(values.stream().distinct().count()).isEqualTo(values.size());
  }
}
