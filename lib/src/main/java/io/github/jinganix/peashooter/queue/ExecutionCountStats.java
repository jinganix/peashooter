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

import io.github.jinganix.peashooter.ExecutionStats;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stat execution count.
 *
 * <p>Confined to one queue batch at a time: the owning runner calls {@code reset} once after
 * acquiring the lock, then {@code record} after each task in the same batch. No concurrent {@code
 * record} runs alongside {@code reset}. The atomic count also keeps cross-thread handoffs visible
 * without relying on the backing executor for happens-before, and concurrent {@code record} calls
 * never lose an increment.
 */
public class ExecutionCountStats implements ExecutionStats {

  private final AtomicInteger executionCount = new AtomicInteger();

  /** Constructor. */
  public ExecutionCountStats() {}

  @Override
  public void reset() {
    executionCount.set(0);
  }

  @Override
  public void record() {
    // Zero-allocation saturating increment: hand-rolled get/CAS loop instead of
    // updateAndGet (which allocates its lambda on every call on this hot path).
    int current;
    do {
      current = executionCount.get();
      if (current == Integer.MAX_VALUE) {
        return;
      }
    } while (!executionCount.compareAndSet(current, current + 1));
  }

  /**
   * Get execution count after start or last yield.
   *
   * <p>Count saturates at {@link Integer#MAX_VALUE} instead of wrapping; yield policies must test
   * {@code getExecutionCount() >= N}, never {@code == N}.
   *
   * @return execution count
   */
  @Override
  public int getExecutionCount() {
    return executionCount.get();
  }
}
