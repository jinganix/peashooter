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
import org.junit.jupiter.api.Test;

@DisplayName("Span")
class SpanTest {

  @Test
  @DisplayName("should keep an explicit trace id when provided")
  void shouldKeepAnExplicitTraceIdWhenProvided() {
    // When
    Span span = new Span("trace", TraceIds.nextSpanId(), null);

    // Then
    assertThat(span.getTraceId()).isEqualTo("trace");
  }

  @Test
  @DisplayName("should generate a trace id when parent is null")
  void shouldGenerateATraceIdWhenParentIsNull() {
    // When
    Span span = new Span(new DefaultTracer(), null);

    // Then
    assertThat(span.getParent()).isNull();
    assertThat(span.getTraceId()).matches("[0-9a-f]{32}");
    assertThat(span.getSpanId()).matches("[0-9a-f]{16}");
  }

  @Test
  @DisplayName("should inherit trace id from parent when parent is set")
  void shouldInheritTraceIdFromParentWhenParentIsSet() {
    // Given
    Span parent = new Span(new DefaultTracer(), null);

    // When
    Span span = new Span(new DefaultTracer(), parent);

    // Then
    assertThat(span.getParent()).isEqualTo(parent);
    assertThat(span.getTraceId()).isEqualTo(parent.getTraceId());
    assertThat(span.getSpanId()).isNotEqualTo(parent.getSpanId());
  }

  @Test
  @DisplayName("should be root when parent is null")
  void shouldBeRootWhenParentIsNull() {
    // When
    Span span = new Span(new DefaultTracer(), null);

    // Then
    assertThat(span.isRoot()).isTrue();
  }

  @Test
  @DisplayName("should not be root when parent is set")
  void shouldNotBeRootWhenParentIsSet() {
    // Given
    TraceIdGenerator traceIdGenerator = new DefaultTracer();
    Span parent = new Span(traceIdGenerator, null);

    // When
    Span span = new Span(traceIdGenerator, parent);

    // Then
    assertThat(span.isRoot()).isFalse();
  }
}
