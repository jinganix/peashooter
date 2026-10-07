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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Lockable timer inline budget")
class LockableTimerInlineBudgetTest {

  static final class FlakyLockQueue extends LockableTaskQueue {
    final AtomicBoolean lockOpen;

    FlakyLockQueue(
        ExecutionStats stats, ScheduledExecutorService rescheduler, AtomicBoolean lockOpen) {
      super(stats, rescheduler);
      this.lockOpen = lockOpen;
    }

    @Override
    protected boolean tryLock(ExecutionStats stats) {
      return lockOpen.get();
    }

    @Override
    protected boolean shouldYield(ExecutionStats stats) {
      return false;
    }

    @Override
    protected void unlock() {}
  }

  @Test
  @DisplayName("timer tick caps inline drain and reschedules the remainder without dropping it")
  void capsInlineDrainAndPreservesRemainder() {
    List<Runnable> retries = new ArrayList<>();
    ScheduledExecutorService rescheduler = capturingRescheduler(retries);
    AtomicBoolean lockOpen = new AtomicBoolean(false);
    FlakyLockQueue queue = new FlakyLockQueue(new ExecutionCountStats(), rescheduler, lockOpen);
    AtomicInteger executed = new AtomicInteger();
    int total = LockableTaskQueue.MAX_TASKS_PER_TIMER_RETRY + 6;
    for (int i = 0; i < total; i++) {
      queue.execute(DirectExecutor.INSTANCE, executed::incrementAndGet);
    }
    assertThat(retries).hasSize(1);

    // Open the lock so the timer tick drains; it must stop at the budget.
    lockOpen.set(true);
    Runnable firstTick = retries.remove(0);
    firstTick.run();

    // The first tick runs exactly the budget and preserves the queued remainder...
    assertThat(executed.get()).isEqualTo(LockableTaskQueue.MAX_TASKS_PER_TIMER_RETRY);
    assertThat(queue.hasPending()).isTrue();
    // ...scheduling a follow-up tick for it instead of draining inline or dropping it.
    assertThat(retries).hasSize(1);

    // Draining the follow-up ticks runs every submitted task exactly once.
    int guard = 0;
    while (!retries.isEmpty() && guard++ < 100) {
      retries.remove(0).run();
    }
    assertThat(executed.get()).isEqualTo(total);
    assertThat(queue.hasPending()).isFalse();
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("timer tick bounds a synchronous non-DirectExecutor head inline")
  void boundsSynchronousNonDirectExecutorHead() {
    // A synchronous executor that is not exactly DirectExecutor (e.g. Runnable::run, or a
    // TraceExecutor wrapping a direct delegate) must still be bounded on the timer thread.
    Executor synchronous = Runnable::run;
    List<Runnable> retries = new ArrayList<>();
    ScheduledExecutorService rescheduler = capturingRescheduler(retries);
    AtomicBoolean lockOpen = new AtomicBoolean(false);
    FlakyLockQueue queue = new FlakyLockQueue(new ExecutionCountStats(), rescheduler, lockOpen);
    AtomicInteger executed = new AtomicInteger();
    int total = LockableTaskQueue.MAX_TASKS_PER_TIMER_RETRY + 6;
    for (int i = 0; i < total; i++) {
      queue.execute(synchronous, executed::incrementAndGet);
    }
    assertThat(retries).hasSize(1);

    lockOpen.set(true);
    Runnable firstTick = retries.remove(0);
    firstTick.run();

    assertThat(executed.get()).isEqualTo(LockableTaskQueue.MAX_TASKS_PER_TIMER_RETRY);
    assertThat(queue.hasPending()).isTrue();
    assertThat(retries).hasSize(1);
  }

  @Test
  @DisplayName("fatal task Error on the timer retry propagates instead of being swallowed")
  void fatalTaskErrorOnTimerRetryPropagates() {
    List<Runnable> retries = new ArrayList<>();
    ScheduledExecutorService rescheduler = capturingRescheduler(retries);
    AtomicBoolean lockOpen = new AtomicBoolean(false);
    FlakyLockQueue queue = new FlakyLockQueue(new ExecutionCountStats(), rescheduler, lockOpen);
    AtomicInteger executed = new AtomicInteger();
    queue.execute(
        DirectExecutor.INSTANCE,
        () -> {
          throw new AssertionError("fatal");
        });
    queue.execute(DirectExecutor.INSTANCE, executed::incrementAndGet);
    assertThat(retries).hasSize(1);

    lockOpen.set(true);
    Runnable tick = retries.remove(0);

    // The fatal Error is not converted into a head rejection: it stays loud, and the surviving
    // task is not auto-drained after a compromised-JVM failure.
    assertThatThrownBy(tick::run).isInstanceOf(AssertionError.class);
    assertThat(executed.get()).isZero();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ScheduledExecutorService capturingRescheduler(List<Runnable> retries) {
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              retries.add(inv.getArgument(0));
              ScheduledFuture f = mock(ScheduledFuture.class);
              // Undone, so a scheduled retry coalesces subsequent requests: exactly one
              // outstanding retry after the initial submissions.
              when(f.isDone()).thenReturn(false);
              return f;
            });
    return rescheduler;
  }
}
