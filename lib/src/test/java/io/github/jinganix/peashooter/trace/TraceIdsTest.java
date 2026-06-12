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

import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

@DisplayName("TraceIds")
class TraceIdsTest {

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
}
