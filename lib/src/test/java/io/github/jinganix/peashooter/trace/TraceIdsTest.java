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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

@DisplayName("TraceIds")
class TraceIdsTest {

  @Test
  @DisplayName("should require explicit span id generation without silent fallback")
  void shouldRequireExplicitSpanIdGenerationWithoutSilentFallback() throws NoSuchMethodException {
    // A custom nextTraceId silently paired with the global nextSpanId splits observability;
    // generators must implement both explicitly.
    assertThat(
            io.github.jinganix.peashooter.TraceIdGenerator.class
                .getMethod("nextSpanId")
                .isDefault())
        .isFalse();
  }

  @Test
  @DisplayName("should generate valid W3C trace and span ids")
  void shouldGenerateValidW3CTraceAndSpanIds() {
    // When
    String traceId = TraceIds.nextTraceId();
    String spanId = TraceIds.nextSpanId();

    // Then
    assertThat(TraceIds.isValidTraceId(traceId)).isTrue();
    assertThat(TraceIds.isValidSpanId(spanId)).isTrue();
  }

  @Test
  @DisplayName("should reject all-zero ids")
  void shouldRejectAllZeroIds() {
    // Then
    assertThat(TraceIds.isValidTraceId("00000000000000000000000000000000")).isFalse();
    assertThat(TraceIds.isValidSpanId("0000000000000000")).isFalse();
  }

  @Test
  @DisplayName("should reject invalid hex characters")
  void shouldRejectInvalidHexCharacters() {
    assertThat(TraceIds.isValidTraceId("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")).isFalse();
    assertThat(TraceIds.isValidSpanId("zzzzzzzzzzzzzzzz")).isFalse();
    assertThat(TraceIds.isValidTraceId("4BF92F3577B34DA6A3CE929D0E0E4736")).isFalse();
    // Characters below '0' exercise the first digit-range operand (e.g. '-' from a
    // mis-split header is neither a digit nor lowercase hex).
    assertThat(TraceIds.isLowerHex("4bf92f3577b34da6a3ce929d0e0e473-")).isFalse();
    assertThat(TraceIds.isValidTraceId("4bf92f3577b34da6a3ce929d0e0e473-")).isFalse();
  }

  @Test
  @DisplayName("should reject empty string as hex")
  void shouldRejectEmptyStringAsHex() {
    assertThat(TraceIds.isLowerHex("")).isFalse();
  }

  @Test
  @DisplayName("should handle null hex inputs safely")
  void shouldHandleNullHexInputsSafely() {
    assertThat(TraceIds.isLowerHex(null)).isFalse();
    assertThat(TraceIds.isValidTraceId(null)).isFalse();
    assertThat(TraceIds.isValidSpanId(null)).isFalse();
  }

  @Test
  @DisplayName("should reject uppercase hex strictly")
  void shouldRejectUppercaseHexStrictly() {
    assertThat(TraceIds.isValidTraceId("4BF92F3577B34DA6A3CE929D0E0E4736")).isFalse();
    assertThat(TraceIds.isLowerHex("4BF92F")).isFalse();
    assertThat(TraceIds.isLowerHex("4bf92f3577b34da6a3ce929d0e0e4736")).isTrue();
  }

  @Test
  @DisplayName("should skip all-zero random values")
  void shouldSkipAllZeroRandomValues() {
    ThreadLocalRandom random = mock(ThreadLocalRandom.class);
    try (MockedStatic<ThreadLocalRandom> mocked = mockStatic(ThreadLocalRandom.class)) {
      mocked.when(ThreadLocalRandom::current).thenReturn(random);
      when(random.nextLong()).thenReturn(0L, 0L, 0L, 1L, 0L, 1L);

      assertThat(TraceIds.nextTraceId()).isEqualTo("00000000000000000000000000000001");
      assertThat(TraceIds.nextSpanId()).isEqualTo("0000000000000001");
    }
  }

  @Test
  @DisplayName("should generate distinct random ids for the id-key uniqueness contract")
  void shouldGenerateDistinctRandomIdsForTheIdKeyUniquenessContract() {
    // SpanIdKey compares only ids, so generators MUST be globally unique: 200 draws
    // must not collide and every draw must be W3C-valid (covers the test-helper contract
    // that hand-rolled constant ids violate).
    Set<String> traceIds = new HashSet<>();
    Set<String> spanIds = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      String traceId = TraceIds.nextTraceId();
      String spanId = TraceIds.nextSpanId();
      assertThat(TraceIds.isValidTraceId(traceId)).isTrue();
      assertThat(TraceIds.isValidSpanId(spanId)).isTrue();
      traceIds.add(traceId);
      spanIds.add(spanId);
    }
    assertThat(traceIds).hasSize(200);
    assertThat(spanIds).hasSize(200);
  }
}
