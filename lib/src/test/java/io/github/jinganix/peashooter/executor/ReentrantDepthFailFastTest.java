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

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Reentrant depth fail-fast")
class ReentrantDepthFailFastTest {

  @Test
  @DisplayName("should fail fast instead of StackOverflow on deep same-key recursion")
  void shouldFailFastOnDeepRecursion() throws Exception {
    var pool = Executors.newFixedThreadPool(4);
    try {
      OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
      AtomicInteger depth = new AtomicInteger(0);
      int target = 1000;
      java.util.function.Supplier<Integer> recursive =
          new java.util.function.Supplier<>() {
            @Override
            public Integer get() {
              int d = depth.incrementAndGet();
              if (d >= target) {
                return d;
              }
              return executor.supply("k", this);
            }
          };
      assertThatThrownBy(() -> executor.supply("k", recursive))
          .isNotInstanceOf(StackOverflowError.class);
    } finally {
      pool.shutdownNow();
    }
  }
}
