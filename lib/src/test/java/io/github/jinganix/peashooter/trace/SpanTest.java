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

import io.github.jinganix.peashooter.TraceIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Span")
class SpanTest {

  @Test
  @DisplayName("should report W3C validity of carried ids")
  void shouldReportW3cValidityOfCarriedIds() {
    // Generated spans are valid; hand-built unchecked spans may not be
    assertThat(Span.child(new DefaultTracer(), null).isValid()).isTrue();
    assertThat(Span.ofIdsUnchecked("trace", TraceIds.nextSpanId(), null).isValid()).isFalse();
    assertThat(
            Span.ofIdsUnchecked("00000000000000000000000000000000", "0000000000000000", null)
                .isValid())
        .isFalse();
    // Valid trace id with an invalid span id is still invalid (covers the second conjunct)
    assertThat(
            Span.ofIdsUnchecked("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "not-a-span-id", null)
                .isValid())
        .isFalse();
  }

  @Test
  @DisplayName("should reject invalid ids in strict factories")
  void shouldRejectInvalidIdsInStrictFactories() {
    assertThatThrownBy(() -> Span.ofIds("trace", TraceIds.nextSpanId(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("traceId");
    assertThatThrownBy(
            () -> Span.ofIds("00000000000000000000000000000000", "0000000000000000", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "not-a-span-id", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spanId");
    assertThatThrownBy(() -> Span.continueTrace("trace", new DefaultTracer(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("traceId");
  }

  @Test
  @DisplayName("should reject invalid generator ids in strict child factory")
  void shouldRejectInvalidGeneratorIdsInStrictChildFactory() {
    TraceIdGenerator badTraceId =
        new TraceIdGenerator() {
          @Override
          public String nextTraceId() {
            return "trace";
          }

          @Override
          public String nextSpanId() {
            return TraceIds.nextSpanId();
          }
        };
    TraceIdGenerator badSpanId =
        new TraceIdGenerator() {
          @Override
          public String nextTraceId() {
            return TraceIds.nextTraceId();
          }

          @Override
          public String nextSpanId() {
            return "span";
          }
        };

    assertThatThrownBy(() -> Span.child(badTraceId, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Span.child(badSpanId, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should keep unchecked ids through the package factory")
  void shouldKeepUncheckedIdsThroughThePackageFactory() {
    // When
    Span span = Span.ofIdsUnchecked("trace", TraceIds.nextSpanId(), null);

    // Then
    assertThat(span.getTraceId()).isEqualTo("trace");
    assertThat(span.isValid()).isFalse();
  }

  @Test
  @DisplayName("should keep an explicit trace id when provided")
  void shouldKeepAnExplicitTraceIdWhenProvided() {
    // When
    Span span = Span.ofIdsUnchecked("trace", TraceIds.nextSpanId(), null);

    // Then
    assertThat(span.getTraceId()).isEqualTo("trace");
  }

  @Test
  @DisplayName("should generate a trace id when parent is null")
  void shouldGenerateATraceIdWhenParentIsNull() {
    // When
    Span span = Span.child(new DefaultTracer(), null);

    // Then
    assertThat(span.getParent()).isNull();
    assertThat(span.getTraceId()).matches("[0-9a-f]{32}");
    assertThat(span.getSpanId()).matches("[0-9a-f]{16}");
  }

  @Test
  @DisplayName("should inherit trace id from parent when parent is set")
  void shouldInheritTraceIdFromParentWhenParentIsSet() {
    // Given
    Span parent = Span.child(new DefaultTracer(), null);

    // When
    Span span = Span.child(new DefaultTracer(), parent);

    // Then
    assertThat(span.getParent()).isEqualTo(parent);
    assertThat(span.getTraceId()).isEqualTo(parent.getTraceId());
    assertThat(span.getSpanId()).isNotEqualTo(parent.getSpanId());
  }

  @Test
  @DisplayName("should force an explicit trace id even when parent is set")
  void shouldForceAnExplicitTraceIdEvenWhenParentIsSet() {
    // Given
    Span parent = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);

    // When
    Span span = Span.continueTrace("cccccccccccccccccccccccccccccccc", new DefaultTracer(), parent);

    // Then
    assertThat(span.getTraceId()).isEqualTo("cccccccccccccccccccccccccccccccc");
    assertThat(span.getParent()).isEqualTo(parent);
    assertThat(span.getSpanId()).matches("[0-9a-f]{16}");
    assertThat(span.isRoot()).isFalse();
  }

  @Test
  @DisplayName("should be root when parent is null")
  void shouldBeRootWhenParentIsNull() {
    // When
    Span span = Span.child(new DefaultTracer(), null);

    // Then
    assertThat(span.isRoot()).isTrue();
  }

  @Test
  @DisplayName("should include trace and span ids in toString")
  void shouldIncludeTraceAndSpanIdsInToString() {
    // Given
    Span span = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);

    // When / Then
    assertThat(span.toString())
        .contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .contains("bbbbbbbbbbbbbbbb");
  }

  @Test
  @DisplayName("should fail fast when generator returns null ids")
  void shouldFailFastWhenGeneratorReturnsNullIds() {
    TraceIdGenerator nullTrace =
        new TraceIdGenerator() {
          @Override
          public String nextTraceId() {
            return null;
          }

          @Override
          public String nextSpanId() {
            return null;
          }
        };
    TraceIdGenerator nullSpan =
        new TraceIdGenerator() {
          @Override
          public String nextTraceId() {
            return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
          }

          @Override
          public String nextSpanId() {
            return null;
          }
        };

    assertThatThrownBy(() -> Span.child(nullTrace, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Span.child(nullSpan, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Span.continueTrace("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", nullSpan, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should use identity equality even when ids match")
  void shouldUseIdentityEqualityEvenWhenIdsMatch() {
    // Given two spans with identical ids but different parents
    Span parent = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span a = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span b = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", parent);
    Span other = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cccccccccccccccc", null);

    // When / Then equality is identity: distinct instances are never equal
    assertThat(a).isNotEqualTo(b);
    assertThat(a).isNotEqualTo(other);
    assertThat(a).isNotEqualTo(null);
    assertThat(a).isNotEqualTo("not-a-span");
    // Different trace ids are unequal even with matching span ids
    assertThat(a)
        .isNotEqualTo(Span.ofIds("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "bbbbbbbbbbbbbbbb", null));
    // SpanIdKey carries the by-ids lookup instead
    assertThat(SpanIdKey.of(a)).isEqualTo(SpanIdKey.of(b));
    java.util.Map<SpanIdKey, String> map = new java.util.HashMap<>();
    map.put(SpanIdKey.of(a), "v");
    assertThat(map.get(SpanIdKey.of(b))).isEqualTo("v");
  }

  @Test
  @DisplayName("should be equal to itself")
  void shouldBeEqualToItself() {
    // Given a span Then reflexive equality holds
    Span span = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);

    assertThat(span.equals(span)).isTrue();
    assertThat(span.hashCode()).isEqualTo(span.hashCode());
  }

  @Test
  @DisplayName("should not be root when parent is set")
  void shouldNotBeRootWhenParentIsSet() {
    // Given
    TraceIdGenerator traceIdGenerator = new DefaultTracer();
    Span parent = Span.child(traceIdGenerator, null);

    // When
    Span span = Span.child(traceIdGenerator, parent);

    // Then
    assertThat(span.isRoot()).isFalse();
  }

  @Test
  @DisplayName("should keep reused ids distinct as spans but colliding as id keys")
  void shouldKeepReusedIdsDistinctAsSpansButCollidingAsIdKeys() {
    // Given an id pair reused for a logically different hop (contract violation: custom
    // TraceIdGenerators MUST be globally unique, see TraceIdGenerator javadoc)
    Span parent = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span reusedA = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span reusedB =
        Span.ofIds(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "bbbbbbbbbbbbbbbb",
            Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cccccccccccccccc", parent));

    // When / Then spans stay distinct, while their id keys collide:
    // never reuse ids in production or test helpers that need distinct spans.
    assertThat(reusedA).isNotEqualTo(reusedB);
    java.util.Map<Span, String> spanMap = new java.util.HashMap<>();
    spanMap.put(reusedA, "first");
    spanMap.put(reusedB, "second");
    assertThat(spanMap).hasSize(2);
    java.util.Map<SpanIdKey, String> keyMap = new java.util.HashMap<>();
    keyMap.put(SpanIdKey.of(reusedA), "first");
    keyMap.put(SpanIdKey.of(reusedB), "second");
    assertThat(keyMap).hasSize(1);
    assertThat(keyMap.get(SpanIdKey.of(reusedA))).isEqualTo("second");

    // And randomly generated spans never collide (the compliant path).
    Span randomA = Span.child(new DefaultTracer(), null);
    Span randomB = Span.child(new DefaultTracer(), null);
    assertThat(randomA).isNotEqualTo(randomB);
    assertThat(SpanIdKey.of(randomA)).isNotEqualTo(SpanIdKey.of(randomB));
  }
}
