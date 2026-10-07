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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Lockable retry discard cap")
class LockableRetryDiscardCapTest {

  static final class NeverLockQueue extends LockableTaskQueue {
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

  static final class RejectAll implements Executor {
    final AtomicInteger attempts = new AtomicInteger();
    volatile boolean rejecting;

    @Override
    public void execute(Runnable command) {
      attempts.incrementAndGet();
      if (rejecting) {
        throw new RejectedExecutionException("saturated");
      }
      command.run();
    }
  }

  @Test
  @DisplayName("single timer tick discards at most the cap and schedules a follow-up")
  @SuppressWarnings({"unchecked", "rawtypes"})
  void capsDiscardsPerTick() {
    List<Runnable> retries = new ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              retries.add(inv.getArgument(0));
              ScheduledFuture f = mock(ScheduledFuture.class);
              // Undone, so the scheduled claim is live: the follow-up scheduled from the tick must
              // coalesce onto it instead of relying on the done-claim reclaim path.
              when(f.isDone()).thenReturn(false);
              return f;
            });
    NeverLockQueue queue = new NeverLockQueue(new ExecutionCountStats(), rescheduler);
    RejectAll rejecting = new RejectAll();
    int total = LockableTaskQueue.MAX_HEAD_DISCARD_PER_RETRY + 5;
    for (int i = 0; i < total; i++) {
      queue.execute(rejecting, () -> {});
    }
    // Flip to saturated only for timer ticks: enqueues above ran inline and left the backlog
    // queued behind the failed tryLock retry.
    rejecting.rejecting = true;
    // First submit runs inline and drains the cap; the remainder waits for timer ticks.
    assertThat(retries).isNotEmpty();
    Runnable firstTick = retries.get(0);
    int attemptsBefore = rejecting.attempts.get();
    firstTick.run();
    int attemptsThisTick = rejecting.attempts.get() - attemptsBefore;
    assertThat(attemptsThisTick)
        .isLessThanOrEqualTo(LockableTaskQueue.MAX_HEAD_DISCARD_PER_RETRY + 1);
    // Follow-up scheduled for the remainder instead of draining unbounded on this tick.
    assertThat(retries.size()).isGreaterThan(1);
  }
}
