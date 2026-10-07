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

package io.github.jinganix.peashooter.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExecutionCountStats")
class ExecutionCountStatsTest {

  @Test
  @DisplayName("should count records until reset")
  void shouldCountRecordsUntilReset() {
    // Given a fresh counter
    ExecutionCountStats stats = new ExecutionCountStats();
    assertThat(stats.getExecutionCount()).isZero();

    // When tasks are recorded Then the count grows and reset clears it
    stats.record();
    stats.record();
    assertThat(stats.getExecutionCount()).isEqualTo(2);
    stats.reset();
    assertThat(stats.getExecutionCount()).isZero();
  }

  @Test
  @DisplayName("should saturate at max value instead of wrapping")
  void shouldSaturateAtMaxValueInsteadOfWrapping() throws Exception {
    // Given a counter one below saturation (set reflectively: recording
    // Integer.MAX_VALUE times is infeasible)
    ExecutionCountStats stats = new ExecutionCountStats();
    Field field = ExecutionCountStats.class.getDeclaredField("executionCount");
    field.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicInteger) field.get(stats)).set(Integer.MAX_VALUE - 1);

    // When recording past the maximum Then the count pins instead of wrapping negative
    stats.record();
    assertThat(stats.getExecutionCount()).isEqualTo(Integer.MAX_VALUE);
    stats.record();
    assertThat(stats.getExecutionCount()).isEqualTo(Integer.MAX_VALUE);
  }
}
