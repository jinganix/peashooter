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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("W3CTraceContext")
class W3CTraceContextTest {

  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Test
  @DisplayName("should parse traceparent header")
  void shouldParseTraceparentHeader() {
    // When
    W3CTraceContext.Context context = W3CTraceContext.parse(TRACEPARENT);

    // Then
    assertThat(context.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(context.parentSpanId()).isEqualTo("00f067aa0ba902b7");
    assertThat(context.sampled()).isTrue();
  }

  @Test
  @DisplayName("should round-trip inject and extract parent")
  void shouldRoundTripInjectAndExtractParent() {
    // Given
    Span span = new Span(TraceIds.nextTraceId(), TraceIds.nextSpanId(), null);

    // When
    String header = W3CTraceContext.inject(span, true);
    Span parent = W3CTraceContext.extractParent(header);
    Span child = new Span(new DefaultTracer(), parent);

    // Then
    assertThat(parent.getTraceId()).isEqualTo(span.getTraceId());
    assertThat(parent.getSpanId()).isEqualTo(span.getSpanId());
    assertThat(child.getTraceId()).isEqualTo(span.getTraceId());
    assertThat(child.getSpanId()).isNotEqualTo(span.getSpanId());
    assertThat(child.getParent()).isEqualTo(parent);
  }

  @Test
  @DisplayName("should reject invalid traceparent")
  void shouldRejectInvalidTraceparent() {
    // Then
    assertThatThrownBy(() -> W3CTraceContext.parse("invalid"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
