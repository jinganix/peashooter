/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
  @DisplayName("span")
  class SpanTest {

    @Nested
    @DisplayName("when span is not set")
    class WhenSpanIsNotSet {

      @Test
      @DisplayName("then return null")
      void thenReturnNull() {
        Tracer tracer = new DefaultTracer();
        tracer.clearSpan();
        assertThat(tracer.getSpan()).isNull();
      }
    }

    @Nested
    @DisplayName("when span is set")
    class WhenSpanIsSet {

      @Test
      @DisplayName("then return the span")
      void thenReturnTheSpan() {
        Tracer tracer = new DefaultTracer();
        tracer.clearSpan();
        Span span = new Span(new DefaultTracer(), null);
        tracer.setSpan(span);
        assertThat(tracer.getSpan()).isEqualTo(span);
      }
    }
  }

  @Nested
  @DisplayName("nextId")
  class NextId {

    @Nested
    @DisplayName("when called in multi threads")
    class WhenCalledInMultiThreads {

      @Test
      @DisplayName("then no duplicated")
      void thenNoDuplicated() throws InterruptedException, ExecutionException {
        Tracer tracer = new DefaultTracer();
        List<Callable<List<String>>> callables =
            IntStream.range(0, 5)
                .mapToObj(
                    (IntFunction<Callable<List<String>>>)
                        _ ->
                            () -> {
                              List<String> values = new ArrayList<>(1000);
                              for (int i = 0; i < 1000; i++) {
                                values.add(tracer.nextId());
                              }
                              return values;
                            })
                .collect(Collectors.toList());

        ExecutorService executorService = Executors.newFixedThreadPool(8);
        List<Future<List<String>>> futureList = executorService.invokeAll(callables);
        List<String> values = new ArrayList<>();
        for (Future<List<String>> future : futureList) {
          values.addAll(future.get());
        }
        assertThat(values.stream().distinct().count()).isEqualTo(values.size());
      }
    }
  }
}
