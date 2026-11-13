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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Review round2 regressions")
class ReviewRound2RegressionTest {

  @Test
  @DisplayName("should leave no ThreadLocal entry when DepthGuard overflows")
  void shouldLeaveNoThreadLocalEntryWhenDepthGuardOverflows() {
    DepthGuard guard = new DepthGuard();
    for (int i = 0; i < 3; i++) {
      guard.enter(1, () -> new IllegalStateException("overflow"));
      try {
        guard.enter(1, () -> new IllegalStateException("overflow"));
      } catch (IllegalStateException expected) {
        // expected
      }
      // Outer level still exits cleanly; a stranded overflow entry would corrupt depth here.
      guard.exit();
      assertThat(guard.depth()).isZero();
    }
  }

  @Test
  @DisplayName("should reject a multi-key call when distinct keys exceed the bound")
  void shouldRejectMultiKeyWhenDistinctKeysExceedBound() {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < MultiKeyNesting.MAX_MULTI_KEYS + 1; i++) {
      keys.add("k-" + i);
    }
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(keys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too many distinct keys");
  }

  @Test
  @DisplayName("should accept duplicate keys when the distinct count collapses within the bound")
  void shouldAcceptDuplicateKeysWhenDistinctCollapseWithinBound() {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < MultiKeyNesting.MAX_MULTI_KEYS + 500; i++) {
      keys.add("same");
    }
    assertThat(MultiKeyNesting.lockKeys(keys)).containsExactly("same");
  }

  @Test
  @DisplayName("should fail fast when an oversized distinct multi-key arrives")
  void shouldFailFastWhenOversizedDistinctMultiKey() {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < MultiKeyNesting.MAX_MULTI_KEYS + 100; i++) {
      keys.add("k-" + i);
    }
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(keys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too many distinct keys");
  }
}
