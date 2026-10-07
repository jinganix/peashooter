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

import static io.github.jinganix.peashooter.utils.TestUtils.awaitCountDown;
import static io.github.jinganix.peashooter.utils.TestUtils.sleep;
import static io.github.jinganix.peashooter.utils.TestUtils.uncheckedRun;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Answers;

@DisplayName("LockableTaskQueue")
class LockableTaskQueueTest {

  /** Caller-managed retry scheduler shared by queues under test; shut down after the suite. */
  private static ScheduledExecutorService sharedTestScheduler;

  @BeforeAll
  static void startSharedTestScheduler() {
    sharedTestScheduler = newSingleThreadTestScheduler();
  }

  @AfterAll
  static void stopSharedTestScheduler() {
    sharedTestScheduler.shutdownNow();
  }

  private static ScheduledExecutorService newSingleThreadTestScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable);
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * Mock queue with real methods and an explicit caller-managed scheduler. {@code
   * mockWithSharedScheduler()} cannot be used: instantiating without the constructor leaves the
   * retry scheduler unset.
   */
  private static LockableTaskQueue mockWithScheduler(ScheduledExecutorService scheduler) {
    return mock(
        LockableTaskQueue.class,
        withSettings()
            .useConstructor(new ExecutionCountStats(), scheduler)
            .defaultAnswer(Answers.CALLS_REAL_METHODS));
  }

  private static LockableTaskQueue mockWithSharedScheduler() {
    return mockWithScheduler(sharedTestScheduler);
  }

  /**
   * Waits for worker threads to actually terminate instead of a blind {@code join(timeout)}: an
   * unasserted join lets a starved thread outlive the wait and fail a later state assertion far
   * from the cause. A genuine stall still fails here via the timeout.
   */
  private static void awaitDeath(Thread... threads) {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> Arrays.stream(threads).noneMatch(Thread::isAlive));
  }

  @Test
  @DisplayName("should decline runner claim while another runner or unlock is active")
  void shouldDeclineRunnerClaimWhileAnotherRunnerOrUnlockIsActive() throws InterruptedException {
    // Given a queue with its runner parked inside a blocking executor
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    CountDownLatch runnerEntered = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    AtomicInteger runnerEntries = new AtomicInteger();
    Executor blocking =
        command -> {
          runnerEntries.incrementAndGet();
          runnerEntered.countDown();
          uncheckedRun(releaseRunner::await);
          command.run();
        };
    CountDownLatch firstDone = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);
    new Thread(() -> taskQueue.execute(blocking, firstDone::countDown)).start();

    // When a second submit arrives while the runner is active Then no second runner starts
    awaitCountDown(runnerEntered);
    taskQueue.execute(DirectExecutor.INSTANCE, secondDone::countDown);
    sleep(100);
    assertThat(runnerEntries.get()).isEqualTo(1);

    // And both tasks still drain in order once the runner is released
    releaseRunner.countDown();
    awaitCountDown(firstDone);
    awaitCountDown(secondDone);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should reject only the head when lock failure reschedule is rejected")
  void shouldRejectOnlyTheHeadWhenLockFailureRescheduleIsRejected() throws InterruptedException {
    // Given a contended lock with a flaky executor that rejects every retry after the first
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(false);
    CountDownLatch runnerEntered = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    AtomicInteger executeCalls = new AtomicInteger();
    Executor flaky =
        command -> {
          int call = executeCalls.getAndIncrement();
          if (call == 0) {
            runnerEntered.countDown();
            uncheckedRun(releaseRunner::await);
            DirectExecutor.INSTANCE.execute(command);
          } else {
            throw new RejectedExecutionException("saturated");
          }
        };
    AtomicReference<Throwable> headSeen = new AtomicReference<>();
    Runnable first = mock(Runnable.class, withSettings().extraInterfaces(RejectionAware.class));
    doAnswer(
            invocation -> {
              headSeen.set(invocation.getArgument(0));
              return null;
            })
        .when((RejectionAware) first)
        .rejected(any());
    CountDownLatch secondDone = new CountDownLatch(1);
    Runnable second = mock(Runnable.class);
    doAnswer(
            invocation -> {
              secondDone.countDown();
              return null;
            })
        .when(second)
        .run();

    // When the runner cannot acquire the lock and its retry is rejected
    new Thread(() -> taskQueue.execute(flaky, first)).start();
    runnerEntered.await();
    taskQueue.execute(DirectExecutor.INSTANCE, second);
    releaseRunner.countDown();

    // Then only the head is rejected visibly while the second task is preserved, not idle
    await().atMost(Duration.ofSeconds(10)).until(() -> headSeen.get() != null);
    assertThat(headSeen.get()).isInstanceOf(RejectedExecutionException.class);
    verify(first, never()).run();
    assertThat(taskQueue.isIdle()).isFalse();

    // And the preserved task still drains once the lock is available
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    awaitCountDown(secondDone);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should keep lock across nested Direct to async handoff")
  void shouldKeepLockAcrossNestedDirectToAsyncHandoff()
      throws
          InterruptedException { // Given a queue drained as [async -> Direct -> async]: outer hands
    // off to Direct
    // inline, nested then hands off async. The lock must transfer once, not unlock early.
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    Executor blockingAsync =
        command -> {
          runnerStarted.countDown();
          uncheckedRun(releaseRunner::await);
          command.run();
        };
    Executor asyncTail = newSingleThreadExecutor();
    CountDownLatch done = new CountDownLatch(3);

    // When all three tasks are queued before the runner starts draining
    new Thread(
            () ->
                taskQueue.execute(
                    blockingAsync,
                    () -> {
                      sleep(10);
                      done.countDown();
                    }))
        .start();
    runnerStarted.await();
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    taskQueue.execute(asyncTail, done::countDown);
    releaseRunner.countDown();

    // Then all tasks run with a single lock acquisition (no re-lock after premature unlock)
    awaitCountDown(done);
    sleep(200);
    verify(taskQueue, times(1)).tryLock(any());
    verify(taskQueue, times(1)).unlock();
  }

  @Test
  @Timeout(15)
  @DisplayName("should not stack overflow on many alternating Direct async handoffs")
  void shouldNotStackOverflowOnManyAlternatingDirectInlineHandoffs() throws InterruptedException {
    // Given a lock that never yields, with many tasks alternating Direct/async.
    // Direct handoffs must loop (trampoline) like TaskQueue instead of recursing one
    // frame per switch; async handoffs run on another thread and do not grow this stack.
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    Executor asyncExec = newSingleThreadExecutor();
    int count = 5000;
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    Executor blockingAsync =
        command -> {
          runnerStarted.countDown();
          uncheckedRun(releaseRunner::await);
          command.run();
        };
    CountDownLatch done = new CountDownLatch(count);
    new Thread(() -> taskQueue.execute(blockingAsync, () -> {})).start();
    runnerStarted.await();
    // When all alternating tasks are queued behind the blocked runner
    for (int i = 0; i < count; i++) {
      Executor exec = (i % 2 == 0) ? DirectExecutor.INSTANCE : asyncExec;
      taskQueue.execute(exec, done::countDown);
    }
    releaseRunner.countDown();

    // Then all tasks drain without StackOverflowError
    awaitCountDown(done);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @Timeout(15)
  @DisplayName("should not stack overflow on many alternating unknown inline handoffs")
  void shouldNotStackOverflowOnManyAlternatingUnknownInlineHandoffs() throws InterruptedException {
    // Given two distinct pseudo-sync executors (plain Runnable::run, no marker) alternating:
    // thread-identity trampoline must loop in-frame with bounded depth, not recurse per switch.
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    Executor inlineA = command -> command.run();
    Executor inlineB = command -> command.run();
    int count = 5000;
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    Executor blockingAsync =
        command -> {
          runnerStarted.countDown();
          uncheckedRun(releaseRunner::await);
          command.run();
        };
    CountDownLatch done = new CountDownLatch(count);
    AtomicInteger maxDepth = new AtomicInteger();
    int baseline = Thread.currentThread().getStackTrace().length;
    new Thread(() -> taskQueue.execute(blockingAsync, () -> {})).start();
    runnerStarted.await();
    for (int i = 0; i < count; i++) {
      Executor exec = (i % 2 == 0) ? inlineA : inlineB;
      taskQueue.execute(
          exec,
          () -> {
            maxDepth.accumulateAndGet(Thread.currentThread().getStackTrace().length, Math::max);
            done.countDown();
          });
    }
    releaseRunner.countDown();

    // Then all tasks drain with bounded stack growth (linear recursion adds ~1 frame/switch)
    awaitCountDown(done);
    assertThat(maxDepth.get() - baseline).isLessThan(300);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should back off async reschedule when tryLock persistently fails")
  void shouldBackOffAsyncRescheduleWhenTryLockPersistentlyFails() {
    // Given a persistently contended lock with an async head executor
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(false);
    AtomicInteger tryLocks = new AtomicInteger();
    LockableTaskQueue counting =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            tryLocks.incrementAndGet();
            return false;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    Executor asyncExec = newSingleThreadExecutor();

    // When the runner keeps failing to acquire the lock
    counting.execute(asyncExec, () -> {});
    sleep(200);

    // Then retries are delayed (1-100ms exponential backoff with jitter), not a hot spin hammering
    // the lock/pool
    // Buggy immediate re-execute yields tens of thousands of attempts in 200ms.
    assertThat(tryLocks.get()).isGreaterThan(0).isLessThan(1000);
  }

  @Test
  @DisplayName("should support counting shouldYield with default stats")
  void shouldSupportCountingShouldYieldWithDefaultStats() {
    // Given a subclass that counts via ExecutionCountStats using the default ctor
    java.util.concurrent.atomic.AtomicReference<Class<?>> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            seen.set(stats.getClass());
            return ((ExecutionCountStats) stats).getExecutionCount() >= 1;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch done = new CountDownLatch(1);

    // When
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);

    // Then default stats must already be countable (no Dummy CCE path)
    assertThat(seen.get()).isEqualTo(ExecutionCountStats.class);
  }

  @Test
  @DisplayName("should reschedule instead of stranding when stats reset throws")
  void shouldRescheduleInsteadOfStrandingWhenStatsResetThrows() {
    // Given stats that fail once, then behave
    io.github.jinganix.peashooter.ExecutionStats flaky =
        new ExecutionCountStats() {
          boolean failed;

          @Override
          public void reset() {
            if (!failed) {
              failed = true;
              throw new RuntimeException("reset boom");
            }
            super.reset();
          }
        };
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(flaky, sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch done = new CountDownLatch(1);

    // When the first batch fails reset, the pending task must still run afterwards
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    taskQueue.execute(DirectExecutor.INSTANCE, () -> {});
    awaitCountDown(done);

    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should back off when stats reset persistently throws")
  void shouldBackOffWhenStatsResetPersistentlyThrows() {
    // Given stats failing once (then healthy) and a lock that always grants,
    // with a manual rescheduler capturing delayed retries instead of running them
    AtomicInteger tryLocks = new AtomicInteger();
    io.github.jinganix.peashooter.ExecutionStats flaky =
        new ExecutionCountStats() {
          boolean failed;

          @Override
          public void reset() {
            if (!failed) {
              failed = true;
              throw new RuntimeException("reset boom");
            }
            super.reset();
          }
        };
    List<Runnable> retries = new java.util.concurrent.CopyOnWriteArrayList<>();
    ScheduledExecutorService manual =
        mock(ScheduledExecutorService.class, Answers.RETURNS_DEFAULTS);
    doAnswer(
            invocation -> {
              retries.add(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            })
        .when(manual)
        .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(flaky, manual) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            tryLocks.incrementAndGet();
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch done = new CountDownLatch(1);

    // When the first batch fails reset
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);

    // Then the runner backs off via the 1-100ms backoff scheduler instead of hot-looping inline:
    // exactly one lock attempt so far, one delayed retry scheduled, task not yet run
    assertThat(tryLocks.get()).isEqualTo(1);
    verify(manual, times(1))
        .schedule(
            any(Runnable.class),
            org.mockito.ArgumentMatchers.longThat(d -> d >= 1 && d <= 100),
            eq(TimeUnit.MILLISECONDS));
    assertThat(done.getCount()).isEqualTo(1);

    // And running the retry completes the pending task with a fresh counting window
    assertThat(retries).hasSize(1);
    retries.get(0).run();
    awaitCountDown(done);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should not reset stats when lock is never acquired")
  void shouldNotResetStatsWhenLockIsNeverAcquired() {
    // Given stats counting resets and a lock that never grants
    AtomicInteger resets = new AtomicInteger();
    io.github.jinganix.peashooter.ExecutionStats counting =
        new ExecutionCountStats() {
          @Override
          public void reset() {
            resets.incrementAndGet();
            super.reset();
          }
        };
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(counting, sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            throw new AssertionError("unlock must not run without a lock hold");
          }
        };

    // When a task is submitted but the lock is contended Then the losing runner must not
    // clear counters owned by another batch: no reset without ownership, only a retry
    taskQueue.execute(DirectExecutor.INSTANCE, () -> {});
    sleep(200);
    assertThat(resets.get()).isEqualTo(0);
  }

  @Test
  @DisplayName("should unlock once when handoff uses async executor while lock is held")
  void shouldUnlockOnceWhenHandoffUsesAsyncExecutorWhileLockIsHeld() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    CountDownLatch done = new CountDownLatch(1);

    // When
    taskQueue.execute(newSingleThreadExecutor(), () -> sleep(50));
    taskQueue.execute(newSingleThreadExecutor(), done::countDown);
    awaitCountDown(done);

    // Then
    verify(taskQueue, times(1)).unlock();
  }

  @Test
  @DisplayName("should hold external lock across fast async handoff")
  void shouldHoldExternalLockAcrossFastAsyncHandoff() throws InterruptedException {
    // Given an external lock: fast async pools may start the runner before execute()
    // returns; thread-identity handoff must still keep the lock until the new runner ends.
    java.util.concurrent.atomic.AtomicBoolean externalLocked =
        new java.util.concurrent.atomic.AtomicBoolean();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return externalLocked.compareAndSet(false, true);
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            externalLocked.set(false);
          }
        };
    // Fast async: starts the runner on another thread and ensures it started before
    // execute() returns, maximizing the old timing-race window (started==true on other thread).
    Executor fastAsync =
        command -> {
          CountDownLatch threadStarted = new CountDownLatch(1);
          Thread thread =
              new Thread(
                  () -> {
                    threadStarted.countDown();
                    command.run();
                  });
          thread.setDaemon(true);
          thread.start();
          try {
            threadStarted.await();
            // Give the new runner a head start so runnerStarted is set before we return.
            Thread.sleep(5);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    Executor firstExecutor = newSingleThreadExecutor();
    java.util.concurrent.atomic.AtomicBoolean lockHeldDuringSecondTask =
        new java.util.concurrent.atomic.AtomicBoolean();
    CountDownLatch done = new CountDownLatch(1);

    // When two tasks use different executors to force a handoff
    taskQueue.execute(firstExecutor, () -> sleep(20));
    taskQueue.execute(
        fastAsync,
        () -> {
          lockHeldDuringSecondTask.set(externalLocked.get());
          done.countDown();
        });
    awaitCountDown(done);
    sleep(100);

    // Then the second task must observe the external lock still held
    assertThat(lockHeldDuringSecondTask.get()).isTrue();
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should unlock once when handoff uses DirectExecutor while lock is held")
  void shouldUnlockOnceWhenHandoffUsesDirectExecutorWhileLockIsHeld() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    CountDownLatch done = new CountDownLatch(1);

    // When
    taskQueue.execute(newSingleThreadExecutor(), () -> sleep(50));
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);

    // Then
    verify(taskQueue, times(1)).unlock();
  }

  @Test
  @DisplayName("should leave the caller-owned scheduler running without a base close")
  void shouldLeaveCallerOwnedSchedulerRunningWithoutABaseClose() {
    // Given a queue with a caller-managed scheduler (who builds it closes it: the base
    // queue is not closeable and never owns the rescheduler, so production callers must
    // share one scheduler and close it explicitly instead of copying the test-double
    // ownership below)
    LockableTaskQueue taskQueue = mockWithSharedScheduler();

    // When the queue drains Then the caller-owned scheduler stays alive for the caller
    CountDownLatch done = new CountDownLatch(1);
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);

    // Then
    assertThat(sharedTestScheduler.isShutdown()).isFalse();
  }

  @Test
  @DisplayName("should expose no close on the base queue while owned schedulers close themselves")
  void shouldExposeNoCloseOnBaseQueueWhileOwnedSchedulersCloseThemselves() {
    // Base queues never own the scheduler, so they expose no close: only subclasses with a
    // self-created scheduler (e.g. Redis test doubles) implement AutoCloseable themselves.
    assertThat((Object[]) LockableTaskQueue.class.getInterfaces())
        .doesNotContain(AutoCloseable.class);
    assertThat(
            Arrays.stream(LockableTaskQueue.class.getMethods())
                .filter(m -> m.getName().equals("close") && m.getParameterCount() == 0)
                .toList())
        .isEmpty();
  }

  @Test
  @DisplayName("should hold no static scheduler pool")
  void shouldHoldNoStaticSchedulerPool() {
    // A static reschedule pool pins threads and the caller's classloader for the JVM lifetime;
    // the retry scheduler must always be caller-provided via the constructor.
    List<java.lang.reflect.Field> schedulerFields =
        Arrays.stream(LockableTaskQueue.class.getDeclaredFields())
            .filter(
                f ->
                    java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && ScheduledExecutorService.class.isAssignableFrom(f.getType()))
            .toList();
    assertThat(schedulerFields).isEmpty();
  }

  @Test
  @DisplayName("should reuse bounded reschedule threads when tryLock persistently fails")
  void shouldReuseSingleRescheduleThreadWhenTryLockPersistentlyFails() {
    // Given a queue with a caller-managed single-thread retry scheduler
    ScheduledExecutorService rescheduler = spy(newSingleThreadTestScheduler());
    LockableTaskQueue taskQueue = mockWithScheduler(rescheduler);
    when(taskQueue.tryLock(any())).thenReturn(false);

    // When
    try {
      for (int i = 0; i < 10; i++) {
        taskQueue.execute(DirectExecutor.INSTANCE, () -> {});
      }
      sleep(500);

      // Then the single caller-provided thread is reused instead of growing per reschedule
      verify(rescheduler, atLeast(1))
          .schedule(
              any(Runnable.class),
              org.mockito.ArgumentMatchers.longThat(d -> d >= 1 && d <= 100),
              eq(TimeUnit.MILLISECONDS));
    } finally {
      rescheduler.shutdownNow();
    }
  }

  @Test
  @Timeout(2)
  @DisplayName("should not stack overflow when tryLock always fails with DirectExecutor")
  void shouldNotStackOverflowWhenTryLockAlwaysFailsWithDirectExecutor() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(false);

    // When / Then: must not recurse on the current thread
    assertThatCode(() -> taskQueue.execute(DirectExecutor.INSTANCE, () -> {}))
        .doesNotThrowAnyException();
    sleep(100);
  }

  @Test
  @DisplayName("should run enqueued task after tryLock fails then succeeds")
  void shouldRunEnqueuedTaskAfterTryLockFailsThenSucceeds() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    AtomicInteger tryLockCalls = new AtomicInteger();
    when(taskQueue.tryLock(any())).thenAnswer(inv -> tryLockCalls.getAndIncrement() > 0);
    CountDownLatch executed = new CountDownLatch(1);

    // When
    taskQueue.execute(DirectExecutor.INSTANCE, executed::countDown);

    // Then
    awaitCountDown(executed);
  }

  @Test
  @DisplayName("should reschedule on async executor when tryLock fails then succeeds")
  void shouldRescheduleOnAsyncExecutorWhenTryLockFailsThenSucceeds() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    AtomicInteger tryLockCalls = new AtomicInteger();
    when(taskQueue.tryLock(any())).thenAnswer(inv -> tryLockCalls.getAndIncrement() > 0);
    CountDownLatch executed = new CountDownLatch(1);

    // When
    taskQueue.execute(newSingleThreadExecutor(), executed::countDown);

    // Then
    awaitCountDown(executed);
  }

  @Test
  @DisplayName("should reset lock and keep draining when unlock throws")
  void shouldResetLockAndKeepDrainingWhenUnlockThrows() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    doThrow(new RuntimeException("unlock boom")).when(taskQueue).unlock();
    CountDownLatch done = new CountDownLatch(2);

    // When / Then a throwing unlock must not poison the queue or escape to submitters
    assertThatCode(
            () -> {
              taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
              taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
            })
        .doesNotThrowAnyException();
    awaitCountDown(done);
    verify(taskQueue, times(2)).tryLock(any());
  }

  @Test
  @DisplayName("should reject only its head when handoff executor throws Error")
  void shouldDiscardBacklogWhenHandoffExecutorThrowsError() throws InterruptedException {
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    Executor blocking =
        command -> {
          runnerStarted.countDown();
          uncheckedRun(releaseRunner::await);
          command.run();
        };
    Executor errorExecutor =
        command -> {
          throw new OutOfMemoryError("handoff boom");
        };

    new Thread(() -> taskQueue.execute(blocking, () -> {})).start();
    runnerStarted.await();
    taskQueue.execute(newSingleThreadExecutor(), () -> {});
    taskQueue.execute(errorExecutor, mock(Runnable.class));
    releaseRunner.countDown();
    sleep(500);

    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should preserve backlog when rescheduler fails sneakily")
  void shouldDiscardBacklogWhenReschedulerFailsSneakily() {
    // Given a lock that never grants and a sneakily-failing rescheduler
    java.io.IOException failure = new java.io.IOException("scheduler boom");
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            invocation -> {
              sneakyThrow(failure);
              return null;
            });
    java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), rescheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };

    // When scheduling the retry fails Then fail-open: backlog preserved for the next submit
    // instead of discarding it behind a dead timer
    taskQueue.execute(DirectExecutor.INSTANCE, () -> ran.set(true));
    assertThat(ran.get()).isFalse();
    assertThat(taskQueue.hasPending()).isTrue();
    assertThat(taskQueue.isIdle()).isFalse();
  }

  @Test
  @DisplayName("should coalesce concurrent reschedules into one timer")
  void shouldCoalesceConcurrentReschedulesIntoOneTimer() {
    // Given a lock that never grants and a rescheduler that captures without firing
    java.util.List<Runnable> timers = new java.util.ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            invocation -> {
              timers.add(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), rescheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };

    // When two runners reschedule while a timer is already pending Then only one timer exists
    taskQueue.execute(DirectExecutor.INSTANCE, () -> {});
    taskQueue.run();
    assertThat(timers).hasSize(1);
  }

  @Test
  @DisplayName("should guard reschedule coalescing with a dedicated lock object")
  void shouldGuardRescheduleCoalescingWithADedicatedLock() throws Exception {
    // Given the coalescing monitor in RetryScheduler: synchronizing on the AtomicReference
    // holder itself is fragile industry practice (concurrent-object monitor). A dedicated final
    // Object lock keeps the monitor stable regardless of the holder type.
    java.lang.reflect.Field lock = RetryScheduler.class.getDeclaredField("rescheduleLock");
    assertThat(lock.getType()).isEqualTo(Object.class);
    assertThat(java.lang.reflect.Modifier.isFinal(lock.getModifiers())).isTrue();
  }

  @Test
  @DisplayName("should reuse held lock for runner arriving during acquisition")
  void shouldReuseHeldLockForRunnerArrivingDuringAcquisition() throws Exception {
    // Given a gated external lock and a slow release, so the arrival window is wide open
    AtomicInteger tryLockCalls = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            tryLockCalls.incrementAndGet();
            entered.countDown();
            uncheckedRun(release::await);
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            sleep(500);
          }
        };

    // When a second runner arrives while the first is still acquiring
    Thread first = new Thread(taskQueue::run);
    first.start();
    awaitCountDown(entered);
    Thread second = new Thread(taskQueue::run);
    second.start();
    sleep(200);
    release.countDown();
    awaitDeath(first, second);

    // Then the lock was acquired once and shared, not invoked concurrently
    assertThat(tryLockCalls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("should reschedule instead of running concurrently during unlock")
  void shouldRescheduleInsteadOfRunningConcurrentlyDuringUnlock() throws Exception {
    // Given a batch releasing its lock with a blocked unlock, counting pre-release acquisitions
    java.util.concurrent.atomic.AtomicInteger preReleaseAcquisitions =
        new java.util.concurrent.atomic.AtomicInteger();
    CountDownLatch unlocking = new CountDownLatch(1);
    CountDownLatch releaseUnlock = new CountDownLatch(1);
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any()))
        .thenAnswer(
            invocation -> {
              if (releaseUnlock.getCount() > 0) {
                preReleaseAcquisitions.incrementAndGet();
              }
              return true;
            });
    when(taskQueue.shouldYield(any())).thenReturn(false);
    doAnswer(
            invocation -> {
              unlocking.countDown();
              uncheckedRun(releaseUnlock::await);
              return null;
            })
        .when(taskQueue)
        .unlock();
    CountDownLatch taskDone = new CountDownLatch(1);
    java.util.concurrent.ExecutorService pool = newSingleThreadExecutor();
    try {
      taskQueue.execute(
          pool,
          () -> {
            taskDone.countDown();
          });
      awaitCountDown(taskDone);
      awaitCountDown(unlocking);

      // When a second runner arrives mid-release Then it backs off on the unlocking flag instead
      // of acquiring concurrently: only the owning batch acquires before the release
      Thread intruder = new Thread(taskQueue::run);
      intruder.start();
      sleep(200);
      assertThat(preReleaseAcquisitions.get()).isEqualTo(1);
      releaseUnlock.countDown();
      awaitDeath(intruder);

      // Then the queue drained cleanly with no duplicate batch
      assertThat(taskQueue.isIdle()).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should not release the external lock concurrently from a competing runner")
  void shouldNotReleaseTheExternalLockConcurrentlyFromACompetingRunner() throws Exception {
    // Given a batch holding the external lock and blocked inside unlock
    AtomicInteger inUnlock = new AtomicInteger();
    AtomicInteger maxInUnlock = new AtomicInteger();
    CountDownLatch unlocking = new CountDownLatch(1);
    CountDownLatch releaseUnlock = new CountDownLatch(1);
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    doAnswer(
            invocation -> {
              int cur = inUnlock.incrementAndGet();
              maxInUnlock.accumulateAndGet(cur, Math::max);
              unlocking.countDown();
              uncheckedRun(releaseUnlock::await);
              inUnlock.decrementAndGet();
              return null;
            })
        .when(taskQueue)
        .unlock();
    CountDownLatch taskDone = new CountDownLatch(1);
    java.util.concurrent.ExecutorService pool = newSingleThreadExecutor();
    try {
      taskQueue.execute(pool, taskDone::countDown);
      awaitCountDown(taskDone);
      awaitCountDown(unlocking);

      // When a competing runner arrives mid-release
      Thread intruder = new Thread(taskQueue::run);
      intruder.start();
      sleep(200);

      // Then it must not enter unlock() concurrently with the owner
      assertThat(maxInUnlock.get()).isEqualTo(1);

      releaseUnlock.countDown();
      awaitDeath(intruder);
      assertThat(maxInUnlock.get()).isEqualTo(1);
      assertThat(taskQueue.isIdle()).isTrue();
    } finally {
      releaseUnlock.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should reject only its head when manually fired retry fails sneakily")
  void shouldDiscardBacklogWhenManuallyFiredRetryFailsSneakily() {
    // Given a captured retry timer with a deferred sneakily-failing task executor
    java.util.List<Runnable> timers = new java.util.ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            invocation -> {
              timers.add(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    java.io.IOException failure = new java.io.IOException("retry boom");
    java.util.concurrent.atomic.AtomicInteger executions =
        new java.util.concurrent.atomic.AtomicInteger();
    Executor deferredSneaky =
        command -> {
          if (executions.getAndIncrement() == 0) {
            command.run();
          } else {
            sneakyThrow(failure);
          }
        };
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), rescheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();

    // When the retry fires Then only the failed head is rejected, the rest preserved
    taskQueue.execute(deferredSneaky, () -> ran.set(true));
    assertThat(timers).hasSize(1);
    timers.get(0).run();
    assertThat(ran.get()).isFalse();
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should keep the backlog when a retry executor starts the runner then throws")
  void shouldKeepBacklogWhenRetryExecutorStartsRunnerThenThrows() throws Exception {
    // Given a captured retry timer whose head executor starts the runner on another thread, then
    // throws (the started-elsewhere contract HandoffTemplate already honors on submit/handoff)
    List<Runnable> timers = new java.util.ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            invocation -> {
              timers.add(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    java.util.concurrent.atomic.AtomicBoolean allowLock =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch taskRan = new CountDownLatch(1);
    Executor startsThenThrows =
        command -> {
          if (calls.getAndIncrement() == 0) {
            command.run();
            return;
          }
          Thread runner = new Thread(command, "started-elsewhere-runner");
          runner.setDaemon(true);
          runner.start();
          // Establish happens-before for the startedOn write before throwing: wait until the
          // runner actually ran the task, so this is the "started, then threw" shape.
          uncheckedRun(() -> taskRan.await(5, TimeUnit.SECONDS));
          throw new RuntimeException("retry boom after start");
        };
    AtomicReference<Throwable> rejected = new AtomicReference<>();
    class NotingTask implements Runnable, RejectionAware {
      @Override
      public void run() {
        taskRan.countDown();
      }

      @Override
      public void rejected(Throwable cause) {
        rejected.set(cause);
      }
    }
    LockableTaskQueue queue =
        new LockableTaskQueue(new ExecutionCountStats(), rescheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return allowLock.get();
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };

    queue.execute(startsThenThrows, new NotingTask());
    assertThat(timers).hasSize(1);
    allowLock.set(true);

    // When the retry fires and its executor starts the runner elsewhere and then throws
    timers.get(0).run();

    // Then the started runner owns the drain: the task runs and is never rejected
    assertThat(taskRan.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(rejected.get()).isNull();
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should keep the head when a retry executor runs the runner inline then throws")
  void shouldKeepHeadWhenRetryExecutorRunsRunnerInlineThenThrows() throws Exception {
    // Given a captured retry timer and an executor that runs the runner inline, then throws
    List<Runnable> timers = new java.util.ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            invocation -> {
              timers.add(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    java.util.concurrent.atomic.AtomicBoolean allowLock =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch ran = new CountDownLatch(1);
    Executor inlineThenThrows =
        command -> {
          int call = calls.getAndIncrement();
          command.run();
          if (call == 1) {
            throw new RuntimeException("inline boom after run");
          }
        };
    AtomicReference<Throwable> rejected = new AtomicReference<>();
    class NotingTask implements Runnable, RejectionAware {
      @Override
      public void run() {
        ran.countDown();
      }

      @Override
      public void rejected(Throwable cause) {
        rejected.set(cause);
      }
    }
    LockableTaskQueue queue =
        new LockableTaskQueue(new ExecutionCountStats(), rescheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return allowLock.get();
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };

    queue.execute(inlineThenThrows, new NotingTask());
    assertThat(timers).hasSize(1);
    allowLock.set(true);

    // When the retry fires and the executor runs the runner inline, then throws
    timers.get(0).run();

    // Then the head already ran inline: it must not be rejected (no double notification)
    assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(rejected.get()).isNull();
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should drop the runner claim when a retry executor throws an Error before starting")
  void shouldDropRunnerClaimWhenRetryExecutorThrowsErrorBeforeStarting() throws Exception {
    // Given a captured retry timer and an executor that throws an Error before starting the runner
    List<Runnable> timers = new java.util.ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            invocation -> {
              timers.add(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    java.util.concurrent.atomic.AtomicBoolean allowLock =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch firstRan = new CountDownLatch(1);
    CountDownLatch secondRan = new CountDownLatch(1);
    Executor flaky =
        command -> {
          if (calls.getAndIncrement() == 1) {
            throw new AssertionError("executor boom before start");
          }
          command.run();
        };
    LockableTaskQueue queue =
        new LockableTaskQueue(new ExecutionCountStats(), rescheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return allowLock.get();
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };

    queue.execute(flaky, firstRan::countDown);
    assertThat(timers).hasSize(1);

    // When the retry executor throws an Error before starting the runner
    assertThatThrownBy(() -> timers.get(0).run()).isInstanceOf(AssertionError.class);

    // Then a later explicit submit must still claim the runner and drain the preserved backlog
    allowLock.set(true);
    queue.execute(flaky, secondRan::countDown);
    assertThat(firstRan.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(secondRan.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should preserve backlog when unlock-time reschedule fails")
  void shouldDiscardPendingWorkWhenUnlockTimeRescheduleFails() throws Exception {
    // Given a batch draining with a blocked unlock and a failing rescheduler
    java.util.concurrent.atomic.AtomicInteger unlocks =
        new java.util.concurrent.atomic.AtomicInteger();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenThrow(new RejectedExecutionException("scheduler down"));
    CountDownLatch unlocking = new CountDownLatch(1);
    CountDownLatch releaseUnlock = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean secondRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), rescheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            unlocks.incrementAndGet();
            unlocking.countDown();
            uncheckedRun(releaseUnlock::await);
          }
        };
    java.util.concurrent.ExecutorService pool = newSingleThreadExecutor();
    try {
      CountDownLatch taskDone = new CountDownLatch(1);
      taskQueue.execute(
          pool,
          () -> {
            taskDone.countDown();
          });
      awaitCountDown(taskDone);
      awaitCountDown(unlocking);

      // When work arrives during the unlock Then fail-open: the failed retry preserves
      // the backlog for the next submit instead of discarding it behind a dead timer
      taskQueue.execute(pool, () -> secondRan.set(true));
      releaseUnlock.countDown();
      long deadline = System.currentTimeMillis() + 5000;
      while ((taskQueue.isIdle() || unlocks.get() == 0) && System.currentTimeMillis() < deadline) {
        sleep(10);
      }
      sleep(100);
      assertThat(secondRan.get()).isFalse();
      assertThat(taskQueue.hasPending()).isTrue();
      assertThat(taskQueue.isIdle()).isFalse();
      assertThat(unlocks.get()).isEqualTo(1);

      // And the preserved backlog resumes on the next explicit submit
      CountDownLatch resumed = new CountDownLatch(1);
      taskQueue.execute(pool, resumed::countDown);
      awaitCountDown(resumed);
      sleep(200);
      assertThat(secondRan.get()).isTrue();
      assertThat(taskQueue.isIdle()).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should preserve multi-head backlog when unlock-time reschedule fails")
  void shouldPreserveMultiHeadBacklogWhenUnlockTimeRescheduleFails() throws Exception {
    // Given a batch draining with a blocked unlock and a dead rescheduler
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenThrow(new RejectedExecutionException("scheduler down"));
    CountDownLatch unlocking = new CountDownLatch(1);
    CountDownLatch releaseUnlock = new CountDownLatch(1);
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), rescheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            unlocking.countDown();
            uncheckedRun(releaseUnlock::await);
          }
        };
    java.util.concurrent.ExecutorService pool = newSingleThreadExecutor();
    try {
      CountDownLatch firstDone = new CountDownLatch(1);
      taskQueue.execute(pool, firstDone::countDown);
      awaitCountDown(firstDone);
      awaitCountDown(unlocking);

      // When two heads arrive during the unlock and the timer is dead Then both are preserved
      java.util.concurrent.atomic.AtomicBoolean secondRan =
          new java.util.concurrent.atomic.AtomicBoolean();
      java.util.concurrent.atomic.AtomicBoolean thirdRan =
          new java.util.concurrent.atomic.AtomicBoolean();
      taskQueue.execute(pool, () -> secondRan.set(true));
      taskQueue.execute(pool, () -> thirdRan.set(true));
      releaseUnlock.countDown();
      sleep(300);
      assertThat(taskQueue.hasPending()).isTrue();
      assertThat(taskQueue.isIdle()).isFalse();
      assertThat(secondRan.get()).isFalse();
      assertThat(thirdRan.get()).isFalse();

      // And the whole backlog resumes FIFO on the next submit
      CountDownLatch fourthDone = new CountDownLatch(1);
      taskQueue.execute(pool, fourthDone::countDown);
      awaitCountDown(fourthDone);
      sleep(200);
      assertThat(secondRan.get()).isTrue();
      assertThat(thirdRan.get()).isTrue();
      assertThat(taskQueue.isIdle()).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should keep lock across nested sync to async handoff")
  void shouldKeepLockAcrossNestedSyncToAsyncHandoff() throws Exception {
    // Given three executors: pool runner, inline handoff, async tail
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    java.util.concurrent.ExecutorService poolA = newSingleThreadExecutor();
    java.util.concurrent.ExecutorService poolC = newSingleThreadExecutor();
    Executor inlineB = command -> command.run();
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    CountDownLatch tailDone = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger completions =
        new java.util.concurrent.atomic.AtomicInteger();
    try {
      taskQueue.execute(
          poolA,
          () -> {
            taskStarted.countDown();
            uncheckedRun(releaseTask::await);
            completions.incrementAndGet();
          });
      awaitCountDown(taskStarted);
      taskQueue.execute(inlineB, completions::incrementAndGet);
      taskQueue.execute(
          poolC,
          () -> {
            completions.incrementAndGet();
            tailDone.countDown();
          });
      releaseTask.countDown();

      // When the nested handoff transfers async Then one lock covers all three tasks
      awaitCountDown(tailDone);
      assertThat(completions.get()).isEqualTo(3);
      verify(taskQueue, times(1)).tryLock(any());
      verify(taskQueue, times(1)).unlock();
    } finally {
      poolA.shutdownNow();
      poolC.shutdownNow();
    }
  }

  @Test
  @DisplayName("should reschedule instead of dying when tryLock throws")
  void shouldRescheduleInsteadOfDyingWhenTryLockThrows() {
    // Given a lock that fails once, then recovers
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    AtomicInteger calls = new AtomicInteger();
    when(taskQueue.tryLock(any()))
        .thenAnswer(
            inv -> {
              if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("lock boom");
              }
              return true;
            });
    CountDownLatch done = new CountDownLatch(1);

    // When
    taskQueue.execute(newSingleThreadExecutor(), done::countDown);

    // Then the runner survives and the task still runs
    awaitCountDown(done);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should end batch and unlock when shouldYield throws")
  void shouldEndBatchAndUnlockWhenShouldYieldThrows() throws InterruptedException {
    // Given a yield check that always throws, with a second task queued behind the first
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenThrow(new RuntimeException("yield boom"));
    CountDownLatch task1Running = new CountDownLatch(1);
    CountDownLatch releaseTask1 = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);

    // When the first task blocks while the second is enqueued, then releases
    new Thread(
            () ->
                taskQueue.execute(
                    DirectExecutor.INSTANCE,
                    () -> {
                      task1Running.countDown();
                      uncheckedRun(releaseTask1::await);
                      done.countDown();
                    }))
        .start();
    task1Running.await();
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    releaseTask1.countDown();

    // Then the queued task must survive the yield failure instead of being discarded
    // (two task batches plus the terminal empty drain each lock once)
    awaitCountDown(done);
    verify(taskQueue, times(3)).tryLock(any());
    verify(taskQueue, times(3)).unlock();
  }

  @Test
  @DisplayName("should not unlock when lock cannot be acquired")
  void shouldNotUnlockWhenLockCannotBeAcquired() { // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(false);

    // When
    taskQueue.run();

    // Then
    verify(taskQueue, never()).unlock();
  }

  @Test
  @DisplayName("should unlock without yielding when queue is empty after lock")
  void shouldUnlockWithoutYieldingWhenQueueIsEmptyAfterLock() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);

    // When
    taskQueue.run();

    // Then
    verify(taskQueue, never()).shouldYield(any());
    verify(taskQueue, times(1)).unlock();
  }

  @Test
  @DisplayName("should acquire lock twice when yielding after a single task")
  void shouldAcquireLockTwiceWhenYieldingAfterASingleTask() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(true);
    CountDownLatch latch = new CountDownLatch(1);

    // When
    taskQueue.execute(DirectExecutor.INSTANCE, latch::countDown);
    awaitCountDown(latch);

    // Then
    verify(taskQueue, times(1)).shouldYield(any());
    verify(taskQueue, times(2)).tryLock(any());
    verify(taskQueue, times(2)).unlock();
  }

  @Test
  @DisplayName("should acquire lock once when not yielding after a single task")
  void shouldAcquireLockOnceWhenNotYieldingAfterASingleTask() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    CountDownLatch latch = new CountDownLatch(1);

    // When
    taskQueue.execute(DirectExecutor.INSTANCE, latch::countDown);
    awaitCountDown(latch);

    // Then
    verify(taskQueue, times(1)).shouldYield(any());
    verify(taskQueue, times(1)).tryLock(any());
    verify(taskQueue, times(1)).unlock();
  }

  @Test
  @DisplayName("should acquire lock twice when first of two tasks yields")
  void shouldAcquireLockTwiceWhenFirstOfTwoTasksYields() throws InterruptedException {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(true, false);

    // When
    executeTwoTasks(taskQueue);

    // Then
    verify(taskQueue, times(2)).shouldYield(any());
    verify(taskQueue, times(2)).tryLock(any());
    verify(taskQueue, times(2)).unlock();
  }

  @Test
  @DisplayName("should acquire lock once when two tasks do not yield")
  void shouldAcquireLockOnceWhenTwoTasksDoNotYield() throws InterruptedException {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);

    // When
    executeTwoTasks(taskQueue);

    // Then
    verify(taskQueue, times(2)).shouldYield(any());
    verify(taskQueue, times(1)).tryLock(any());
    verify(taskQueue, times(1)).unlock();
  }

  @Test
  @DisplayName("should run next task when the first task throws")
  void shouldRunNextTaskWhenTheFirstTaskThrows() {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    long startMillis = System.currentTimeMillis();

    // When
    taskQueue.execute(
        newSingleThreadExecutor(),
        () -> {
          sleep(100);
          throw new RuntimeException("error");
        });
    AtomicReference<Long> elapsed = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    taskQueue.execute(
        newSingleThreadExecutor(),
        () -> {
          elapsed.set(System.currentTimeMillis() - startMillis);
          latch.countDown();
        });
    awaitCountDown(latch);

    // Then
    assertThat(elapsed.get()).isGreaterThanOrEqualTo(100);
  }

  @Test
  @DisplayName("should finish prior queued tasks when a later submit is rejected")
  void shouldFinishPriorQueuedTasksWhenALaterSubmitIsRejected() throws InterruptedException {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    when(taskQueue.shouldYield(any())).thenReturn(false);
    AtomicInteger completed = new AtomicInteger(0);
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException()).when(rejecting).execute(any());
    Runnable rejected = mock(Runnable.class);

    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    CountDownLatch priorTasksDone = new CountDownLatch(3);

    new Thread(
            () ->
                taskQueue.execute(
                    command -> {
                      runnerStarted.countDown();
                      uncheckedRun(releaseRunner::await);
                      command.run();
                    },
                    () -> {}))
        .start();
    runnerStarted.await();

    Executor worker = newSingleThreadExecutor();
    Runnable countAndSignal =
        () -> {
          completed.incrementAndGet();
          priorTasksDone.countDown();
        };
    taskQueue.execute(worker, countAndSignal);
    taskQueue.execute(worker, countAndSignal);
    taskQueue.execute(worker, countAndSignal);

    // When
    taskQueue.execute(rejecting, rejected);
    releaseRunner.countDown();
    awaitCountDown(priorTasksDone);

    // Then
    assertThat(completed.get()).isEqualTo(3);
    verify(rejected, never()).run();
  }

  @Test
  @DisplayName("should reject submit when executor rejects on an idle queue")
  void shouldRejectSubmitWhenExecutorRejectsOnAnIdleQueue() {
    // Given a saturated executor on an idle queue
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    Executor executor = mock(Executor.class);
    doThrow(new RejectedExecutionException()).when(executor).execute(any());
    Runnable task = mock(Runnable.class);

    // When / Then the trigger fails visibly so the submitter can retry
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskQueue.execute(executor, task))
        .isInstanceOf(RejectedExecutionException.class);
    verify(task, never()).run();
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should not run enqueued task when executor rejects while runner is active")
  void shouldNotRunEnqueuedTaskWhenExecutorRejectsWhileRunnerIsActive()
      throws InterruptedException {
    // Given
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    when(taskQueue.tryLock(any())).thenReturn(true);
    Executor executor = mock(Executor.class);
    doThrow(new RejectedExecutionException()).when(executor).execute(any());
    Runnable task = mock(Runnable.class);

    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    CountDownLatch runnerFinished = new CountDownLatch(1);

    new Thread(
            () ->
                taskQueue.execute(
                    command -> {
                      runnerStarted.countDown();
                      uncheckedRun(releaseRunner::await);
                      command.run();
                      runnerFinished.countDown();
                    },
                    () -> {}))
        .start();
    runnerStarted.await();

    // When
    taskQueue.execute(executor, task);
    releaseRunner.countDown();
    awaitCountDown(runnerFinished);

    // Then
    verify(executor, times(1)).execute(any());
    verify(task, never()).run();
  }

  @Test
  @DisplayName("should not run next batch while unlock is blocked")
  void shouldNotRunNextBatchWhileUnlockIsBlocked() throws InterruptedException {
    // Given a queue whose unlock blocks (e.g. slow external release)
    CountDownLatch unlockEntered = new CountDownLatch(1);
    CountDownLatch releaseUnlock = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean secondRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            unlockEntered.countDown();
            uncheckedRun(releaseUnlock::await);
          }
        };

    // When the first batch finishes and blocks inside unlock
    taskQueue.execute(newSingleThreadExecutor(), () -> {});
    unlockEntered.await();
    // And a second batch is submitted while unlock is still blocked
    new Thread(
            () ->
                taskQueue.execute(
                    DirectExecutor.INSTANCE,
                    () -> {
                      secondRan.set(true);
                      secondDone.countDown();
                    }))
        .start();
    sleep(300);

    // Then the second batch must not run concurrently with the in-progress unlock
    assertThat(secondRan.get()).isFalse();
    releaseUnlock.countDown();
    awaitCountDown(secondDone);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should schedule tryLock retries on the injected scheduler")
  void shouldScheduleTryLockRetriesOnInjectedScheduler() {
    // Given a queue with a caller-managed scheduler and a persistently contended lock
    AtomicReference<Runnable> captured = new AtomicReference<>();
    CountDownLatch scheduled = new CountDownLatch(1);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              captured.set(inv.getArgument(0));
              scheduled.countDown();
              @SuppressWarnings("unchecked")
              ScheduledFuture<?> future = mock(ScheduledFuture.class);
              return future;
            });
    LockableTaskQueue queue =
        new LockableTaskQueue(new ExecutionCountStats(), scheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };

    // When a task cannot acquire the lock
    queue.execute(newSingleThreadExecutor(), () -> {});

    // Then the retry goes to the injected scheduler with the documented backoff
    awaitCountDown(scheduled);
    verify(scheduler, times(1))
        .schedule(
            any(Runnable.class),
            org.mockito.ArgumentMatchers.longThat(d -> d >= 1 && d <= 100),
            eq(TimeUnit.MILLISECONDS));

    // And firing the retry reschedules again (coalescing claim released at fire time).
    // The retry hands the runner back to the task executor, so the second schedule lands
    // asynchronously.
    captured.get().run();
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                verify(scheduler, times(2))
                    .schedule(
                        any(Runnable.class),
                        org.mockito.ArgumentMatchers.longThat(d -> d >= 1 && d <= 100),
                        eq(TimeUnit.MILLISECONDS)));
  }

  @Test
  @DisplayName("should reschedule when tryLock sneaky-throws checked")
  void shouldRescheduleWhenTryLockSneakyThrowsChecked() {
    // Given a lock smuggling a checked failure once, then recovering
    LockableTaskQueue taskQueue = mockWithSharedScheduler();
    AtomicInteger calls = new AtomicInteger();
    when(taskQueue.tryLock(any()))
        .thenAnswer(
            inv -> {
              if (calls.getAndIncrement() == 0) {
                sneakyThrow(new java.io.IOException("lock sneaky"));
              }
              return true;
            });
    CountDownLatch done = new CountDownLatch(1);

    // When / Then the runner survives like a failed lock and the task still runs
    taskQueue.execute(newSingleThreadExecutor(), done::countDown);
    awaitCountDown(done);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should end batch when stats record throws")
  void shouldEndBatchWhenStatsRecordThrows() {
    io.github.jinganix.peashooter.ExecutionStats stats =
        new ExecutionCountStats() {
          @Override
          public void record() {
            throw new RuntimeException("record boom");
          }
        };
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(stats, sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats s) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats s) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch done = new CountDownLatch(2);

    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);

    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should abort batch loudly on fatal Error instead of running the next task")
  void shouldAbortBatchLoudlyOnFatalErrorInsteadOfRunningTheNextTask() throws Exception {
    // Given a guarded queue with a fatal task followed by a marker task
    java.util.concurrent.atomic.AtomicInteger unlocks =
        new java.util.concurrent.atomic.AtomicInteger();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            unlocks.incrementAndGet();
          }
        };
    java.util.concurrent.atomic.AtomicReference<Throwable> uncaught =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CountDownLatch died = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setUncaughtExceptionHandler(
                  (t, e) -> {
                    uncaught.set(e);
                    died.countDown();
                  });
              return thread;
            });
    java.util.concurrent.atomic.AtomicBoolean secondRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    AssertionError fatal = new AssertionError("boom");
    // Gate the fatal task so the marker is already queued when it throws: no scheduling race
    // between the test thread and the pool thread is possible afterwards.
    java.util.concurrent.CountDownLatch taskStarted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseTask = new java.util.concurrent.CountDownLatch(1);
    try {
      taskQueue.execute(
          pool,
          () -> {
            taskStarted.countDown();
            uncheckedRun(releaseTask::await);
            throw fatal;
          });
      awaitCountDown(taskStarted);
      taskQueue.execute(pool, () -> secondRan.set(true));
      releaseTask.countDown();

      // When the runner hits the fatal task Then it dies loudly with the lock released exactly
      // once, and the batch aborts instead of continuing (no auto-retry on a compromised JVM)
      awaitCountDown(died);
      assertThat(uncaught.get()).isSameAs(fatal);
      Thread.sleep(200);
      assertThat(secondRan.get()).isFalse();
      assertThat(unlocks.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should retain runner claim on drain while external lock is held")
  void shouldRetainRunnerClaimOnDrainWhileExternalLockIsHeld() throws Exception {
    // Given a queue whose unlock blocks, holding the external lock past the empty drain
    CountDownLatch unlockStarted = new CountDownLatch(1);
    CountDownLatch releaseUnlock = new CountDownLatch(1);
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            unlockStarted.countDown();
            uncheckedRun(releaseUnlock::await);
          }
        };
    Executor exec = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      CountDownLatch taskDone = new CountDownLatch(1);
      taskQueue.execute(exec, taskDone::countDown);
      // Wait until the batch drained and entered unlock (empty poll retained the claim)
      assertThat(unlockStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

      // Then no false idle snapshot is published before unlock sets unlocking=true:
      // tasks are empty but the runner claim is still held, so a concurrent submitter
      // cannot start a second runner on the stale lock (concurrent batches + double unlock).
      assertThat(taskQueue.isIdle()).isFalse();
      // And pending work during unlock is queued, not run concurrently
      releaseUnlock.countDown();
      awaitCountDown(taskDone);
    } finally {
      releaseUnlock.countDown();
      ((java.util.concurrent.ExecutorService) exec).shutdownNow();
    }
  }

  @Test
  @DisplayName("should never invoke tryLock concurrently from competing runners")
  void shouldNeverInvokeTryLockConcurrentlyFromCompetingRunners() throws Exception {
    // Given a slow external lock recording its maximum invocation concurrency
    AtomicInteger inTryLock = new AtomicInteger();
    AtomicInteger maxInTryLock = new AtomicInteger();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            int cur = inTryLock.incrementAndGet();
            maxInTryLock.accumulateAndGet(cur, Math::max);
            sleep(100);
            inTryLock.decrementAndGet();
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };

    // When two runners race on an idle queue
    CountDownLatch start = new CountDownLatch(1);
    Thread first =
        new Thread(
            () -> {
              uncheckedRun(start::await);
              taskQueue.run();
            });
    Thread second =
        new Thread(
            () -> {
              uncheckedRun(start::await);
              taskQueue.run();
            });
    first.start();
    second.start();
    start.countDown();
    awaitDeath(first, second);

    // Then lock acquisition was serialized even though the volatile flag was unset for both
    assertThat(maxInTryLock.get()).isEqualTo(1);
  }

  private void executeTwoTasks(TaskQueue taskQueue) throws InterruptedException {
    CountDownLatch firstTaskStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstTask = new CountDownLatch(1);
    CountDownLatch secondTaskDone = new CountDownLatch(1);

    new Thread(
            () ->
                taskQueue.execute(
                    command -> {
                      firstTaskStarted.countDown();
                      uncheckedRun(releaseFirstTask::await);
                      command.run();
                    },
                    () -> {}))
        .start();
    firstTaskStarted.await();

    taskQueue.execute(
        command -> {
          command.run();
          secondTaskDone.countDown();
        },
        () -> {});
    releaseFirstTask.countDown();
    awaitCountDown(secondTaskDone);
  }

  @Test
  @DisplayName("should reset reschedule claim when scheduler sneaky-throws checked")
  void shouldResetRescheduleClaimWhenSchedulerSneakyThrowsChecked() {
    // Given a lock-contended queue whose scheduler smuggles a checked failure once
    AtomicInteger scheduleCalls = new AtomicInteger();
    ScheduledExecutorService flakyScheduler = mock(ScheduledExecutorService.class);
    when(flakyScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            invocation -> {
              int call = scheduleCalls.getAndIncrement();
              if (call == 0) {
                sneakyThrow(new java.io.IOException("scheduler boom"));
              }
              // Retries are recorded but never fired: the test only asserts that the
              // second lock failure still attempts a schedule (claim was released).
              return mock(ScheduledFuture.class);
            });
    // Direct subclass instance (no spy: TaskQueue.runner captures this::run at
    // construction, so an instance-spy would split queue state across two objects).
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), flakyScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };

    // When two separate lock failures each need a retry
    taskQueue.execute(DirectExecutor.INSTANCE, () -> {});
    taskQueue.execute(DirectExecutor.INSTANCE, () -> {});

    // Then the second failure must still schedule a retry (claim was released after the throw)
    verify(flakyScheduler, times(2))
        .schedule(
            any(Runnable.class),
            org.mockito.ArgumentMatchers.longThat(d -> d >= 1 && d <= 100),
            eq(TimeUnit.MILLISECONDS));
  }

  @Test
  @DisplayName("should notify rejected head without holding the queue monitor")
  void shouldNotifyRejectedHeadWithoutHoldingQueueMonitor() throws InterruptedException {
    // Given a lockable queue with a parked runner, a saturated rejection-aware head and survivors
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), mock(ScheduledExecutorService.class)) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    java.util.concurrent.atomic.AtomicBoolean rejectedCalled =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    CountDownLatch survivorsDone = new CountDownLatch(2);
    class Probe implements Runnable, RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        // User callback: runs after the head was removed and the claim repointed, so the
        // surviving backlog stays queued for the same drain instead of stalling.
        rejectedCalled.set(true);
      }
    }
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    Executor blocking =
        command -> {
          runnerStarted.countDown();
          uncheckedRun(releaseRunner::await);
          command.run();
        };
    Thread submitter =
        new Thread(
            () -> {
              taskQueue.execute(blocking, () -> {});
            });
    submitter.start();
    awaitCountDown(runnerStarted);
    Executor saturated =
        command -> {
          throw new RejectedExecutionException("saturated");
        };
    Probe probe = new Probe();
    taskQueue.execute(saturated, probe);
    taskQueue.execute(DirectExecutor.INSTANCE, survivorsDone::countDown);
    taskQueue.execute(DirectExecutor.INSTANCE, survivorsDone::countDown);
    // Release the runner: the drain reaches the saturated head, rejects it outside the monitor,
    // and keeps draining the survivors behind it
    releaseRunner.countDown();
    awaitCountDown(survivorsDone);
    awaitDeath(submitter);

    // Then the rejection callback ran outside the queue monitor and survivors still drained
    assertThat(rejectedCalled.get()).isTrue();
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should share enqueue hooks between TaskQueue and Lockable paths")
  void shouldShareEnqueueHooksBetweenTaskQueueAndLockablePaths() {
    // Given a Lockable queue observing enqueue hooks (previously bypassed by a copy-pasted
    // execute override, which would also split ordering for PinnedTaskQueue-style subclasses)
    AtomicInteger enqueueLocked = new AtomicInteger();
    AtomicInteger enqueued = new AtomicInteger();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}

          @Override
          protected boolean onEnqueueLocked() {
            enqueueLocked.incrementAndGet();
            return true;
          }

          @Override
          protected void onEnqueued(boolean changed) {
            if (changed) {
              enqueued.incrementAndGet();
            }
          }
        };

    // When / Then the shared template method invokes hooks exactly once per submit
    CountDownLatch done = new CountDownLatch(1);
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);
    assertThat(enqueueLocked.get()).isEqualTo(1);
    assertThat(enqueued.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("should hold no ThreadLocal runner state")
  void shouldHoldNoThreadLocalRunnerState() {
    // High-cardinality providers hold tens of thousands of queue instances: one ThreadLocal
    // per queue leaves one ThreadLocalMap entry per (thread, queue) pair, and the RunState
    // value outlives the evicted queue until the next rehash. Runner state must not live
    // in any ThreadLocal field.
    List<java.lang.reflect.Field> threadLocals =
        Arrays.stream(LockableTaskQueue.class.getDeclaredFields())
            .filter(f -> ThreadLocal.class.isAssignableFrom(f.getType()))
            .toList();
    assertThat(threadLocals).isEmpty();
  }

  @Test
  @DisplayName("should isolate runner state across queues nested on one thread")
  void shouldIsolateRunnerStateAcrossQueuesNestedOnOneThread() {
    // Given two queues sharing one thread via inline execution
    LockableTaskQueue outer = mockWithSharedScheduler();
    when(outer.tryLock(any())).thenReturn(true);
    when(outer.shouldYield(any())).thenReturn(false);
    LockableTaskQueue inner = mockWithSharedScheduler();
    when(inner.tryLock(any())).thenReturn(true);
    when(inner.shouldYield(any())).thenReturn(false);
    CountDownLatch done = new CountDownLatch(1);

    // When the outer task synchronously drains the inner queue on the same thread
    outer.execute(
        DirectExecutor.INSTANCE, () -> inner.execute(DirectExecutor.INSTANCE, done::countDown));

    // Then each queue acquired and released its own lock exactly once: the inner
    // batch must not observe the outer depth/ownership and skip its unlock
    awaitCountDown(done);
    verify(outer, times(1)).tryLock(any());
    verify(outer, times(1)).unlock();
    verify(inner, times(1)).tryLock(any());
    verify(inner, times(1)).unlock();
    assertThat(outer.isIdle()).isTrue();
    assertThat(inner.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should drain many queues on shared threads without state interference")
  void shouldDrainManyQueuesOnSharedThreadsWithoutStateInterference() throws Exception {
    // Given hundreds of queues sharing a small pool (high-cardinality provider shape)
    int queueCount = 200;
    java.util.concurrent.ExecutorService pool =
        Executors.newFixedThreadPool(
            4,
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setDaemon(true);
              return thread;
            });
    try {
      List<LockableTaskQueue> queues = new java.util.ArrayList<>(queueCount);
      for (int i = 0; i < queueCount; i++) {
        LockableTaskQueue queue =
            new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
              @Override
              protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
                return true;
              }

              @Override
              protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
                return false;
              }

              @Override
              protected void unlock() {}
            };
        queues.add(queue);
      }
      CountDownLatch done = new CountDownLatch(queueCount);

      // When every queue drains on the shared pool
      for (LockableTaskQueue queue : queues) {
        queue.execute(pool, done::countDown);
      }

      // Then every task runs exactly once and every queue returns to idle
      awaitCountDown(done);
      for (LockableTaskQueue queue : queues) {
        assertThat(queue.isIdle()).isTrue();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should self-heal when scheduled retry is cancelled and drive backlog")
  void shouldSelfHealWhenScheduledRetryIsCancelledAndDriveBacklog() {
    // Given a lock that fails twice then recovers, and a scheduler whose first timer is lost
    // (schedule succeeded but the retry never fires: shutdown/cancel discards it)
    AtomicInteger tryLocks = new AtomicInteger();
    java.util.List<Runnable> timers = new java.util.concurrent.CopyOnWriteArrayList<>();
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            invocation -> {
              timers.add(invocation.getArgument(0));
              @SuppressWarnings("unchecked")
              ScheduledFuture<?> future = mock(ScheduledFuture.class);
              if (timers.size() == 1) {
                // First timer lost: cancelled before firing, never clears the coalescing flag.
                when(future.isDone()).thenReturn(true);
                when(future.isCancelled()).thenReturn(true);
              }
              return future;
            });
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), scheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return tryLocks.getAndIncrement() >= 2;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch done = new CountDownLatch(2);

    // When the first task schedules a retry that is then lost, a second task arrives,
    // and another runner triggers a reschedule
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    taskQueue.execute(DirectExecutor.INSTANCE, done::countDown);
    taskQueue.run();

    // Then the lost retry must not stick the flag to true forever: a second timer is scheduled
    assertThat(timers).hasSize(2);

    // And firing the healed retry still drives the stranded backlog
    timers.get(1).run();
    awaitCountDown(done);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should fail-open and recover when tryLock throws Error")
  void shouldFailOpenAndRecoverWhenTryLockThrowsError() {
    // Given a lock that throws AssertionError once, then grants
    AssertionError boom = new AssertionError("lock boom");
    AtomicInteger calls = new AtomicInteger();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            if (calls.getAndIncrement() == 0) {
              throw boom;
            }
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch both = new CountDownLatch(2);

    // When the first inline submit hits the Error Then it propagates fail-open with the
    // backlog preserved (not stranded behind a phantom claim)
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> taskQueue.execute(DirectExecutor.INSTANCE, both::countDown))
        .isSameAs(boom);

    // And the next submit resumes the preserved backlog instead of piling behind current != null
    taskQueue.execute(DirectExecutor.INSTANCE, both::countDown);
    awaitCountDown(both);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should fail-open and recover when shouldYield throws Error")
  void shouldFailOpenAndRecoverWhenShouldYieldThrowsError() {
    // Given shouldYield failing once with Error, then healthy
    AssertionError boom = new AssertionError("yield boom");
    AtomicInteger calls = new AtomicInteger();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            if (calls.getAndIncrement() == 0) {
              throw boom;
            }
            return false;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch both = new CountDownLatch(2);

    // When the first batch hits the Error Then it stays loud with backlog preserved
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> taskQueue.execute(DirectExecutor.INSTANCE, both::countDown))
        .isSameAs(boom);

    // And the next submit resumes instead of stranding
    taskQueue.execute(DirectExecutor.INSTANCE, both::countDown);
    awaitCountDown(both);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should fail-open and recover when stats record throws Error")
  void shouldFailOpenAndRecoverWhenStatsRecordThrowsError() {
    // Given stats.record failing once with Error, then healthy
    AssertionError boom = new AssertionError("record boom");
    io.github.jinganix.peashooter.ExecutionStats flaky =
        new ExecutionCountStats() {
          boolean failed;

          @Override
          public void record() {
            if (!failed) {
              failed = true;
              throw boom;
            }
            super.record();
          }
        };
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(flaky, sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch both = new CountDownLatch(2);

    // When the first batch hits the Error Then it stays loud with backlog preserved
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> taskQueue.execute(DirectExecutor.INSTANCE, both::countDown))
        .isSameAs(boom);

    // And the next submit resumes instead of stranding
    taskQueue.execute(DirectExecutor.INSTANCE, both::countDown);
    awaitCountDown(both);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should fail-open and recover when unlock throws Error")
  void shouldFailOpenAndRecoverWhenUnlockThrowsError() {
    // Given unlock failing once with Error, then healthy
    AssertionError boom = new AssertionError("unlock boom");
    AtomicInteger calls = new AtomicInteger();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            if (calls.getAndIncrement() == 0) {
              throw boom;
            }
          }
        };
    CountDownLatch both = new CountDownLatch(2);

    // When the first batch hits the Error Then it stays loud with lock state reset
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> taskQueue.execute(DirectExecutor.INSTANCE, both::countDown))
        .isSameAs(boom);

    // And the next submit resumes instead of poisoning the queue
    taskQueue.execute(DirectExecutor.INSTANCE, both::countDown);
    awaitCountDown(both);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should drop the runner claim when unlock throws Error with pending work")
  void shouldDropRunnerClaimWhenUnlockThrowsErrorWithPendingWork() throws Exception {
    // Given a queue yielding after one task and whose first unlock throws Error
    AssertionError boom = new AssertionError("unlock boom");
    AtomicInteger unlockCalls = new AtomicInteger();
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return stats.getExecutionCount() >= 1;
          }

          @Override
          protected void unlock() {
            if (unlockCalls.getAndIncrement() == 0) {
              throw boom;
            }
          }
        };
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    CountDownLatch errorObserved = new CountDownLatch(1);
    Executor pool =
        newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setUncaughtExceptionHandler(
                  (t, e) -> {
                    uncaught.set(e);
                    errorObserved.countDown();
                  });
              return thread;
            });
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    CountDownLatch pendingRan = new CountDownLatch(1);
    CountDownLatch explicitRan = new CountDownLatch(1);
    try {
      // Given the batch yields while a second task is already pending
      taskQueue.execute(
          pool,
          () -> {
            taskStarted.countDown();
            uncheckedRun(releaseTask::await);
          });
      awaitCountDown(taskStarted);
      taskQueue.execute(pool, pendingRan::countDown);
      releaseTask.countDown();

      // When the batch's unlock throws Error Then it stays loud
      awaitCountDown(errorObserved);
      assertThat(uncaught.get()).isSameAs(boom);

      // Then nothing is auto-scheduled, and an explicit submit resumes the preserved backlog
      // instead of stranding it behind a phantom runner claim
      assertThat(pendingRan.await(200, TimeUnit.MILLISECONDS)).isFalse();
      taskQueue.execute(pool, explicitRan::countDown);
      awaitCountDown(pendingRan);
      awaitCountDown(explicitRan);
      assertThat(taskQueue.isIdle()).isTrue();
    } finally {
      releaseTask.countDown();
      ((java.util.concurrent.ExecutorService) pool).shutdownNow();
    }
  }

  @Test
  @DisplayName("should not auto-retry when unlock throws Error with pending work")
  void shouldNotAutoRetryWhenUnlockThrowsErrorWithPendingWork() throws Exception {
    // Given stats whose first reset blocks until the second task is queued, then throws
    AssertionError boom = new AssertionError("unlock boom");
    AtomicInteger unlockCalls = new AtomicInteger();
    CountDownLatch resetEntered = new CountDownLatch(1);
    CountDownLatch allowResetReturn = new CountDownLatch(1);
    io.github.jinganix.peashooter.ExecutionStats gatedReset =
        new ExecutionCountStats() {
          boolean failed;

          @Override
          public void reset() {
            if (!failed) {
              failed = true;
              // Hold the runner and its claim until the second submission is queued: otherwise
              // that submission could observe the dropped claim and start a legitimate runner
              // of its own, and the "nothing ran" assertion below would fail spuriously.
              resetEntered.countDown();
              uncheckedRun(allowResetReturn::await);
              throw new IllegalStateException("reset boom");
            }
            super.reset();
          }
        };
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(gatedReset, sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {
            if (unlockCalls.getAndIncrement() == 0) {
              throw boom;
            }
          }
        };
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    CountDownLatch errorObserved = new CountDownLatch(1);
    Executor pool =
        newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setUncaughtExceptionHandler(
                  (t, e) -> {
                    uncaught.set(e);
                    errorObserved.countDown();
                  });
              return thread;
            });
    CountDownLatch firstRan = new CountDownLatch(1);
    CountDownLatch secondRan = new CountDownLatch(1);
    CountDownLatch explicitRan = new CountDownLatch(1);
    try {
      // Given one task running behind the gated reset
      taskQueue.execute(pool, firstRan::countDown);
      awaitCountDown(resetEntered);

      // And a second task enqueued while the first runner still holds the claim
      taskQueue.execute(pool, secondRan::countDown);
      allowResetReturn.countDown();

      // When the batch's unlock throws Error Then it stays loud
      awaitCountDown(errorObserved);
      assertThat(uncaught.get()).isSameAs(boom);

      // Then the preserved backlog is auto-scheduled nowhere before an explicit submit
      assertThat(firstRan.await(300, TimeUnit.MILLISECONDS)).isFalse();
      assertThat(secondRan.await(300, TimeUnit.MILLISECONDS)).isFalse();
      taskQueue.execute(pool, explicitRan::countDown);
      awaitCountDown(firstRan);
      awaitCountDown(secondRan);
      awaitCountDown(explicitRan);
      assertThat(taskQueue.isIdle()).isTrue();
    } finally {
      allowResetReturn.countDown();
      ((java.util.concurrent.ExecutorService) pool).shutdownNow();
    }
  }

  @Test
  @DisplayName("should propagate Error and recover when stats reset throws Error")
  void shouldPropagateErrorAndRecoverWhenStatsResetThrowsError() {
    // Given stats failing once with an Error, then healthy
    AssertionError boom = new AssertionError("reset boom");
    io.github.jinganix.peashooter.ExecutionStats flaky =
        new ExecutionCountStats() {
          boolean failed;

          @Override
          public void reset() {
            if (!failed) {
              failed = true;
              throw boom;
            }
            super.reset();
          }
        };
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(flaky, sharedTestScheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return true;
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch both = new CountDownLatch(2);

    // When the first batch fails reset Then the Error stays loud with the claim dropped
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> taskQueue.execute(DirectExecutor.INSTANCE, both::countDown))
        .isSameAs(boom);

    // And the next submit resumes instead of stranding
    taskQueue.execute(DirectExecutor.INSTANCE, both::countDown);
    awaitCountDown(both);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should recover the backlog when the retry scheduler is shut down")
  void shouldRecoverBacklogWhenRetrySchedulerIsShutDown() throws Exception {
    // Given a lock-contended queue whose retry scheduler is shut down before the retry fires
    ScheduledThreadPoolExecutor rescheduler = new ScheduledThreadPoolExecutor(1);
    java.util.concurrent.atomic.AtomicBoolean lockOpen =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    LockableTaskQueue taskQueue =
        new LockableTaskQueue(new ExecutionCountStats(), rescheduler) {
          @Override
          protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
            return lockOpen.get();
          }

          @Override
          protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
            return false;
          }

          @Override
          protected void unlock() {}
        };
    CountDownLatch workerBusy = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    try {
      // Park the only worker so the reserved retry stays queued instead of firing
      rescheduler.execute(
          () -> {
            workerBusy.countDown();
            uncheckedRun(releaseWorker::await);
          });
      assertThat(workerBusy.await(5, TimeUnit.SECONDS)).isTrue();
      CountDownLatch firstDone = new CountDownLatch(1);
      taskQueue.execute(DirectExecutor.INSTANCE, firstDone::countDown);
      assertThat(taskQueue.hasPending()).isTrue();

      // When the scheduler is shut down, draining the queued retry without completing its future
      rescheduler.shutdownNow();
      assertThat(rescheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

      // Then the next submit reclaims the dead retry claim and drains the preserved backlog
      lockOpen.set(true);
      CountDownLatch secondDone = new CountDownLatch(1);
      taskQueue.execute(DirectExecutor.INSTANCE, secondDone::countDown);
      awaitCountDown(firstDone);
      awaitCountDown(secondDone);
      assertThat(taskQueue.isIdle()).isTrue();
    } finally {
      releaseWorker.countDown();
      rescheduler.shutdownNow();
    }
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
    throw (E) throwable;
  }
}
