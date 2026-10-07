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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Lockable interrupt")
class LockableInterruptTest {

  static final class AlwaysLockQueue extends LockableTaskQueue {
    AlwaysLockQueue(ExecutionStats stats, ScheduledExecutorService scheduler) {
      super(stats, scheduler);
    }

    @Override
    protected boolean tryLock(ExecutionStats stats) {
      return true;
    }

    @Override
    protected boolean shouldYield(ExecutionStats stats) {
      return false;
    }

    @Override
    protected void unlock() {}
  }

  static final class ContendedLockQueue extends LockableTaskQueue {
    private final AtomicBoolean lockOpen;

    ContendedLockQueue(
        ExecutionStats stats, ScheduledExecutorService scheduler, AtomicBoolean lockOpen) {
      super(stats, scheduler);
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
  @DisplayName("should restore interrupt and end batch when locked task is interrupted")
  void shouldRestoreInterruptAndEndBatchWhenLockedTaskIsInterrupted() throws Exception {
    // Given a locked queue with a parked runner and an interrupt task followed by a marker
    ScheduledExecutorService scheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setDaemon(true);
              return thread;
            });
    try {
      AlwaysLockQueue queue = new AlwaysLockQueue(new ExecutionCountStats(), scheduler);
      AtomicReference<Thread> runnerThread = new AtomicReference<>();
      Executor capturingAsync =
          command -> {
            Thread thread = new Thread(command);
            thread.setDaemon(true);
            runnerThread.set(thread);
            thread.start();
          };
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch markerDone = new CountDownLatch(1);
      AtomicBoolean markerRan = new AtomicBoolean();
      AtomicReference<Thread> markerThread = new AtomicReference<>();
      queue.execute(
          capturingAsync,
          () -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new RuntimeException(e);
            }
          });
      if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new IllegalStateException("runner did not start");
      }
      queue.execute(
          capturingAsync,
          () -> {
            throw smuggle(new InterruptedException("locked interrupt"));
          });
      queue.execute(
          capturingAsync,
          () -> {
            markerThread.set(Thread.currentThread());
            markerRan.set(true);
            markerDone.countDown();
          });

      Thread initialRunner = runnerThread.get();
      // When the batch hits the interrupt
      release.countDown();
      if (initialRunner != null) {
        initialRunner.join(5000);
      }

      // Then the interrupted runner keeps its flag instead of swallowing it
      assertThat(initialRunner.isInterrupted()).isTrue();
      // And the batch ends on that thread: the marker reschedules onto a fresh thread
      // instead of continuing inline under interruption, with the backlog preserved
      if (!markerDone.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new IllegalStateException("marker was not rescheduled");
      }
      assertThat(markerRan.get()).isTrue();
      assertThat(markerThread.get()).isNotSameAs(initialRunner);
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  @DisplayName("should reschedule backlog when interrupted runner does not own the contended lock")
  @SuppressWarnings({"unchecked", "rawtypes"})
  void shouldRescheduleWhenInterruptedNonOwningRunner() throws Exception {
    // Given a queue whose external lock is held elsewhere and a runner thread already interrupted
    List<Runnable> retries = new ArrayList<>();
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              retries.add(inv.getArgument(0));
              ScheduledFuture f = mock(ScheduledFuture.class);
              when(f.isDone()).thenReturn(false);
              return f;
            });
    AtomicBoolean lockOpen = new AtomicBoolean(false);
    ContendedLockQueue queue =
        new ContendedLockQueue(new ExecutionCountStats(), scheduler, lockOpen);
    AtomicInteger executed = new AtomicInteger();

    // When a task is submitted inline on an already-interrupted thread and the lock is contended
    Thread runner =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              queue.execute(DirectExecutor.INSTANCE, executed::incrementAndGet);
            });
    runner.start();
    runner.join(5000);

    // Then the backlog is not stranded: the interrupted non-owning runner still schedules a retry
    assertThat(retries).hasSize(1);

    // And draining that retry runs the preserved task once the lock opens
    lockOpen.set(true);
    retries.remove(0).run();
    assertThat(executed.get()).isEqualTo(1);
    assertThat(queue.isIdle()).isTrue();
  }

  // Test-only smuggling of a checked interrupt past Runnable; production wraps instead.
  private static <E extends Throwable> RuntimeException smuggle(Throwable failure) throws E {
    throw (E) failure;
  }
}
