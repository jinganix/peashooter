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
