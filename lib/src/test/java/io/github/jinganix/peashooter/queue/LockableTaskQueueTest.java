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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LockableTaskQueue")
class LockableTaskQueueTest {

  @Test
  @DisplayName("should run enqueued task after tryLock fails then succeeds")
  void shouldRunEnqueuedTaskAfterTryLockFailsThenSucceeds() {
    // Given
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
    AtomicInteger tryLockCalls = new AtomicInteger();
    when(taskQueue.tryLock(any())).thenAnswer(inv -> tryLockCalls.getAndIncrement() > 0);
    CountDownLatch executed = new CountDownLatch(1);

    // When
    taskQueue.execute(DirectExecutor.INSTANCE, executed::countDown);

    // Then
    awaitCountDown(executed);
  }

  @Test
  @DisplayName("should not unlock when lock cannot be acquired")
  void shouldNotUnlockWhenLockCannotBeAcquired() {
    // Given
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
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
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
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
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
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
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
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
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
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
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
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
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
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
  @DisplayName("should propagate rejection when executor rejects on an idle queue")
  void shouldPropagateRejectionWhenExecutorRejectsOnAnIdleQueue() {
    // Given
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
    when(taskQueue.tryLock(any())).thenReturn(true);
    Executor executor = mock(Executor.class);
    RejectedExecutionException exception = new RejectedExecutionException();
    doThrow(exception).when(executor).execute(any());

    // When / Then
    assertThatThrownBy(() -> taskQueue.execute(executor, () -> {})).isEqualTo(exception);
  }

  @Test
  @DisplayName("should not run enqueued task when executor rejects while runner is active")
  void shouldNotRunEnqueuedTaskWhenExecutorRejectsWhileRunnerIsActive()
      throws InterruptedException {
    // Given
    LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
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
}
