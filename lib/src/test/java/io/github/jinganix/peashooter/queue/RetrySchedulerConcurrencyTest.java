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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Retry scheduler concurrency")
class RetrySchedulerConcurrencyTest {

  private Object readClaim(RetryScheduler scheduler) throws Exception {
    Field f = RetryScheduler.class.getDeclaredField("rescheduleFuture");
    f.setAccessible(true);
    AtomicReference<?> ref = (AtomicReference<?>) f.get(scheduler);
    return ref.get();
  }

  private void writeClaim(RetryScheduler scheduler, Object value) throws Exception {
    Field f = RetryScheduler.class.getDeclaredField("rescheduleFuture");
    f.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<Object> ref = (AtomicReference<Object>) f.get(scheduler);
    ref.set(value);
  }

  private int readAttempts(RetryScheduler scheduler) throws Exception {
    Field f = RetryScheduler.class.getDeclaredField("backoffAttempts");
    f.setAccessible(true);
    java.util.concurrent.atomic.AtomicInteger attempts =
        (java.util.concurrent.atomic.AtomicInteger) f.get(scheduler);
    return attempts.get();
  }

  /** Builds a claim of the private claim type holding {@code future}, without firing it. */
  private Object newClaim(ScheduledFuture<?> future) throws Exception {
    Class<?> type = Class.forName("io.github.jinganix.peashooter.queue.RetryScheduler$RetryClaim");
    java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object claim = constructor.newInstance();
    Field f = type.getDeclaredField("future");
    f.setAccessible(true);
    f.set(claim, future);
    return claim;
  }

  @Test
  @DisplayName("should clear only its own claim when the fired retry starts")
  void shouldPreserveConcurrentReplacementWhenFiredRetryClearsClaim() throws Exception {
    List<Runnable> timers = new ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              timers.add(inv.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    RetryScheduler scheduler = new RetryScheduler(rescheduler, () -> 0L);
    scheduler.scheduleRetry(() -> {});
    assertThat(timers).hasSize(1);

    // Simulate a concurrent replacement claim installed for the same slot.
    Object replacement = newClaim(mock(ScheduledFuture.class));
    writeClaim(scheduler, replacement);

    // When the first timer fires, it must clear only its own claim, not the replacement.
    timers.get(0).run();

    assertThat(readClaim(scheduler)).isSameAs(replacement);
  }

  @Test
  @DisplayName("should increment backoff only when a retry is actually scheduled")
  void shouldIncrementBackoffOnlyWhenRetryActuallyScheduled() throws Exception {
    List<Runnable> timers = new ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              timers.add(inv.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    RetryScheduler scheduler =
        new RetryScheduler(rescheduler, java.util.concurrent.ThreadLocalRandom.current()::nextLong);

    scheduler.scheduleRetry(() -> {});
    assertThat(readAttempts(scheduler)).isEqualTo(1);

    // Coalesced: a pending retry exists, so no new timer and no counter growth.
    scheduler.scheduleRetry(() -> {});
    assertThat(timers).hasSize(1);
    assertThat(readAttempts(scheduler)).isEqualTo(1);
  }

  @Test
  @DisplayName("should coalesce onto a reserved retry without bumping backoff")
  void shouldCoalesceOntoReservedRetryWithoutBumpingBackoff() throws Exception {
    // Given a first caller blocked inside schedule() with its claim already reserved
    java.util.concurrent.CountDownLatch insideSchedule = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseSchedule =
        new java.util.concurrent.CountDownLatch(1);
    List<Runnable> timers = new ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              timers.add(inv.getArgument(0));
              insideSchedule.countDown();
              releaseSchedule.await();
              return mock(ScheduledFuture.class);
            });
    RetryScheduler scheduler = new RetryScheduler(rescheduler, () -> 0L);

    Thread first = new Thread(() -> scheduler.scheduleRetry(() -> {}));
    first.setDaemon(true);
    first.start();
    assertThat(insideSchedule.await(5, TimeUnit.SECONDS)).isTrue();

    // When a second request arrives while the first claim is reserved
    scheduler.scheduleRetry(() -> {});

    // Then it coalesces onto the reserved claim: no extra timer and no extra backoff bump
    assertThat(readAttempts(scheduler)).isEqualTo(1);
    releaseSchedule.countDown();
    first.join(5000);
    assertThat(timers).hasSize(1);
  }

  @Test
  @DisplayName("should keep a reserved follow-up live while an earlier retry is still running")
  void shouldNotCoalesceFollowUpOntoRunningRetry() throws Exception {
    List<Runnable> timers = new ArrayList<>();
    List<ScheduledFuture<?>> futures = new ArrayList<>();
    CountDownLatch retryStarted = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    CountDownLatch bodyDone = new CountDownLatch(1);
    AtomicBoolean followUpLive = new AtomicBoolean();
    AtomicInteger timersAfterFollowUp = new AtomicInteger();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              Runnable clearing = inv.getArgument(0);
              timers.add(clearing);
              ScheduledFuture<?> f = mock(ScheduledFuture.class);
              when(f.isDone()).thenReturn(false);
              futures.add(f);
              if (timers.size() == 1) {
                // Run the first retry on a separate thread before schedule() returns, so the
                // caller publishes the future while the retry is already in flight.
                Thread t = new Thread(clearing, "retry-1");
                t.setDaemon(true);
                t.start();
                retryStarted.await();
              }
              return f;
            });
    RetryScheduler scheduler = new RetryScheduler(rescheduler, () -> 0L);

    Runnable retry1 =
        () -> {
          retryStarted.countDown();
          awaitQuietly(proceed);
          // A follow-up request arrives while this retry is still running: it must reserve a new
          // timer instead of being absorbed by the in-flight one.
          scheduler.scheduleRetry(() -> {});
          followUpLive.set(scheduler.hasPendingRetry());
          timersAfterFollowUp.set(timers.size());
          // And a second request must coalesce onto that follow-up, not pile a third timer.
          scheduler.scheduleRetry(() -> {});
          bodyDone.countDown();
        };
    scheduler.scheduleRetry(retry1);
    proceed.countDown();
    awaitQuietly(bodyDone);

    // Then the running retry never absorbs or cancels the follow-up that keeps the backlog alive.
    assertThat(followUpLive).isTrue();
    assertThat(timersAfterFollowUp).hasValue(2);
    assertThat(timers).hasSize(2);
    verify(futures.get(1), never()).cancel(anyBoolean());
  }

  @Test
  @DisplayName("should treat a claim as lost when the scheduler dies before the timer fires")
  void shouldTreatClaimAsLostWhenSchedulerDiesBeforeTimerFires() throws Exception {
    // Given a scheduler whose only worker is parked, so a reserved retry cannot fire
    ScheduledThreadPoolExecutor rescheduler = new ScheduledThreadPoolExecutor(1);
    CountDownLatch workerBusy = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    try {
      rescheduler.execute(
          () -> {
            workerBusy.countDown();
            awaitQuietly(releaseWorker);
          });
      assertThat(workerBusy.await(5, TimeUnit.SECONDS)).isTrue();
      RetryScheduler scheduler = new RetryScheduler(rescheduler, () -> 0L);
      AtomicBoolean retryRan = new AtomicBoolean();
      scheduler.scheduleRetry(() -> retryRan.set(true));
      assertThat(scheduler.hasPendingRetry()).isTrue();

      // When the scheduler is shut down, draining the queued timer without completing its future
      rescheduler.shutdownNow();
      assertThat(rescheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

      // Then the dead claim is recognised instead of blocking the queue forever
      assertThat(retryRan).isFalse();
      assertThat(scheduler.hasPendingRetry()).isFalse();
      assertThat(scheduler.reclaimDoneRetry()).isTrue();
    } finally {
      releaseWorker.countDown();
      rescheduler.shutdownNow();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for latch");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
