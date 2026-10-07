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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MultiKeyNesting")
class MultiKeyNestingTest {

  @Test
  @DisplayName("should sort and deduplicate keys for deadlock-free acquisition")
  void shouldSortAndDeduplicateKeysForDeadlockFreeAcquisition() {
    assertThat(MultiKeyNesting.lockKeys(List.of("b", "a", "b", "c")))
        .containsExactly("a", "b", "c");
    assertThat(MultiKeyNesting.lockKeys(List.of("only"))).containsExactly("only");
    assertThat(MultiKeyNesting.lockKeys(List.of("b", "a"))).containsExactly("a", "b");
  }

  @Test
  @DisplayName("should reject null blank and empty key collections")
  void shouldRejectNullBlankAndEmptyKeyCollections() {
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(List.of("a", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keys[1]");
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(java.util.Arrays.asList("a", null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("keys[1]");
  }
}
