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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue")
class TaskQueueTest {

  Executor createExecutor() {
    return newSingleThreadExecutor();
  }

  @Test
  @DisplayName("should run tasks sequentially when two tasks use the same executor")
  void shouldRunTasksSequentiallyWhenTwoTasksUseTheSameExecutor() {
    // Given
    TaskQueue taskQueue = new TaskQueue();
    long startMillis = System.currentTimeMillis();

    // When
    taskQueue.execute(createExecutor(), () -> sleep(100));
    AtomicReference<Long> elapsed = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    taskQueue.execute(
        createExecutor(),
        () -> {
          elapsed.set(System.currentTimeMillis() - startMillis);
          latch.countDown();
        });
    awaitCountDown(latch);

    // Then
    assertThat(elapsed.get()).isGreaterThanOrEqualTo(100);
  }

  @Test
  @DisplayName("should run tasks sequentially when two tasks use different executors")
  void shouldRunTasksSequentiallyWhenTwoTasksUseDifferentExecutors() {
    // Given
    TaskQueue taskQueue = new TaskQueue();
    long startMillis = System.currentTimeMillis();

    // When
    taskQueue.execute(createExecutor(), () -> sleep(100));
    AtomicReference<Long> elapsed = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    taskQueue.execute(
        createExecutor(),
        () -> {
          elapsed.set(System.currentTimeMillis() - startMillis);
          latch.countDown();
        });
    awaitCountDown(latch);

    // Then
    assertThat(elapsed.get()).isGreaterThanOrEqualTo(100);
  }

  @Test
  @DisplayName("should run next task when the first task throws")
  void shouldRunNextTaskWhenTheFirstTaskThrows() {
    // Given
    TaskQueue taskQueue = new TaskQueue();
    long startMillis = System.currentTimeMillis();

    // When
    taskQueue.execute(
        createExecutor(),
        () -> {
          sleep(100);
          throw new RuntimeException("error");
        });
    AtomicReference<Long> elapsed = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    taskQueue.execute(
        createExecutor(),
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
    TaskQueue taskQueue = new TaskQueue();
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
    TaskQueue taskQueue = new TaskQueue();
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
  @DisplayName("should finish prior queued tasks when a later submit is rejected")
  void shouldFinishPriorQueuedTasksWhenALaterSubmitIsRejected() throws InterruptedException {
    // Given
    TaskQueue taskQueue = new TaskQueue();
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

    Executor worker = createExecutor();
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
  @DisplayName("should reject only the failing submit when queue is idle after prior work")
  void shouldRejectOnlyTheFailingSubmitWhenQueueIsIdleAfterPriorWork() throws InterruptedException {
    // Given
    TaskQueue taskQueue = new TaskQueue();
    AtomicInteger completed = new AtomicInteger(0);
    Executor rejecting = mock(Executor.class);
    RejectedExecutionException exception = new RejectedExecutionException();
    doThrow(exception).when(rejecting).execute(any());

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
    awaitCountDown(priorTasksDone);
    assertThat(completed.get()).isEqualTo(3);

    Runnable rejected = mock(Runnable.class);

    // When / Then
    assertThatThrownBy(() -> taskQueue.execute(rejecting, rejected)).isEqualTo(exception);
    verify(rejected, never()).run();
    assertThat(completed.get()).isEqualTo(3);
  }

  @Test
  @DisplayName("should report not empty when rejection in run leaves tasks queued")
  void shouldReportNotEmptyWhenRejectionInRunLeavesTasksQueued() throws InterruptedException {
    // Given
    TaskQueue taskQueue = new TaskQueue();
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException()).when(rejecting).execute(any());

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
    taskQueue.execute(rejecting, () -> {});
    releaseRunner.countDown();
    awaitCountDown(runnerFinished);

    // Then
    assertThat(taskQueue.isEmpty()).isFalse();
  }

  @Test
  @DisplayName("should report empty when no tasks are queued or running")
  void shouldReportEmptyWhenNoTasksAreQueuedOrRunning() {
    // When / Then
    assertThat(new TaskQueue().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("should report not empty when a task is queued")
  void shouldReportNotEmptyWhenATaskIsQueued() {
    // Given
    TaskQueue queue = new TaskQueue();

    // When
    queue.execute(x -> {}, () -> {});

    // Then
    assertThat(queue.isEmpty()).isFalse();
  }

  @Test
  @DisplayName("should report not empty when runner is scheduled but task has not finished")
  void shouldReportNotEmptyWhenRunnerIsScheduledButTaskHasNotFinished() {
    // Given
    TaskQueue queue = new TaskQueue();
    CountDownLatch releaseTask = new CountDownLatch(1);

    // When: no-op executor leaves runner scheduled with current set
    queue.execute(x -> {}, () -> uncheckedRun(releaseTask::await));

    // Then
    assertThat(queue.isEmpty()).isFalse();
    releaseTask.countDown();
  }
}
