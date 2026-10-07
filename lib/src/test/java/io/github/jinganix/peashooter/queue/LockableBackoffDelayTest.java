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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Lockable backoff delay")
class LockableBackoffDelayTest {

  static class NeverLockQueue extends LockableTaskQueue {
    NeverLockQueue(ExecutionStats stats, ScheduledExecutorService rescheduler) {
      super(stats, rescheduler);
    }

    @Override
    protected boolean tryLock(ExecutionStats stats) {
      return false;
    }

    @Override
    protected boolean shouldYield(ExecutionStats stats) {
      return false;
    }

    @Override
    protected void unlock() {}
  }

  @Test
  @DisplayName("should use exponential backoff with jitter, not fixed 1ms")
  @SuppressWarnings({"unchecked", "rawtypes"})
  void shouldUseExponentialBackoffWithJitter() {
    List<Long> delays = new ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              delays.add((Long) inv.getArgument(1));
              ScheduledFuture f = mock(ScheduledFuture.class);
              when(f.isDone()).thenReturn(true);
              return f;
            });
    NeverLockQueue queue = new NeverLockQueue(new ExecutionCountStats(), rescheduler);
    for (int i = 0; i < 20; i++) {
      queue.execute(DirectExecutor.INSTANCE, () -> {});
    }
    assertThat(delays).isNotEmpty();
    // New contract: delays must be jittered/bounded with exponential growth, not fixed 1ms.
    assertThat(delays).allSatisfy(d -> assertThat(d).isBetween(1L, 100L));
    assertThat(delays.stream().distinct().count())
        .as("expected jittered delays, got all=%s", delays)
        .isGreaterThan(1);
    assertThat(delays.stream().mapToLong(Long::longValue).max().orElse(0))
        .as("expected backoff growth, got %s", delays)
        .isGreaterThan(1);
  }
}
