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

package io.github.jinganix.peashooter.executor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SyncWait deadline saturation")
class SyncWaitDeadlineTest {

  @Test
  @DisplayName("should saturate on positive overflow")
  void shouldSaturateOnPositiveOverflow() {
    assertThat(SyncWait.deadlineOf(Long.MAX_VALUE - 10, 20)).isEqualTo(Long.MAX_VALUE);
    assertThat(SyncWait.deadlineOf(2L, Long.MAX_VALUE - 1)).isEqualTo(Long.MAX_VALUE);
    // Exact fit does not overflow.
    assertThat(SyncWait.deadlineOf(0L, Long.MAX_VALUE - 1)).isEqualTo(Long.MAX_VALUE - 1);
    assertThat(SyncWait.deadlineOf(1L, Long.MAX_VALUE - 1)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName("should not saturate for a negative now without overflow")
  void shouldNotSaturateForNegativeNowWithoutOverflow() {
    // MIN + (MAX-1) = -2 fits in long: no overflow, must stay finite.
    assertThat(SyncWait.deadlineOf(Long.MIN_VALUE, Long.MAX_VALUE - 1)).isEqualTo(-2L);
    assertThat(SyncWait.deadlineOf(-1000L, 500L)).isEqualTo(-500L);
  }

  @Test
  @DisplayName("should preserve the wait-forever sentinel")
  void shouldPreserveWaitForeverSentinel() {
    assertThat(SyncWait.deadlineOf(123L, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
  }
}
