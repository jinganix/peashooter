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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jinganix.peashooter.TraceIdGenerator;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("OrderedSpan")
class OrderedSpanTest {

  @Test
  @DisplayName("should keep an explicit trace id when provided")
  void shouldKeepAnExplicitTraceIdWhenProvided() {
    // When
    OrderedSpan span = new OrderedSpan((TraceIdGenerator) () -> "trace", null, "key", true);

    // Then
    assertThat(span.getTraceId()).isEqualTo("trace");
  }

  @Test
  @DisplayName("should return false when span is null")
  void shouldReturnFalseWhenSpanIsNull() {
    // When / Then
    assertThat(OrderedSpan.invokedBy(null, "")).isFalse();
  }

  static Stream<Arguments> invokedByScenarios() {
    return Stream.of(
        Arguments.of(
            "should treat as sync when only sync span matches key",
            Lists.list(SpanArg.sync("foo")),
            SpanArg.sync("foo")),
        Arguments.of(
            "should treat as sync when async parent has sync child for key",
            Lists.list(SpanArg.async("foo")),
            SpanArg.sync("foo")),
        Arguments.of(
            "should treat as sync when async then sync chain ends on key",
            Lists.list(SpanArg.async("foo"), SpanArg.sync("foo")),
            SpanArg.sync("foo")),
        Arguments.of(
            "should treat as async when async spans do not end on key",
            Lists.list(SpanArg.async("foo"), SpanArg.async("bar")),
            SpanArg.async("foo")),
        Arguments.of(
            "should treat as async when sync parent has async child for key",
            Lists.list(SpanArg.sync("foo"), SpanArg.async("bar")),
            SpanArg.async("foo")),
        Arguments.of(
            "should treat as sync when async then sync on same key",
            Lists.list(SpanArg.async("foo"), SpanArg.sync("foo")),
            SpanArg.sync("foo")),
        Arguments.of(
            "should treat as sync when async then sync on different keys",
            Lists.list(SpanArg.async("foo"), SpanArg.sync("bar")),
            SpanArg.sync("foo")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invokedByScenarios")
  @DisplayName("should classify invocation as sync or async from span chain")
  void shouldClassifyInvocationAsSyncOrAsyncFromSpanChain(
      String scenario, List<SpanArg> args, SpanArg expected) {
    // Given
    OrderedSpan span = null;
    DefaultTracer tracer = new DefaultTracer();
    for (SpanArg arg : args) {
      span = new OrderedSpan(tracer, span, arg.key, arg.sync);
    }

    // When / Then
    assertThat(OrderedSpan.invokedBy(span, expected.key)).isEqualTo(expected.sync);
  }

  static class SpanArg {
    String key;
    boolean sync;

    SpanArg(String key, boolean sync) {
      this.key = key;
      this.sync = sync;
    }

    static SpanArg sync(String key) {
      return new SpanArg(key, true);
    }

    static SpanArg async(String key) {
      return new SpanArg(key, false);
    }
  }
}
