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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LockableTaskQueue")
class LockableTaskQueueTest {

  @Nested
  @DisplayName("run")
  class Run {

    @Test
    @DisplayName("Given not locked -> should not unlock")
    void givenNotLocked() {
      // Given
      LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
      when(taskQueue.tryLock(any())).thenReturn(false);

      // When
      taskQueue.run();

      // Then
      verify(taskQueue, never()).unlock();
    }

    @Test
    @DisplayName("Given locked and no tasks -> should not check yield")
    void givenLockedAndNoTasks() {
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
    @DisplayName("Given should yield with single task -> should lock twice")
    void givenShouldYieldWithSingleTask() throws InterruptedException {
      // Given
      LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
      when(taskQueue.tryLock(any())).thenReturn(true);
      when(taskQueue.shouldYield(any())).thenReturn(true);

      CountDownLatch latch = new CountDownLatch(1);

      // When
      taskQueue.execute(DirectExecutor.INSTANCE, latch::countDown);
      latch.await();

      // Then
      verify(taskQueue, times(1)).shouldYield(any());
      verify(taskQueue, times(2)).tryLock(any());
      verify(taskQueue, times(2)).unlock();
    }

    @Test
    @DisplayName("Given should not yield with single task -> should lock once")
    void givenShouldNotYieldWithSingleTask() throws InterruptedException {
      // Given
      LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
      when(taskQueue.tryLock(any())).thenReturn(true);
      when(taskQueue.shouldYield(any())).thenReturn(false);

      CountDownLatch latch = new CountDownLatch(1);

      // When
      taskQueue.execute(DirectExecutor.INSTANCE, latch::countDown);
      latch.await();

      // Then
      verify(taskQueue, times(1)).shouldYield(any());
      verify(taskQueue, times(1)).tryLock(any());
      verify(taskQueue, times(1)).unlock();
    }

    void executeTwoTasks(TaskQueue taskQueue) throws InterruptedException {
      CountDownLatch latch1 = new CountDownLatch(1);
      CountDownLatch latch2 = new CountDownLatch(1);
      CountDownLatch latch3 = new CountDownLatch(1);

      new Thread(
              () ->
                  taskQueue.execute(
                      command -> {
                        latch1.countDown();
                        uncheckedRun(latch2::await);
                        command.run();
                      },
                      () -> {}))
          .start();
      latch1.await();

      taskQueue.execute(
          command -> {
            command.run();
            latch3.countDown();
          },
          () -> {});
      latch2.countDown();
      latch3.await();
    }

    @Test
    @DisplayName("Given should yield first with two tasks -> should lock twice")
    void givenShouldYieldFirstWithTwoTasks() throws InterruptedException {
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
    @DisplayName("Given should not yield with two tasks -> should lock once")
    void givenShouldNotYieldWithTwoTasks() throws InterruptedException {
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
    @DisplayName("Given first task throws error -> should run tasks sequentially")
    void givenFirstTaskThrowsError() throws InterruptedException {
      // Given
      LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
      when(taskQueue.tryLock(any())).thenReturn(true);

      CountDownLatch latch = new CountDownLatch(1);

      // When
      taskQueue.execute(
          newSingleThreadExecutor(),
          () -> {
            sleep(100);
            throw new RuntimeException("error");
          });

      AtomicReference<Long> ref = new AtomicReference<>();
      long millis = System.currentTimeMillis();
      taskQueue.execute(
          newSingleThreadExecutor(),
          () -> {
            ref.set(System.currentTimeMillis() - millis);
            latch.countDown();
          });

      latch.await();

      // Then
      assertThat(ref.get()).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("Given executor throws error and tasks empty -> should throw exception")
    void givenExecutorThrowsErrorAndTasksEmpty() {
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
    @DisplayName("Given executor throws error and tasks not empty -> should not call task")
    void givenExecutorThrowsErrorAndTasksNotEmpty() throws InterruptedException {
      // Given
      LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
      when(taskQueue.tryLock(any())).thenReturn(true);

      Executor executor = mock(Executor.class);
      doThrow(new RejectedExecutionException()).when(executor).execute(any());
      Runnable task = mock(Runnable.class);

      CountDownLatch latch1 = new CountDownLatch(1);
      CountDownLatch latch2 = new CountDownLatch(1);
      CountDownLatch latch3 = new CountDownLatch(1);

      new Thread(
              () ->
                  taskQueue.execute(
                      command -> {
                        latch1.countDown();
                        uncheckedRun(latch2::await);
                        command.run();
                        latch3.countDown();
                      },
                      () -> {}))
          .start();
      latch1.await();

      // When
      taskQueue.execute(executor, task);
      latch2.countDown();
      latch3.await();

      // Then
      verify(executor, times(1)).execute(any());
      verify(task, never()).run();
    }
  }
}
