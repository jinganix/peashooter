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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MultiKeyNesting bound")
class MultiKeyNestingBoundTest {

  @Test
  @DisplayName("should reject when the collection grows past the bound during copy")
  void shouldRejectWhenCollectionGrowsPastBoundDuringCopy() {
    List<String> base = new ArrayList<>();
    for (int i = 0; i < MultiKeyNesting.MAX_MULTI_KEYS; i++) {
      base.add("k-" + i);
    }
    // Lies about size() then yields one extra element: fast path must still bound.
    AbstractCollection<String> lying =
        new AbstractCollection<>() {
          @Override
          public Iterator<String> iterator() {
            List<String> grown = new ArrayList<>(base);
            grown.add("k-overflow");
            return grown.iterator();
          }

          @Override
          public int size() {
            return base.size();
          }
        };
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(lying))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too many distinct keys");
  }

  @Test
  @DisplayName("should reject a collection that reports oversize but yields nothing")
  void shouldRejectOversizeReportWithNoElements() {
    AbstractCollection<String> misreporting =
        new AbstractCollection<>() {
          @Override
          public Iterator<String> iterator() {
            return List.<String>of().iterator();
          }

          @Override
          public int size() {
            return MultiKeyNesting.MAX_MULTI_KEYS + 1;
          }
        };
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(misreporting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keys must not be empty");
  }

  @Test
  @DisplayName("should reject oversized keys on both paths")
  void shouldRejectOversizedKeysOnBothPaths() {
    String oversized = "k".repeat(io.github.jinganix.peashooter.internal.Keys.MAX_KEY_CHARS + 1);
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(List.of("a", oversized)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too long");
    List<String> many = new ArrayList<>();
    many.add(oversized);
    for (int i = 0; i < 1100; i++) {
      many.add("k-" + i);
    }
    assertThatThrownBy(() -> MultiKeyNesting.lockKeys(many))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too long");
  }
}
