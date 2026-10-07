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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SpanIdentity")
class SpanIdentityTest {

  @Test
  @DisplayName("should not be equal when ids match but parents differ")
  void shouldNotBeEqualWhenIdsMatchButParentsDiffer() {
    // Given
    Span parent = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span a = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span b = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", parent);

    // When / Then distinct instances stay distinct even with identical ids
    assertThat(a).isNotEqualTo(b);
    assertThat(b).isNotEqualTo(a);
  }

  @Test
  @DisplayName("should not collide as map keys when ids match")
  void shouldNotCollideAsMapKeysWhenIdsMatch() {
    // Given
    Span parent = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span a = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    Span b = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", parent);

    // When
    Map<Span, String> map = new HashMap<>();
    map.put(a, "first");
    map.put(b, "second");

    // Then identity semantics keep both entries
    assertThat(map).hasSize(2);
    assertThat(map.get(a)).isEqualTo("first");
    assertThat(map.get(b)).isEqualTo("second");
  }

  @Test
  @DisplayName("should not be equal when ordered span shares ids with plain span")
  void shouldNotBeEqualWhenOrderedSpanSharesIdsWithPlainSpan() {
    // Given
    TraceIdGenerator reusedIds =
        new TraceIdGenerator() {
          @Override
          public String nextTraceId() {
            return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
          }

          @Override
          public String nextSpanId() {
            return "bbbbbbbbbbbbbbbb";
          }
        };
    OrderedSpan ordered = OrderedSpan.child(reusedIds, null, "key-a", true);
    Span plain = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);

    // When / Then cross-type instances with identical ids stay distinct
    assertThat(ordered).isNotEqualTo(plain);
    assertThat(plain).isNotEqualTo(ordered);
  }

  @Test
  @DisplayName("should not be equal when ordered spans share ids across keys")
  void shouldNotBeEqualWhenOrderedSpansShareIdsAcrossKeys() {
    // Given
    TraceIdGenerator reusedIds =
        new TraceIdGenerator() {
          @Override
          public String nextTraceId() {
            return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
          }

          @Override
          public String nextSpanId() {
            return "bbbbbbbbbbbbbbbb";
          }
        };
    OrderedSpan keyedSync = OrderedSpan.child(reusedIds, null, "key-a", true);
    OrderedSpan keyedAsync = OrderedSpan.child(reusedIds, null, "key-b", false);

    // When / Then key/sync differences ride on distinct instances, never value equality
    assertThat(keyedSync).isNotEqualTo(keyedAsync);
  }
}
