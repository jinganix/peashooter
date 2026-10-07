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

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Channel 1 (inline 32) and Channel 2 (reentrant 256) share one thread; combined needs one bound.
 */
@DisplayName("Nested inline depth limit")
class NestedInlineDepthLimitTest {

  @Test
  @DisplayName(
      "should fail fast when inline nesting exceeds the unified budget instead of overflowing the"
          + " stack")
  void shouldFailFastWhenInlineNestingExceedsUnifiedBudgetInsteadOfStackOverflow() {
    // Worst case is the sum on one thread: inline 32 + reentrant 256 = 288 logical levels.
    assertThat(DirectExecutor.MAX_INLINE_DEPTH + ReentrancyGate.MAX_REENTRANT_DEPTH).isEqualTo(288);

    AtomicInteger maxObserved = new AtomicInteger(0);
    Runnable recursive =
        new Runnable() {
          @Override
          public void run() {
            int d = DirectExecutor.depth();
            maxObserved.accumulateAndGet(d, Math::max);
            if (d < DirectExecutor.MAX_INLINE_DEPTH + ReentrancyGate.MAX_REENTRANT_DEPTH + 100) {
              DirectExecutor.INSTANCE.execute(this);
            }
          }
        };
    assertThatThrownBy(() -> DirectExecutor.INSTANCE.execute(recursive))
        .isNotInstanceOf(StackOverflowError.class)
        .isInstanceOf(IllegalStateException.class);
    assertThat(maxObserved.get()).isLessThanOrEqualTo(DirectExecutor.MAX_INLINE_DEPTH);
  }
}
