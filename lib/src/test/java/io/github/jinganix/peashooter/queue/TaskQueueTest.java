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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue")
class TaskQueueTest {

  Executor createExecutor() {
    return newSingleThreadExecutor();
  }

  @Nested
  @DisplayName("execute")
  class Execute {

    @Test
    @DisplayName("Given run two tasks -> should run tasks sequentially")
    void givenRunTwoTasks() throws InterruptedException {
      // Given
      TaskQueue taskQueue = new TaskQueue();
      Executor executor = createExecutor();

      // When
      taskQueue.execute(executor, () -> sleep(100));
      AtomicReference<Long> ref = new AtomicReference<>();
      long millis = System.currentTimeMillis();

      CountDownLatch latch = new CountDownLatch(1);
      taskQueue.execute(
          createExecutor(),
          () -> {
            ref.set(System.currentTimeMillis() - millis);
            latch.countDown();
          });
      latch.await();

      // Then
      assertThat(ref.get()).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("Given run two executors -> should run tasks sequentially")
    void givenRunTwoExecutors() throws InterruptedException {
      // Given
      TaskQueue taskQueue = new TaskQueue();

      // When
      taskQueue.execute(createExecutor(), () -> sleep(100));
      AtomicReference<Long> ref = new AtomicReference<>();
      long millis = System.currentTimeMillis();

      CountDownLatch latch = new CountDownLatch(1);
      taskQueue.execute(
          createExecutor(),
          () -> {
            ref.set(System.currentTimeMillis() - millis);
            latch.countDown();
          });
      latch.await();

      // Then
      assertThat(ref.get()).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("Given first task throws errors -> should run tasks sequentially")
    void givenFirstTaskThrowsErrors() throws InterruptedException {
      // Given
      TaskQueue taskQueue = new TaskQueue();

      // When
      taskQueue.execute(
          createExecutor(),
          () -> {
            sleep(100);
            throw new RuntimeException("error");
          });

      AtomicReference<Long> ref = new AtomicReference<>();
      long millis = System.currentTimeMillis();

      CountDownLatch latch = new CountDownLatch(1);
      taskQueue.execute(
          createExecutor(),
          () -> {
            ref.set(System.currentTimeMillis() - millis);
            latch.countDown();
          });
      latch.await();

      // Then
      assertThat(ref.get()).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("Given executor throws errors with empty tasks -> should throw exception")
    void givenExecutorThrowsErrorsWithEmptyTasks() {
      // Given
      TaskQueue taskQueue = new TaskQueue();
      Executor executor = mock(Executor.class);
      RejectedExecutionException exception = new RejectedExecutionException();
      doThrow(exception).when(executor).execute(any());

      // When / Then
      assertThatThrownBy(() -> taskQueue.execute(executor, () -> {})).isEqualTo(exception);
    }

    @Test
    @DisplayName("Given executor throws errors with non-empty tasks -> should not call task")
    void givenExecutorThrowsErrorsWithNonEmptyTasks() throws InterruptedException {
      // Given
      TaskQueue taskQueue = new TaskQueue();
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

    @Test
    @DisplayName("Given runner active and rejected submit -> prior queued tasks still complete")
    void givenRunnerActiveAndRejectedSubmit_priorQueuedTasksStillComplete()
        throws InterruptedException {
      // Given
      TaskQueue taskQueue = new TaskQueue();
      AtomicInteger completed = new AtomicInteger(0);
      Executor rejecting = mock(Executor.class);
      doThrow(new RejectedExecutionException()).when(rejecting).execute(any());
      Runnable rejected = mock(Runnable.class);

      CountDownLatch runnerScheduled = new CountDownLatch(1);
      CountDownLatch releaseRunner = new CountDownLatch(1);
      CountDownLatch priorTasksDone = new CountDownLatch(3);

      new Thread(
              () ->
                  taskQueue.execute(
                      command -> {
                        runnerScheduled.countDown();
                        uncheckedRun(releaseRunner::await);
                        command.run();
                      },
                      () -> {}))
          .start();
      runnerScheduled.await();

      Executor worker = createExecutor();
      Runnable countAndSignal =
          () -> {
            completed.incrementAndGet();
            priorTasksDone.countDown();
          };
      taskQueue.execute(worker, countAndSignal);
      taskQueue.execute(worker, countAndSignal);
      taskQueue.execute(worker, countAndSignal);

      // When: submit on test thread while runner is held; task is only enqueued
      taskQueue.execute(rejecting, rejected);
      releaseRunner.countDown();
      priorTasksDone.await();

      // Then: prior tasks finished; rejected task stalled on executor switch, not run
      assertThat(completed.get()).isEqualTo(3);
      verify(rejected, never()).run();
    }

    @Test
    @DisplayName(
        "Given rejected submit throws on idle queue -> other thread tasks already completed")
    void givenRejectedSubmitThrows_otherThreadTasksAlreadyCompleted() throws InterruptedException {
      // Given: other thread drains three tasks first
      TaskQueue taskQueue = new TaskQueue();
      AtomicInteger completed = new AtomicInteger(0);
      Executor rejecting = mock(Executor.class);
      RejectedExecutionException exception = new RejectedExecutionException();
      doThrow(exception).when(rejecting).execute(any());

      CountDownLatch runnerScheduled = new CountDownLatch(1);
      CountDownLatch releaseRunner = new CountDownLatch(1);
      CountDownLatch priorTasksDone = new CountDownLatch(3);

      new Thread(
              () ->
                  taskQueue.execute(
                      command -> {
                        runnerScheduled.countDown();
                        uncheckedRun(releaseRunner::await);
                        command.run();
                      },
                      () -> {}))
          .start();
      runnerScheduled.await();

      Executor worker = createExecutor();
      Runnable countAndSignal =
          () -> {
            completed.incrementAndGet();
            priorTasksDone.countDown();
          };
      taskQueue.execute(worker, countAndSignal);
      taskQueue.execute(worker, countAndSignal);
      taskQueue.execute(worker, countAndSignal);
      releaseRunner.countDown();
      priorTasksDone.await();

      assertThat(completed.get()).isEqualTo(3);

      Runnable rejected = mock(Runnable.class);

      // When / Then: idle queue rejects only this submission; prior work is unchanged
      assertThatThrownBy(() -> taskQueue.execute(rejecting, rejected)).isEqualTo(exception);
      verify(rejected, never()).run();
      assertThat(completed.get()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("isEmpty")
  class IsEmpty {

    @Test
    @DisplayName("Given no tasks and current is null -> should return true")
    void givenNoTasksAndCurrentIsNull() {
      // When / Then
      assertThat(new TaskQueue().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Given has tasks and current is not null -> should return false")
    void givenHasTasksAndCurrentIsNotNull() {
      // Given
      TaskQueue queue = new TaskQueue();

      // When
      queue.execute(x -> {}, () -> {});

      // Then
      assertThat(queue.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("Given no tasks and current is not null -> should return false")
    void givenNoTasksAndCurrentIsNotNull() {
      // Given
      TaskQueue queue = new TaskQueue();
      CountDownLatch latch = new CountDownLatch(1);

      // When
      queue.execute(x -> {}, () -> uncheckedRun(latch::await));

      // Then
      assertThat(queue.isEmpty()).isFalse();
      latch.countDown();
    }
  }
}
