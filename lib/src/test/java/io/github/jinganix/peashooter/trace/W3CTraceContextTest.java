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
  @DisplayName("should reject non-canonical headers without normalization")
  void shouldRejectNonCanonicalHeadersWithoutNormalization() {
    // Given an uppercase header with surrounding whitespace (non-canonical senders must normalize)
    String header = "  00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01  ";
    assertThatThrownBy(() -> W3CTraceContext.extractParent(header))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> W3CTraceContext.parse(header))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should reject surrounding whitespace strictly")
  void shouldRejectSurroundingWhitespaceStrictly() {
    assertThatThrownBy(() -> W3CTraceContext.parse(" \t\n\r\f" + TRACEPARENT + "\f\r\n\t "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> W3CTraceContext.parse(" " + TRACEPARENT + " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should reject vertical tab surrounding strictly")
  void shouldRejectVerticalTabSurroundingStrictly() {
    assertThatThrownBy(() -> W3CTraceContext.parse("\u000B" + TRACEPARENT + "\u000B"))
        .isInstanceOf(IllegalArgumentException.class);
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
    Span span = Span.ofIds(TraceIds.nextTraceId(), TraceIds.nextSpanId(), null);

    // When
    String header = W3CTraceContext.inject(span, true);
    Span parent = W3CTraceContext.extractParent(header);
    Span child = Span.child(new DefaultTracer(), parent);

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
    // Two dashes: version and trace id present but parent id missing (third delimiter absent).
    assertThatThrownBy(
            () -> W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4");
  }

  @Test
  @DisplayName("should reject empty traceparent without scanning whitespace")
  void shouldRejectEmptyTraceparentWithoutScanningWhitespace() {
    // Empty input short-circuits the dash scan (no delimiters on entry).
    assertThatThrownBy(() -> W3CTraceContext.parse(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should accept future traceparent versions and reject invalid ones")
  void shouldAcceptFutureTraceparentVersionsAndRejectInvalidOnes() {
    // Future version with the same 4 fields: accepted, extras ignored (W3C forward compat).
    W3CTraceContext.Context future = W3CTraceContext.parse("01-" + TRACEPARENT.substring(3));
    assertThat(future.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

    // Future version with trailing fields: accepted.
    W3CTraceContext.Context extended =
        W3CTraceContext.parse("01-" + TRACEPARENT.substring(3) + "-extra");
    assertThat(extended.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

    // Version 00 with trailing fields: rejected (strict for the defined version).
    assertThatThrownBy(() -> W3CTraceContext.parse(TRACEPARENT + "-extra"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4");

    // Invalid versions: ff and non-hex.
    assertThatThrownBy(() -> W3CTraceContext.parse("ff-" + TRACEPARENT.substring(3)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
    assertThatThrownBy(() -> W3CTraceContext.parse("zz-" + TRACEPARENT.substring(3)))
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
  @DisplayName("should reject uppercase hex in traceparent")
  void shouldRejectUppercaseHexInTraceparent() {
    assertThatThrownBy(
            () -> W3CTraceContext.parse("00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should inject with default unsampled flags")
  void shouldInjectWithDefaultUnsampledFlags() {
    Span span = Span.ofIds(TraceIds.nextTraceId(), TraceIds.nextSpanId(), null);

    assertThat(W3CTraceContext.inject(span)).endsWith("-00");
  }

  @Test
  @DisplayName("should inject unsampled traceparent")
  void shouldInjectUnsampledTraceparent() {
    Span span = Span.ofIds(TraceIds.nextTraceId(), TraceIds.nextSpanId(), null);

    assertThat(W3CTraceContext.inject(span, false)).endsWith("-00");
  }

  @Test
  @DisplayName("should reject null span on inject")
  void shouldRejectNullSpanOnInject() {
    assertThatThrownBy(() -> W3CTraceContext.inject(null, true))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should sanitize control characters in Context rejection message")
  void shouldSanitizeControlCharactersInContextRejectionMessage() {
    // Given an attacker-controlled trace id carrying a log-forging newline
    String evil = "bad\nFORGED-line";
    // When / Then the rejection message must neutralize the line break
    assertThatThrownBy(() -> new W3CTraceContext.Context(evil, "00f067aa0ba902b7", true, "00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("\n")
        .hasMessageContaining("bad_FORGED-line");
  }

  @Test
  @DisplayName("should include the value when rejecting invalid Context")
  void shouldIncludeTheValueWhenRejectingInvalidContext() {
    assertThatThrownBy(() -> new W3CTraceContext.Context("bad", "00f067aa0ba902b7", true, "00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bad");
  }

  @Test
  @DisplayName("should reject directly constructed invalid Context")
  void shouldRejectDirectlyConstructedInvalidContext() {
    assertThatThrownBy(() -> new W3CTraceContext.Context("bad", "00f067aa0ba902b7", true, "00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("traceId");
    assertThatThrownBy(
            () ->
                new W3CTraceContext.Context("4bf92f3577b34da6a3ce929d0e0e4736", "bad", true, "00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parentSpanId");
  }

  @Test
  @DisplayName("should reject invalid ids on inject")
  void shouldRejectInvalidIdsOnInject() {
    Span invalidTraceId =
        Span.ofIdsUnchecked("00000000000000000000000000000000", TraceIds.nextSpanId(), null);
    Span invalidSpanId = Span.ofIdsUnchecked(TraceIds.nextTraceId(), "0000000000000000", null);

    assertThatThrownBy(() -> W3CTraceContext.inject(invalidTraceId, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trace id");
    assertThatThrownBy(() -> W3CTraceContext.inject(invalidSpanId, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("span id");
  }

  @Test
  @DisplayName("should reject undelimited long flags for future versions")
  void shouldRejectUndelimitedLongFlagsForFutureVersions() {
    // W3C keeps trace-flags at exactly 2 hex chars for every version; extra fields must be
    // '-'-delimited. A 4-char run with no delimiter is a malformed field, not an extension.
    assertThatThrownBy(
            () ->
                W3CTraceContext.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0100"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                W3CTraceContext.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01zz"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should mask sampled bit from dash-delimited extensions of future versions")
  void shouldMaskSampledBitFromDashDelimitedExtensionsOfFutureVersions() {
    // Given future-version headers with a 2-char flags field followed by '-' extension fields
    W3CTraceContext.Context sampled =
        W3CTraceContext.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-ff");
    W3CTraceContext.Context unsampled =
        W3CTraceContext.parse("7f-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-02-00");

    // When / Then only the low sampled bit of the two-char flags is read
    assertThat(sampled.sampled()).isTrue();
    assertThat(unsampled.sampled()).isFalse();
  }

  @Test
  @DisplayName("should still reject long flags for version 00")
  void shouldStillRejectLongFlagsForVersion00() {
    assertThatThrownBy(
            () ->
                W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0100"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should reject malformed flags strictly")
  void shouldRejectMalformedFlagsStrictly() {
    // Non-hex flags are rejected
    assertThatThrownBy(
            () -> W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0x"))
        .isInstanceOf(IllegalArgumentException.class);
    // Future-version flags shorter than a pair are rejected too
    assertThatThrownBy(
            () -> W3CTraceContext.parse("7f-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should preserve the traceparent version in the parsed context")
  void shouldPreserveVersionInParsedContext() {
    // Given the defined version and two future versions
    // When parsing Then the version round-trips through the context (nothing else carries it:
    // Span has no version field, so dropping it here would lose it silently)
    assertThat(W3CTraceContext.parse(TRACEPARENT).version()).isEqualTo("00");
    assertThat(W3CTraceContext.parse("01-" + TRACEPARENT.substring(3)).version()).isEqualTo("01");
    assertThat(
            W3CTraceContext.parse("7f-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-02-00")
                .version())
        .isEqualTo("7f");
  }

  @Test
  @DisplayName("should downgrade future versions to 00 on inject")
  void shouldDowngradeFutureVersionsTo00OnInject() {
    // Given a future-version inbound header (version preserved on parse)
    W3CTraceContext.Context context = W3CTraceContext.parse("01-" + TRACEPARENT.substring(3));
    assertThat(context.version()).isEqualTo("01");

    // When re-emitting Then the header downgrades to the only version this library speaks:
    // callers needing version preservation must forward the raw header instead.
    Span parent = W3CTraceContext.extractParent("01-" + TRACEPARENT.substring(3));
    assertThat(W3CTraceContext.inject(parent, context.sampled())).startsWith("00-");
  }

  @Test
  @DisplayName("should reject invalid versions on direct Context construction")
  void shouldRejectInvalidVersionsOnDirectConstruction() {
    assertThatThrownBy(
            () ->
                new W3CTraceContext.Context(
                    "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true, "ff"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
    assertThatThrownBy(
            () ->
                new W3CTraceContext.Context(
                    "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true, "0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
    assertThatThrownBy(
            () ->
                new W3CTraceContext.Context(
                    "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should parse strictly by default")
  void shouldParseStrictlyByDefault() {
    // Strict accepts the canonical header
    W3CTraceContext.Context context = W3CTraceContext.parse(TRACEPARENT);
    assertThat(context.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

    // But rejects surrounding whitespace and uppercase hex
    assertThatThrownBy(() -> W3CTraceContext.parse(" " + TRACEPARENT + " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> W3CTraceContext.parse("00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should drop non-sampled flag bits and downgrade version observably on inject")
  void shouldDropNonSampledFlagBitsAndDowngradeVersionObservablyOnInject() {
    // Given a future-version header with extra flag bits (0x09: sampled + 0x08 vendor bit)
    String inbound = "7f-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-09-00";
    W3CTraceContext.Context context = W3CTraceContext.parse(inbound);
    assertThat(context.sampled()).isTrue();
    assertThat(context.version()).isEqualTo("7f");

    // When re-emitting from the span Then only the sampled bit survives and version downgrades:
    // the downgrade is observable by comparing Context.version() against the output prefix.
    Span parent = W3CTraceContext.extractParent(inbound);
    String header = W3CTraceContext.inject(parent, context.sampled());
    assertThat(header).startsWith("00-");
    assertThat(header).endsWith("-01");
    assertThat(header).isNotEqualTo(inbound);
    assertThat(context.version()).isNotEqualTo(header.substring(0, 2));

    // And a set extra bit without sampled (0x08) injects as unsampled, proving non-sampled
    // bits are dropped rather than forwarded.
    W3CTraceContext.Context unsampled =
        W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-08");
    assertThat(unsampled.sampled()).isFalse();
    Span unsampledParent =
        W3CTraceContext.extractParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-08");
    assertThat(W3CTraceContext.inject(unsampledParent, unsampled.sampled())).endsWith("-00");

    // Version-preserving forward (javadoc recipe): raw header survives when version differs.
    String forwarded =
        !"00".equals(context.version())
            ? inbound
            : W3CTraceContext.inject(parent, context.sampled());
    assertThat(W3CTraceContext.parse(forwarded).version()).isEqualTo("7f");
  }

  @Test
  @DisplayName("should preserve tracestate when downgrading future version on inject")
  void shouldPreserveTracestateWhenDowngradingFutureVersionOnInject() {
    // Given a future-version inbound header plus an opaque tracestate value
    String inbound = "7f-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-09-00";
    String tracestate = "rojo=00f067aa0ba902b7,congo=t61rcWkgMzE";
    W3CTraceContext.Context context = W3CTraceContext.parse(inbound);
    Span parent = W3CTraceContext.extractParent(inbound);

    // When re-emitting with tracestate passthrough
    W3CTraceContext.Headers headers = W3CTraceContext.inject(parent, context.sampled(), tracestate);

    // Then traceparent explicitly downgrades to 00 while tracestate passes through untouched
    assertThat(headers.traceparent()).startsWith("00-");
    assertThat(W3CTraceContext.parse(headers.traceparent()).version()).isEqualTo("00");
    assertThat(context.version()).isNotEqualTo("00");
    assertThat(headers.tracestate()).isSameAs(tracestate);
  }

  @Test
  @DisplayName("should include sanitized snippet when rejecting invalid traceparent")
  void shouldIncludeSanitizedSnippetWhenRejectingInvalidTraceparent() {
    // Given an attacker-controlled header carrying a log-forging newline
    String evilFlags = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0\nFORGED";

    // When / Then the rejection must carry the sanitized offending fragment, not the raw break
    assertThatThrownBy(() -> W3CTraceContext.parse(evilFlags))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trace-flags")
        .hasMessageContaining("0_FORGED")
        .hasMessageNotContaining("\nFORGED");
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> tracestateVectors() {
    String longValue = "a".repeat(512);
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("rojo=00f067aa0ba902b7", true),
        org.junit.jupiter.params.provider.Arguments.of(
            "rojo=00f067aa0ba902b7,congo=t61rcWkgMzE", true),
        org.junit.jupiter.params.provider.Arguments.of("  rojo=00f067aa0ba902b7  ", true),
        org.junit.jupiter.params.provider.Arguments.of("rojo = 00f067aa0ba902b7", true),
        org.junit.jupiter.params.provider.Arguments.of("k=v=" + "x", false),
        org.junit.jupiter.params.provider.Arguments.of("rojo=", false),
        org.junit.jupiter.params.provider.Arguments.of("=value", false),
        org.junit.jupiter.params.provider.Arguments.of("rojo", false),
        org.junit.jupiter.params.provider.Arguments.of("", false),
        org.junit.jupiter.params.provider.Arguments.of("a=b,,c=d", false),
        org.junit.jupiter.params.provider.Arguments.of("a=b,c=d,", false),
        org.junit.jupiter.params.provider.Arguments.of("a=b\u007Fc=d", false),
        org.junit.jupiter.params.provider.Arguments.of("a=b\nc=d", false),
        org.junit.jupiter.params.provider.Arguments.of("a=" + longValue, false));
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0} -> {1}")
  @org.junit.jupiter.params.provider.MethodSource("tracestateVectors")
  @DisplayName("should validate tracestate vectors in one scan")
  void shouldValidateTracestateVectorsInOneScan(String value, boolean expected) {
    assertThat(W3CTraceContext.isValidTracestate(value)).isEqualTo(expected);
  }

  @Test
  @DisplayName("should reject tracestate with inner whitespace while keeping edge whitespace")
  void shouldRejectTracestateWithInnerWhitespaceWhileKeepingEdgeWhitespace() {
    // Inner spaces/tabs inside a key or value are never valid
    assertThat(W3CTraceContext.isValidTracestate("rojo=ab cd")).isFalse();
    assertThat(W3CTraceContext.isValidTracestate("ro ja=v")).isFalse();
    assertThat(W3CTraceContext.isValidTracestate("rojo=ab\tcd")).isFalse();
    assertThat(W3CTraceContext.isValidTracestate("a=b, c =d e")).isFalse();

    // Leading/trailing OWS around the header and around key/value stays tolerated
    assertThat(W3CTraceContext.isValidTracestate("  rojo=00f067aa0ba902b7  ")).isTrue();
    assertThat(W3CTraceContext.isValidTracestate("rojo = 00f067aa0ba902b7")).isTrue();
    assertThat(W3CTraceContext.isValidTracestate("a=b,\tc=d")).isTrue();
  }

  @Test
  @DisplayName("should reject null and oversized tracestate")
  void shouldRejectNullAndOversizedTracestate() {
    assertThat(W3CTraceContext.isValidTracestate(null)).isFalse();
    assertThat(W3CTraceContext.isValidTracestate("k=" + "a".repeat(510))).isTrue();
    assertThat(W3CTraceContext.isValidTracestate("k=" + "a".repeat(511))).isFalse();
  }

  @Test
  @DisplayName("should reject null Context components with NPE for parity with Span")
  void shouldRejectNullContextComponentsWithNPE() {
    // Given valid ids
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String spanId = "00f067aa0ba902b7";
    // When / Then null must throw NPE (programming error), malformed throws IAE
    // Parity with Span.ofIds(null, ...) which throws NPE via requireNonNull
    assertThatThrownBy(() -> new W3CTraceContext.Context(null, spanId, true, "00"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new W3CTraceContext.Context(traceId, null, true, "00"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new W3CTraceContext.Context(traceId, spanId, true, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should reject invalid tracestate on inject with passthrough")
  void shouldRejectInvalidTracestateOnInjectWithPassthrough() {
    Span span = Span.ofIds(TraceIds.nextTraceId(), TraceIds.nextSpanId(), null);
    assertThatThrownBy(() -> W3CTraceContext.inject(span, false, "rojo="))
        .isInstanceOf(IllegalArgumentException.class);
    W3CTraceContext.Headers blank = W3CTraceContext.inject(span, false, "   ");
    assertThat(blank.tracestate()).isNull();
  }
}
