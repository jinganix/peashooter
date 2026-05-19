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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Span")
class SpanTest {

  @Nested
  @DisplayName("constructor")
  class Constructor {

    @Test
    @DisplayName("Given concrete with trace id -> should get the trace id")
    void givenConcreteWithTraceId() {
      // When
      Span span = new Span("trace", null);

      // Then
      assertThat(span.getTraceId()).isEqualTo("trace");
    }

    @Test
    @DisplayName("Given parent is null -> should gen new trace id")
    void givenParentIsNull() {
      // When
      Span span = new Span(new DefaultTracer(), null);

      // Then
      assertThat(span.getParent()).isNull();
      assertThat(span.getTraceId()).matches("\\w+-\\w+-\\w+-\\w+-\\w+");
    }

    @Test
    @DisplayName("Given parent is not null -> should inherit the trace id")
    void givenParentIsNotNull() {
      // Given
      Span parent = new Span(new DefaultTracer(), null);

      // When
      Span span = new Span(new DefaultTracer(), parent);

      // Then
      assertThat(span.getParent()).isEqualTo(parent);
      assertThat(span.getTraceId()).isEqualTo(parent.getTraceId());
    }
  }

  @Nested
  @DisplayName("isRoot")
  class IsRoot {

    @Test
    @DisplayName("Given parent is null -> should return true")
    void givenParentIsNull() {
      // When
      Span span = new Span(new DefaultTracer(), null);

      // Then
      assertThat(span.isRoot()).isTrue();
    }

    @Test
    @DisplayName("Given parent is not null -> should return false")
    void givenParentIsNotNull() {
      // Given
      TraceIdGenerator traceIdGenerator = new DefaultTracer();
      Span parent = new Span(traceIdGenerator, null);

      // When
      Span span = new Span(traceIdGenerator, parent);

      // Then
      assertThat(span.isRoot()).isFalse();
    }
  }
}
