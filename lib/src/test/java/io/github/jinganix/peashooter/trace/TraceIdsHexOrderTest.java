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

@DisplayName("TraceIds hex byte order")
class TraceIdsHexOrderTest {

  @Test
  @DisplayName("should encode longs big-endian without ByteBuffer")
  void shouldEncodeBigEndian() {
    ThreadLocalRandom random = mock(ThreadLocalRandom.class);
    try (MockedStatic<ThreadLocalRandom> mocked = mockStatic(ThreadLocalRandom.class)) {
      mocked.when(ThreadLocalRandom::current).thenReturn(random);
      when(random.nextLong())
          .thenReturn(0x0123456789ABCDEFL, 0xFEDCBA9876543210L, 0x0123456789ABCDEFL);
      assertThat(TraceIds.nextTraceId()).isEqualTo("0123456789abcdeffedcba9876543210");
      assertThat(TraceIds.nextSpanId()).isEqualTo("0123456789abcdef");
    }
  }
}
