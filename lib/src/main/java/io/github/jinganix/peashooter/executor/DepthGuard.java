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

import java.util.function.Supplier;

/**
 * Per-thread nesting depth guard shared by inline execution channels.
 *
 * <p>Single owner of the {@code ThreadLocal<int[1]>} pattern: an {@code int[1]} holder avoids
 * {@link Integer} boxing on hot paths, is removed when depth returns to zero so pooled threads
 * retain nothing, and a plain {@link ThreadLocal} (never {@code withInitial}) materializes no entry
 * for threads that only probe depth.
 */
final class DepthGuard {

  private final ThreadLocal<int[]> depth = new ThreadLocal<>();

  /** Current thread's depth; never materializes an entry for idle threads. */
  int depth() {
    int[] holder = depth.get();
    return holder == null ? 0 : holder[0];
  }

  /**
   * Enters one nesting level, failing fast when the budget is exceeded.
   *
   * @param maxDepth maximum depth before overflow
   * @param overflow failure supplier when already at the budget
   */
  void enter(int maxDepth, Supplier<? extends RuntimeException> overflow) {
    int[] holder = depth.get();
    if (holder == null) {
      // Overflow check before materializing: throwing after set would strand an int[1] on a
      // pooled thread with no matching exit to remove it, contradicting the no-entry-for-idle
      // contract.
      if (0 >= maxDepth) {
        throw overflow.get();
      }
      holder = new int[1];
      depth.set(holder);
    } else if (holder[0] >= maxDepth) {
      throw overflow.get();
    }
    holder[0]++;
  }

  /**
   * Exits one nesting level, removing the entry when depth returns to zero.
   *
   * @throws IllegalStateException on an unpaired exit with no matching enter
   */
  void exit() {
    int[] holder = depth.get();
    if (holder == null || holder[0] <= 0) {
      throw new IllegalStateException("Unpaired DepthGuard exit without matching enter");
    }
    int next = holder[0] - 1;
    if (next <= 0) {
      depth.remove();
    } else {
      holder[0] = next;
    }
  }
}
