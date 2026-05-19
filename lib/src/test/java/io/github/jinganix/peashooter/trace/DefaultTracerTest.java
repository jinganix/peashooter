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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultTracer")
class DefaultTracerTest {

  @Nested
  @DisplayName("span when not set")
  class SpanWhenNotSet {

    @Test
    @DisplayName("Given span is not set -> should return null")
    void givenSpanIsNotSet() {
      // Given
      Tracer tracer = new DefaultTracer();
      tracer.clearSpan();

      // When / Then
      assertThat(tracer.getSpan()).isNull();
    }
  }

  @Nested
  @DisplayName("span when set")
  class SpanWhenSet {

    @Test
    @DisplayName("Given span is set -> should return the span")
    void givenSpanIsSet() {
      // Given
      Tracer tracer = new DefaultTracer();
      tracer.clearSpan();
      Span span = new Span(new DefaultTracer(), null);

      // When
      tracer.setSpan(span);

      // Then
      assertThat(tracer.getSpan()).isEqualTo(span);
    }
  }

  @Nested
  @DisplayName("nextId when called in multi threads")
  class NextIdWhenCalledInMultiThreads {

    @Test
    @DisplayName("Given called in multi threads -> should have no duplicated")
    void givenCalledInMultiThreads() throws InterruptedException, ExecutionException {
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
                              values.add(tracer.nextId());
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
}
