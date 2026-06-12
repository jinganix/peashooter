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
  @DisplayName("should parse lowercase hex letters in trace flags")
  void shouldParseLowercaseHexLettersInTraceFlags() {
    W3CTraceContext.Context context =
        W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0a");

    assertThat(context.sampled()).isFalse();
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

  @Test
  @DisplayName("should reject null traceparent")
  void shouldRejectNullTraceparent() {
    assertThatThrownBy(() -> W3CTraceContext.parse(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should reject traceparent with wrong field count")
  void shouldRejectTraceparentWithWrongFieldCount() {
    assertThatThrownBy(() -> W3CTraceContext.parse("00-abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4");
  }

  @Test
  @DisplayName("should reject unsupported traceparent version")
  void shouldRejectUnsupportedTraceparentVersion() {
    assertThatThrownBy(() -> W3CTraceContext.parse("01-" + TRACEPARENT.substring(3)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
  }

  @Test
  @DisplayName("should reject all-zero trace id in traceparent")
  void shouldRejectAllZeroTraceIdInTraceparent() {
    assertThatThrownBy(
            () -> W3CTraceContext.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trace-id");
  }

  @Test
  @DisplayName("should reject all-zero span id in traceparent")
  void shouldRejectAllZeroSpanIdInTraceparent() {
    assertThatThrownBy(
            () -> W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("span id");
  }

  @Test
  @DisplayName("should reject invalid trace flags in traceparent")
  void shouldRejectInvalidTraceFlagsInTraceparent() {
    assertThatThrownBy(
            () -> W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trace-flags");
    assertThatThrownBy(
            () -> W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-zz"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trace-flags");
    assertThatThrownBy(
            () -> W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-g0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trace-flags");
  }

  @Test
  @DisplayName("should parse unsampled traceparent")
  void shouldParseUnsampledTraceparent() {
    W3CTraceContext.Context context =
        W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");

    assertThat(context.sampled()).isFalse();
  }

  @Test
  @DisplayName("should normalize uppercase hex in traceparent")
  void shouldNormalizeUppercaseHexInTraceparent() {
    W3CTraceContext.Context context =
        W3CTraceContext.parse("00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01");

    assertThat(context.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(context.parentSpanId()).isEqualTo("00f067aa0ba902b7");
  }

  @Test
  @DisplayName("should inject unsampled traceparent")
  void shouldInjectUnsampledTraceparent() {
    Span span = new Span(TraceIds.nextTraceId(), TraceIds.nextSpanId(), null);

    assertThat(W3CTraceContext.inject(span, false)).endsWith("-00");
  }

  @Test
  @DisplayName("should reject null span on inject")
  void shouldRejectNullSpanOnInject() {
    assertThatThrownBy(() -> W3CTraceContext.inject(null, true))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should reject invalid ids on inject")
  void shouldRejectInvalidIdsOnInject() {
    Span invalidTraceId = new Span("00000000000000000000000000000000", TraceIds.nextSpanId(), null);
    Span invalidSpanId = new Span(TraceIds.nextTraceId(), "0000000000000000", null);

    assertThatThrownBy(() -> W3CTraceContext.inject(invalidTraceId, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trace id");
    assertThatThrownBy(() -> W3CTraceContext.inject(invalidSpanId, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("span id");
  }
}
