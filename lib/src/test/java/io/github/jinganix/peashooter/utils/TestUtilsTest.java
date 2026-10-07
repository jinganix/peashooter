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

package io.github.jinganix.peashooter.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TestUtils")
class TestUtilsTest {

  @Test
  @DisplayName("should restore interrupt flag when sleep is interrupted")
  void shouldRestoreInterruptFlagWhenSleepIsInterrupted() {
    // Given the current thread is already interrupted
    Thread.currentThread().interrupt();

    try {
      // When / Then the interrupt status must survive the wrap
      assertThatThrownBy(() -> TestUtils.sleep(10_000))
          .isInstanceOf(RuntimeException.class)
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      // Cleanup so the test worker thread is reusable
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("should wait the full duration and return it")
  void shouldWaitTheFullDurationAndReturnIt() {
    // When
    long startAt = System.nanoTime();
    long waited = TestUtils.sleep(200);

    // Then only a lower bound is asserted: CI load can only stretch the wait, never shrink it
    assertThat(waited).isEqualTo(200);
    assertThat(System.nanoTime() - startAt).isGreaterThanOrEqualTo(200_000_000L);
  }

  @Test
  @DisplayName("should restore interrupt flag when uncheckedRun wraps interruption")
  void shouldRestoreInterruptFlagWhenUncheckedRunWrapsInterruption() {
    // Given a throwing task interrupted mid-wait (sleep throws and clears the flag)
    Thread.currentThread().interrupt();

    try {
      // When / Then the bridge must restore the flag instead of dropping it with the wrap
      assertThatThrownBy(() -> TestUtils.uncheckedRun(() -> Thread.sleep(10_000)))
          .isInstanceOf(RuntimeException.class)
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      // Cleanup so the test worker thread is reusable
      Thread.interrupted();
    }
  }
}
