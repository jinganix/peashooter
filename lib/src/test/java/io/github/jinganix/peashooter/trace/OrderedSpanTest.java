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

import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

@DisplayName("OrderedSpan")
class OrderedSpanTest {

  @Nested
  @DisplayName("constructor")
  class Constructor {

    @Nested
    @DisplayName("when concrete with trace id")
    class WhenConcreteWithTraceId {

      @Test
      @DisplayName("then get the trace id")
      void thenGetTheTraceId() {
        OrderedSpan span = new OrderedSpan("trace", null, "key", true);
        assertThat(span.getTraceId()).isEqualTo("trace");
      }
    }
  }

  @Nested
  @DisplayName("invokedBy")
  class InvokedBy {

    @Nested
    @DisplayName("when span is null")
    class WhenSpanIsNull {

      @Test
      @DisplayName("then return false")
      void thenReturnFalse() {
        assertThat(OrderedSpan.invokedBy(null, "")).isFalse();
      }
    }

    @Nested
    @DisplayName("when span is OrderedSpan")
    class WhenSpanIsOrderedSpan {

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

        @Override
        public String toString() {
          return "SpanKeyArg(" + key + ", " + sync + ")";
        }
      }

      static class SpanArgumentsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
          return Stream.of(
              Arguments.of(Lists.list(SpanArg.sync("foo")), SpanArg.sync("foo")),
              Arguments.of(Lists.list(SpanArg.async("foo")), SpanArg.sync("foo")),
              Arguments.of(
                  Lists.list(SpanArg.async("foo"), SpanArg.sync("foo")), SpanArg.sync("foo")),
              Arguments.of(
                  Lists.list(SpanArg.async("foo"), SpanArg.async("bar")), SpanArg.async("foo")),
              Arguments.of(
                  Lists.list(SpanArg.sync("foo"), SpanArg.async("bar")), SpanArg.async("foo")),
              Arguments.of(
                  Lists.list(SpanArg.async("foo"), SpanArg.sync("foo")), SpanArg.sync("foo")),
              Arguments.of(
                  Lists.list(SpanArg.async("foo"), SpanArg.sync("bar")), SpanArg.sync("foo")));
        }
      }

      @ParameterizedTest(name = "{0} => {1}")
      @ArgumentsSource(SpanArgumentsProvider.class)
      void thenRun(List<SpanArg> args, SpanArg expected) {
        OrderedSpan span = null;
        DefaultTracer tracer = new DefaultTracer();
        for (SpanArg arg : args) {
          span = new OrderedSpan(tracer, span, arg.key, arg.sync);
        }
        assertThat(OrderedSpan.invokedBy(span, expected.key)).isEqualTo(expected.sync);
      }
    }
  }
}
