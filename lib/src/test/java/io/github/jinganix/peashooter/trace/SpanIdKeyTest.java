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

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SpanIdKey")
class SpanIdKeyTest {

  @Test
  @DisplayName("should compare by trace and span ids when keyed from spans")
  void shouldCompareByTraceAndSpanIdsWhenKeyedFromSpans() {
    // Given
    Span parent = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span a = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span b = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", parent);

    // When / Then ids match across different parents, so the keys are equal
    assertThat(SpanIdKey.of(a)).isEqualTo(SpanIdKey.of(b));
    assertThat(SpanIdKey.of(a).hashCode()).isEqualTo(SpanIdKey.of(b).hashCode());
    assertThat(SpanIdKey.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"))
        .isEqualTo(SpanIdKey.of(a));
  }

  @Test
  @DisplayName("should distinguish keys when either id differs")
  void shouldDistinguishKeysWhenEitherIdDiffers() {
    // Given
    SpanIdKey key = SpanIdKey.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb");

    // When / Then
    assertThat(key)
        .isNotEqualTo(SpanIdKey.of("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "bbbbbbbbbbbbbbbb"));
    assertThat(key)
        .isNotEqualTo(SpanIdKey.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cccccccccccccccc"));
    assertThat(key).isNotEqualTo(null);
    assertThat(key).isNotEqualTo("not-a-key");
  }

  @Test
  @DisplayName("should round-trip spans through map keys when ids are shared")
  void shouldRoundTripSpansThroughMapKeysWhenIdsAreShared() {
    // Given
    Span a = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span b =
        Span.ofIds(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "bbbbbbbbbbbbbbbb",
            Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cccccccccccccccc", null));

    // When
    Map<SpanIdKey, String> map = new HashMap<>();
    map.put(SpanIdKey.of(a), "v");

    // Then
    assertThat(map.get(SpanIdKey.of(b))).isEqualTo("v");
  }

  @Test
  @DisplayName("should fail fast when ids or spans are null")
  void shouldFailFastWhenIdsOrSpansAreNull() {
    // When / Then
    assertThatThrownBy(() -> SpanIdKey.of(null, "bbbbbbbbbbbbbbbb"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> SpanIdKey.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> SpanIdKey.of((Span) null)).isInstanceOf(NullPointerException.class);
  }
}
