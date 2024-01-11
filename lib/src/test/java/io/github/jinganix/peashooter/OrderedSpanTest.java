/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * https://github.com/jinganix/peashooter
 */

package io.github.jinganix.peashooter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("OrderedSpan")
class OrderedSpanTest {

  @Nested
  @DisplayName("invokedBy")
  class InvokedBy {

    @Nested
    @DisplayName("when span is null")
    class WhenSpanIsNull {

      @Test
      @DisplayName("then return false")
      void thenReturnFalse() {
        assertThat(OrderedSpan.invokedBy(null, "")).isFalse();
      }
    }

    @Nested
    @DisplayName("when span is OrderedSpan")
    class WhenSpanIsOrderedSpan {

      @Nested
      @DisplayName("when is sync and key equals")
      class WhenIsSyncAndKeyEquals {

        @Test
        @DisplayName("then return true")
        void thenReturnTrue() {
          OrderedSpan orderedSpan = new OrderedSpan(new DefaultTracer(), null, "key", true);
          assertThat(OrderedSpan.invokedBy(orderedSpan, "key")).isTrue();
        }
      }

      @Nested
      @DisplayName("when is sync but key not equal")
      class WhenIsSyncButKeyNotEqual {

        @Test
        @DisplayName("then return false")
        void thenReturnTrue() {
          OrderedSpan orderedSpan = new OrderedSpan(new DefaultTracer(), null, "key", true);
          assertThat(OrderedSpan.invokedBy(orderedSpan, "key1")).isFalse();
        }
      }

      @Nested
      @DisplayName("when key equals but not sync")
      class WhenKeyEqualsButNotSync {

        @Test
        @DisplayName("then return false")
        void thenReturnTrue() {
          OrderedSpan orderedSpan = new OrderedSpan(new DefaultTracer(), null, "key", false);
          assertThat(OrderedSpan.invokedBy(orderedSpan, "key")).isFalse();
        }
      }
    }
  }
}
