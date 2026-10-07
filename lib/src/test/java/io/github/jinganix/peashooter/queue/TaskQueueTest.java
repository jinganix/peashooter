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
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue")
class TaskQueueTest {

  Executor createExecutor() {
    return newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable);
          thread.setDaemon(true);
          return thread;
        });
  }

  @Test
  @org.junit.jupiter.api.Timeout(10)
  @DisplayName("should not stack overflow on many alternating custom inline handoffs")
  void shouldNotStackOverflowOnManyAlternatingCustomInlineHandoffs() throws InterruptedException {
    // Given two distinct inline executors (sync vs async is decided by thread identity)
    TaskQueue taskQueue = new TaskQueue();
    Executor inlineA = Runnable::run;
    Executor inlineB = Runnable::run;
    int count = 100000;
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
    for (int i = 0; i < count; i++) {
      taskQueue.execute((i % 2 == 0) ? inlineA : inlineB, done::countDown);
    }
    releaseRunner.countDown();

    awaitCountDown(done);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @org.junit.jupiter.api.Timeout(10)
  @DisplayName("should keep queued order when a hook failure removes its trigger")
  void shouldKeepQueuedOrderWhenAHookFailureRemovesItsTrigger() {
    // Given a runner blocked on the first task with two tasks queued behind it, where the
    // third submit fails its pre-enqueue hook
    java.util.List<String> order =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    AtomicInteger calls = new AtomicInteger();
    TaskQueue queue =
        new TaskQueue() {
          @Override
          protected boolean onEnqueueLocked() {
            if (calls.incrementAndGet() == 3) {
              throw new RuntimeException("hook boom");
            }
            return false;
          }
        };
    Executor background = createExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    queue.execute(
        background,
        () -> {
          order.add("first");
          entered.countDown();
          uncheckedRun(release::await);
        });
    awaitCountDown(entered);
    queue.execute(background, () -> order.add("second"));

    // When the third submit fails its hook Then only its trigger is removed
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> queue.execute(background, () -> {}))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("hook boom");

    // And the surviving backlog still drains in submission order
    queue.execute(background, () -> order.add("fourth"));
    release.countDown();
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .until(() -> order.size() == 3);
    assertThat(order).containsExactly("first", "second", "fourth");
    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(queue::isIdle);
  }

  @Test
  @org.junit.jupiter.api.Timeout(10)
  @DisplayName("should preserve runner claim when enqueue hook fails with active runner")
  void shouldPreserveRunnerClaimWhenEnqueueHookFailsWithActiveRunner() throws Exception {
    // Given a queue with a blocking task on an async runner
    java.util.concurrent.atomic.AtomicBoolean failHook =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    TaskQueue taskQueue =
        new TaskQueue() {
          @Override
          protected boolean onEnqueueLocked() {
            if (failHook.get()) {
              throw new IllegalStateException("hook boom");
            }
            return false;
          }
        };
    Executor async = newSingleThreadExecutor();
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    CountDownLatch taskDone = new CountDownLatch(1);
    taskQueue.execute(
        async,
        () -> {
          taskStarted.countDown();
          uncheckedRun(releaseTask::await);
          taskDone.countDown();
        });
    // Wait until the runner is in the task body (claim held, outside monitor)
    if (!taskStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
      throw new IllegalStateException("runner did not start");
    }

    // When an enqueue hook fails while the old runner is still active Then only the trigger
    // fails visibly while the runner claim is preserved
    failHook.set(true);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskQueue.execute(async, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hook boom");
    failHook.set(false);

    // Then the runner claim must be preserved: not idle while the task body still runs,
    // otherwise a later submit could start a second concurrent runner
    assertThat(taskQueue.isIdle()).isFalse();

    // And the queue recovers: releasing the task drains and idles without a second runner
    releaseTask.countDown();
    awaitCountDown(taskDone);
    // Poll for idle (runner needs a moment to loop and clear)
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (!taskQueue.isIdle() && System.nanoTime() < deadline) {
      sleep(10);
    }
    assertThat(taskQueue.isIdle()).isTrue();
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
  @DisplayName("should preserve queued tasks when a concurrent submit is rejected")
  void shouldPreserveQueuedTasksWhenConcurrentSubmitIsRejected() throws InterruptedException {
    // Given a runner parked on a rejecting head with a second task queued behind it
    TaskQueue taskQueue = new TaskQueue();
    CountDownLatch rejectorEntered = new CountDownLatch(1);
    CountDownLatch releaseRejector = new CountDownLatch(1);
    AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    Executor rejecting =
        command -> {
          rejectorEntered.countDown();
          uncheckedRun(releaseRejector::await);
          throw new RejectedExecutionException();
        };
    Executor worker = createExecutor();
    CountDownLatch secondDone = new CountDownLatch(1);
    AtomicBoolean secondRan = new AtomicBoolean(false);
    AtomicReference<Throwable> secondRejected = new AtomicReference<>();
    class Second implements Runnable, RejectionAware {
      @Override
      public void run() {
        secondRan.set(true);
        secondDone.countDown();
      }

      @Override
      public void rejected(Throwable cause) {
        secondRejected.set(cause);
      }
    }

    // When the head submit fails to schedule while the second task waits behind it
    Thread submitter =
        new Thread(
            () -> {
              try {
                taskQueue.execute(rejecting, () -> {});
              } catch (Throwable e) {
                backgroundFailure.set(e);
              }
            });
    submitter.start();
    rejectorEntered.await();
    taskQueue.execute(worker, new Second());
    releaseRejector.countDown();
    submitter.join(5000);

    // Then the trigger fails visibly while the queued task is preserved, not dropped with it.
    // The piggybacking submit joined the failed runner claim, so it is preserved without being
    // notified: only the triggering submit observes the rejection.
    assertThat(backgroundFailure.get()).isInstanceOf(RejectedExecutionException.class);
    assertThat(secondRejected.get()).isNull();
    assertThat(taskQueue.isIdle()).isFalse();

    // And the preserved backlog still drains on the next submission
    CountDownLatch resume = new CountDownLatch(1);
    taskQueue.execute(worker, resume::countDown);
    awaitCountDown(secondDone);
    awaitCountDown(resume);
    assertThat(secondRan.get()).isTrue();
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should contain a throwing rejection callback and preserve the rest")
  void shouldContainAThrowingRejectionCallbackAndPreserveTheRest() throws InterruptedException {
    // Given a parked runner with a rejection-aware head whose callback throws, plus a survivor
    TaskQueue taskQueue = new TaskQueue();
    CountDownLatch rejectorEntered = new CountDownLatch(1);
    CountDownLatch releaseRejector = new CountDownLatch(1);
    AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    Executor rejecting =
        command -> {
          rejectorEntered.countDown();
          uncheckedRun(releaseRejector::await);
          throw new RejectedExecutionException("saturated");
        };
    Runnable first = mock(Runnable.class, withSettings().extraInterfaces(RejectionAware.class));
    doThrow(new RuntimeException("callback boom")).when((RejectionAware) first).rejected(any());

    // When the head submit fails to schedule
    Thread submitter =
        new Thread(
            () -> {
              try {
                taskQueue.execute(rejecting, first);
              } catch (Throwable e) {
                backgroundFailure.set(e);
              }
            });
    submitter.start();
    rejectorEntered.await();
    CountDownLatch survivorDone = new CountDownLatch(1);
    Executor worker = createExecutor();
    taskQueue.execute(worker, survivorDone::countDown);
    releaseRejector.countDown();
    submitter.join(5000);

    // Then the original rejection still reaches the submitter (callback failure suppressed, logged)
    assertThat(backgroundFailure.get()).isInstanceOf(RejectedExecutionException.class);
    assertThat(backgroundFailure.get().getSuppressed())
        .anySatisfy(suppressed -> assertThat(suppressed).hasMessageContaining("callback boom"));

    // And the survivor is preserved and still drains instead of being dropped with the trigger
    CountDownLatch resume = new CountDownLatch(1);
    taskQueue.execute(worker, resume::countDown);
    awaitCountDown(survivorDone);
    awaitCountDown(resume);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should contain an Error rejection callback and preserve the rest")
  void shouldContainAnErrorRejectionCallbackAndPreserveTheRest() throws InterruptedException {
    // Given a parked runner with a rejection-aware head whose callback throws Error, plus survivor
    TaskQueue taskQueue = new TaskQueue();
    CountDownLatch rejectorEntered = new CountDownLatch(1);
    CountDownLatch releaseRejector = new CountDownLatch(1);
    AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    Executor rejecting =
        command -> {
          rejectorEntered.countDown();
          uncheckedRun(releaseRejector::await);
          throw new RejectedExecutionException("saturated");
        };
    Runnable first = mock(Runnable.class, withSettings().extraInterfaces(RejectionAware.class));
    doThrow(new AssertionError("callback boom")).when((RejectionAware) first).rejected(any());

    // When the head submit fails to schedule Then the callback Error neither masks the
    // original rejection nor escapes to the background thread
    Thread submitter =
        new Thread(
            () -> {
              try {
                taskQueue.execute(rejecting, first);
              } catch (Throwable e) {
                backgroundFailure.set(e);
              }
            });
    submitter.start();
    rejectorEntered.await();
    CountDownLatch survivorDone = new CountDownLatch(1);
    Executor worker = createExecutor();
    taskQueue.execute(worker, survivorDone::countDown);
    releaseRejector.countDown();
    submitter.join(5000);

    assertThat(backgroundFailure.get()).isInstanceOf(RejectedExecutionException.class);
    assertThat(backgroundFailure.get().getSuppressed())
        .anySatisfy(suppressed -> assertThat(suppressed).hasMessageContaining("callback boom"));

    // And the survivor is preserved and still drains
    CountDownLatch resume = new CountDownLatch(1);
    taskQueue.execute(worker, resume::countDown);
    awaitCountDown(survivorDone);
    awaitCountDown(resume);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should dispatch rejection notification via the single Throwable path")
  void shouldDispatchRejectionNotificationViaTheSingleThrowablePath() {
    // Given a queue with a rejection-aware task
    TaskQueue taskQueue = new TaskQueue();
    Runnable task = mock(Runnable.class, withSettings().extraInterfaces(RejectionAware.class));
    RuntimeException cause = new RejectedExecutionException("rejected");

    // When the rejection path notifies the trigger directly
    taskQueue.notifyDiscarded(task, cause);

    // Then the task observes the single direct notification
    verify((RejectionAware) task, times(1)).rejected(cause);
  }

  @Test
  @DisplayName("should propagate Error cause when executor fails with Error")
  void shouldPropagateErrorCauseWhenExecutorFailsWithError() {
    // Given an executor that fails with an Error and a rejection-aware task
    TaskQueue taskQueue = new TaskQueue();
    AssertionError failure = new AssertionError("executor boom");
    Executor failing = mock(Executor.class);
    doThrow(failure).when(failing).execute(any());
    Runnable task = mock(Runnable.class, withSettings().extraInterfaces(RejectionAware.class));

    // When the submission is rejected Then the original Error propagates to the submitter
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskQueue.execute(failing, task))
        .isSameAs(failure);

    // And the trigger observes the original Error (not a wrapper) via the Throwable overload
    verify((RejectionAware) task, times(1)).rejected(failure);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should surface failure when executor throws non-rejection on idle queue")
  void shouldSurfaceFailureWhenExecutorThrowsNonRejectionOnIdleQueue() {
    // Given an executor that fails synchronously on an idle queue
    TaskQueue taskQueue = new TaskQueue();
    Executor bad = mock(Executor.class);
    doThrow(new IllegalStateException("executor failed")).when(bad).execute(any());

    // When the triggering submit fails Then it surfaces to the submitter, not swallowed
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskQueue.execute(bad, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("executor failed");

    // And the queue stays usable for the next submission
    CountDownLatch latch = new CountDownLatch(1);
    taskQueue.execute(createExecutor(), latch::countDown);

    // Then
    awaitCountDown(latch);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should reject submit when executor rejects on an idle queue")
  void shouldRejectSubmitWhenExecutorRejectsOnAnIdleQueue() {
    // Given a saturated executor on an idle queue
    TaskQueue taskQueue = new TaskQueue();
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
  @DisplayName("should fail only the triggering submit when queue is idle after prior work")
  void shouldFailOnlyTheTriggeringSubmitWhenQueueIsIdleAfterPriorWork()
      throws InterruptedException {
    // Given
    TaskQueue taskQueue = new TaskQueue();
    AtomicInteger completed = new AtomicInteger(0);
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException()).when(rejecting).execute(any());

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

    // When the saturated executor rejects the trigger Then it fails visibly, prior work intact
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskQueue.execute(rejecting, rejected))
        .isInstanceOf(RejectedExecutionException.class);
    verify(rejected, never()).run();
    assertThat(completed.get()).isEqualTo(3);
  }

  @Test
  @DisplayName("should reject only the saturated head when executor rejects during handoff in run")
  void shouldRejectOnlyTheSaturatedHeadWhenExecutorRejectsDuringHandoffInRun()
      throws InterruptedException {
    // Given
    TaskQueue taskQueue = new TaskQueue();
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException()).when(rejecting).execute(any());
    Executor worker = createExecutor();
    CountDownLatch firstTaskStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstTask = new CountDownLatch(1);
    Runnable discarded = mock(Runnable.class);

    new Thread(
            () ->
                taskQueue.execute(
                    worker,
                    () -> {
                      firstTaskStarted.countDown();
                      uncheckedRun(releaseFirstTask::await);
                    }))
        .start();
    firstTaskStarted.await();

    // When
    taskQueue.execute(rejecting, discarded);
    releaseFirstTask.countDown();
    sleep(200);

    // Then
    verify(discarded, never()).run();
  }

  @Test
  @DisplayName("should report empty when rejection in run rejects only its head")
  void shouldReportEmptyWhenRejectionInRunDiscardsTask() throws InterruptedException {
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
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should stay usable when executor throws Error on idle queue")
  void
      shouldStayUsableWhenExecutorThrowsErrorOnIdleQueue() { // Given an executor that fails with an
    // Error (not RuntimeException)
    TaskQueue taskQueue = new TaskQueue();
    AssertionError failure = new AssertionError("executor boom");
    Executor bad =
        command -> {
          throw failure;
        };
    Runnable rejected = mock(Runnable.class);

    // When / Then the scheduling failure surfaces without stranding the queue
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskQueue.execute(bad, rejected))
        .isSameAs(failure);
    verify(rejected, never()).run();
    CountDownLatch latch = new CountDownLatch(1);
    taskQueue.execute(createExecutor(), latch::countDown);
    awaitCountDown(latch);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should expose idle state across submissions")
  void shouldExposeIdleStateAcrossSubmissions() {
    TaskQueue queue = new TaskQueue();
    assertThat(queue.isIdle()).isTrue();
    CountDownLatch latch = new CountDownLatch(1);
    queue.execute(createExecutor(), latch::countDown);
    awaitCountDown(latch);
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should drain alternating Direct handoffs in FIFO order without extra frames")
  void shouldDrainDeepAlternatingHandoffsWithoutRecursionOverflow() throws InterruptedException {
    // Given a runner parked on a blocking head task while Direct/pool tasks pile up
    // (Direct switches loop in-frame instead of recursing one frame per switch)
    TaskQueue taskQueue = new TaskQueue();
    Executor pool = newSingleThreadExecutor();
    Executor drainPool = newSingleThreadExecutor();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    int total = 5_000;
    CountDownLatch done = new CountDownLatch(total);
    java.util.List<Integer> order =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    taskQueue.execute(
        pool,
        () -> {
          started.countDown();
          uncheckedRun(release::await);
        });
    started.await();
    for (int i = 0; i < total; i++) {
      int sequence = i;
      Executor exec = (i & 1) == 0 ? DirectExecutor.INSTANCE : drainPool;
      taskQueue.execute(
          exec,
          () -> {
            order.add(sequence);
            done.countDown();
          });
    }

    // When the head unblocks, the whole backlog must drain strictly in submission order
    release.countDown();
    awaitCountDown(done);

    // Then
    assertThat(order)
        .containsExactlyElementsOf(java.util.stream.IntStream.range(0, total).boxed().toList());
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should preserve FIFO for deep custom inline handoffs without recursion")
  void shouldPreserveFifoForShallowCustomInlineHandoffs() throws InterruptedException {
    // Given two distinct pseudo-sync executors alternating (plain Runnable::run, no marker):
    // thread-identity trampoline loops in-frame, so deep chains drain with bounded depth.
    TaskQueue taskQueue = new TaskQueue();
    Executor inlineA = Runnable::run;
    Executor inlineB = Runnable::run;
    Executor pool = newSingleThreadExecutor();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    int total = 5000;
    CountDownLatch done = new CountDownLatch(total);
    java.util.List<Integer> order =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    java.util.concurrent.atomic.AtomicInteger maxDepth =
        new java.util.concurrent.atomic.AtomicInteger();
    int baseline = Thread.currentThread().getStackTrace().length;
    taskQueue.execute(
        pool,
        () -> {
          started.countDown();
          uncheckedRun(release::await);
        });
    started.await();
    for (int i = 0; i < total; i++) {
      int sequence = i;
      Executor exec = (i & 1) == 0 ? inlineA : inlineB;
      taskQueue.execute(
          exec,
          () -> {
            maxDepth.accumulateAndGet(Thread.currentThread().getStackTrace().length, Math::max);
            order.add(sequence);
            done.countDown();
          });
    }

    // When
    release.countDown();
    awaitCountDown(done);

    // Then FIFO preserved with bounded stack growth (linear recursion would add ~1 frame/switch)
    assertThat(order)
        .containsExactlyElementsOf(java.util.stream.IntStream.range(0, total).boxed().toList());
    assertThat(maxDepth.get() - baseline).isLessThan(200);
    assertThat(taskQueue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should reject null executor or task on execute")
  void shouldRejectNullExecutorOrTaskOnExecute() {
    TaskQueue queue = new TaskQueue();
    assertThatCode(() -> queue.execute(null, () -> {}))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("executor");
    assertThatCode(() -> queue.execute(x -> {}, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("runnable");
  }

  @Test
  @DisplayName("should report empty when no tasks are queued or running")
  void shouldReportEmptyWhenNoTasksAreQueuedOrRunning() {
    // When / Then
    assertThat(new TaskQueue().isIdle()).isTrue();
  }

  @Test
  @DisplayName("should report not empty when a task is queued")
  void shouldReportNotEmptyWhenATaskIsQueued() {
    // Given
    TaskQueue queue = new TaskQueue();

    // When
    queue.execute(x -> {}, () -> {});

    // Then
    assertThat(queue.isIdle()).isFalse();
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
    assertThat(queue.isIdle()).isFalse();
    releaseTask.countDown();
  }

  @Test
  @DisplayName("should reject only its head when handoff executor throws Error")
  void shouldDiscardBacklogWhenHandoffExecutorThrowsError() throws InterruptedException {
    TaskQueue queue = new TaskQueue();
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

    new Thread(() -> queue.execute(blocking, () -> {})).start();
    runnerStarted.await();
    queue.execute(createExecutor(), () -> {});
    queue.execute(errorExecutor, mock(Runnable.class));
    releaseRunner.countDown();
    sleep(300);

    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should wrap hostile checked executor failure and recover")
  void shouldWrapHostileCheckedExecutorFailureAndRecover() {
    // Given a hostile executor smuggling a checked failure past the undeclared signature
    TaskQueue queue = new TaskQueue();
    java.io.IOException smuggled = new java.io.IOException("sneaky rejection");
    Executor hostileExecutor = command -> sneakyThrow(smuggled);

    // When / Then the scheduling failure surfaces wrapped (never under a false static type)
    // instead of dropping work silently, and the queue stays usable
    try {
      queue.execute(hostileExecutor, () -> {});
      org.assertj.core.api.Assertions.fail("expected sneaky rejection to propagate");
    } catch (Throwable thrown) {
      assertThat(thrown).isInstanceOf(java.util.concurrent.CompletionException.class);
      assertThat(thrown.getCause()).isSameAs(smuggled);
    }
    assertThat(queue.isIdle()).isTrue();

    // And the queue recovers for the next submission
    CountDownLatch done = new CountDownLatch(1);
    queue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);
  }

  @Test
  @DisplayName("should reject only the trigger when the pre-enqueue hook throws")
  void shouldRejectOnlyTheTriggerWhenThePreEnqueueHookThrows() {
    // Given a queue whose pre-enqueue hook throws once, then recovers
    java.util.concurrent.atomic.AtomicBoolean failOnce =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
    TaskQueue queue =
        new TaskQueue() {
          @Override
          protected boolean onEnqueueLocked() {
            if (failOnce.compareAndSet(true, false)) {
              throw new RuntimeException("hook boom");
            }
            return false;
          }
        };

    // When / Then the throw surfaces to the triggering submitter without running its task
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> queue.execute(createExecutor(), () -> ran.set(true)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("hook boom");
    assertThat(ran.get()).isFalse();
    assertThat(queue.isIdle()).isTrue();
    CountDownLatch done = new CountDownLatch(1);
    queue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);
  }

  @Test
  @DisplayName("should reject only the trigger when the post-enqueue hook throws")
  void shouldRejectOnlyTheTriggerWhenThePostEnqueueHookThrows() {
    // Given a queue whose post-enqueue hook throws once, after the runner claim
    java.util.concurrent.atomic.AtomicBoolean failOnce =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
    TaskQueue queue =
        new TaskQueue() {
          @Override
          protected void onEnqueued(boolean changed) {
            if (failOnce.compareAndSet(true, false)) {
              throw new RuntimeException("hook boom");
            }
          }
        };

    // When / Then the throw surfaces with the claimed runner released and the queue reusable
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> queue.execute(createExecutor(), () -> ran.set(true)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("hook boom");
    assertThat(ran.get()).isFalse();
    assertThat(queue.isIdle()).isTrue();
    CountDownLatch done = new CountDownLatch(1);
    queue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);
  }

  @Test
  @DisplayName("should preserve active runner when post-enqueue hook throws without a claim")
  void shouldPreserveActiveRunnerWhenPostEnqueueHookThrowsWithoutAClaim() {
    // Given a queue whose post-enqueue hook throws on the second submit, while the first
    // runner is still active on a background thread (so the second submit claims nothing)
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean secondRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    TaskQueue queue =
        new TaskQueue() {
          @Override
          protected void onEnqueued(boolean changed) {
            if (calls.incrementAndGet() == 2) {
              throw new RuntimeException("hook boom");
            }
          }
        };
    Executor background = createExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    queue.execute(
        background,
        () -> {
          entered.countDown();
          uncheckedRun(release::await);
        });
    awaitCountDown(entered);

    // When / Then the throw surfaces to the triggering submitter without touching the claim
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> queue.execute(background, () -> secondRan.set(true)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("hook boom");
    assertThat(secondRan.get()).isFalse();

    // And the active runner still owns its claim: releasing it drains cleanly and the queue
    // stays usable instead of stalling behind a false idle snapshot
    release.countDown();
    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(queue::isIdle);
    assertThat(queue.isIdle()).isTrue();
    CountDownLatch done = new CountDownLatch(1);
    queue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);
  }

  @Test
  @DisplayName("should propagate Error from enqueue hook instead of discarding")
  void shouldPropagateErrorFromEnqueueHookInsteadOfDiscarding() {
    AssertionError failure = new AssertionError("hook boom");
    TaskQueue queue =
        new TaskQueue() {
          @Override
          protected boolean onEnqueueLocked() {
            throw failure;
          }
        };
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> queue.execute(DirectExecutor.INSTANCE, () -> {}))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("should propagate Error from notify hook and release claim")
  void shouldPropagateErrorFromNotifyHookAndReleaseClaim() {
    AssertionError failure = new AssertionError("hook boom");
    java.util.concurrent.atomic.AtomicBoolean armed =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    TaskQueue queue =
        new TaskQueue() {
          @Override
          protected void onEnqueued(boolean changed) {
            if (armed.getAndSet(false)) {
              throw failure;
            }
          }
        };
    CountDownLatch both = new CountDownLatch(2);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> queue.execute(DirectExecutor.INSTANCE, both::countDown))
        .isSameAs(failure);
    queue.execute(DirectExecutor.INSTANCE, both::countDown);
    awaitCountDown(both);
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should preserve backlog and claim when a hook rejects its trigger")
  void shouldPreserveBacklogAndClaimWhenAHookRejectsItsTrigger() {
    // Given a queue whose pre-enqueue hook throws on the second submit, while the first
    // runner is still active on a background thread (so the second submit claims nothing)
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    TaskQueue queue =
        new TaskQueue() {
          @Override
          protected boolean onEnqueueLocked() {
            if (calls.incrementAndGet() == 2) {
              throw new RuntimeException("hook boom");
            }
            return false;
          }
        };
    Executor background = createExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    queue.execute(
        background,
        () -> {
          entered.countDown();
          uncheckedRun(release::await);
        });
    awaitCountDown(entered);

    // When the second submit fails its hook while the first runner holds the claim
    // Then only the trigger fails visibly while the old claim is kept
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> queue.execute(background, () -> {}))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("hook boom");
    assertThat(queue.isIdle()).isFalse();

    // And the active runner still drains cleanly with the queue reusable afterwards
    release.countDown();
    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(queue::isIdle);
    CountDownLatch done = new CountDownLatch(1);
    queue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);
  }

  @Test
  @DisplayName("should propagate Error from notify hook without claim")
  void shouldPropagateErrorFromNotifyHookWithoutClaim() {
    AssertionError failure = new AssertionError("hook boom");
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    TaskQueue queue =
        new TaskQueue() {
          @Override
          protected void onEnqueued(boolean changed) {
            if (calls.incrementAndGet() == 2) {
              throw failure;
            }
          }
        };
    Executor background = createExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    queue.execute(
        background,
        () -> {
          entered.countDown();
          uncheckedRun(release::await);
        });
    awaitCountDown(entered);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> queue.execute(background, () -> {}))
        .isSameAs(failure);
    release.countDown();
    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(queue::isIdle);
  }

  @Test
  @DisplayName("should propagate fatal Error from inline task and preserve the backlog")
  void shouldPropagateFatalErrorFromInlineTaskAndPreserveTheBacklog() throws InterruptedException {
    // Given an inline runner whose task fails fatally with a second task queued behind it
    TaskQueue queue = new TaskQueue();
    AssertionError fatal = new AssertionError("inline boom");
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    CountDownLatch firstDone = new CountDownLatch(1);
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean secondRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    Thread runner =
        new Thread(
            () -> {
              try {
                queue.execute(
                    DirectExecutor.INSTANCE,
                    () -> {
                      taskStarted.countDown();
                      uncheckedRun(releaseTask::await);
                      throw fatal;
                    });
              } catch (Throwable e) {
                thrown.set(e);
              } finally {
                firstDone.countDown();
              }
            });
    runner.start();
    awaitCountDown(taskStarted);
    queue.execute(DirectExecutor.INSTANCE, () -> secondRan.set(true));
    releaseTask.countDown();
    awaitCountDown(firstDone);

    // Then the fatal Error surfaces to the inline submitter instead of being swallowed
    assertThat(thrown.get()).isSameAs(fatal);

    // And the backlog is preserved: a later submit resumes it instead of losing it
    CountDownLatch done = new CountDownLatch(1);
    queue.execute(DirectExecutor.INSTANCE, done::countDown);
    awaitCountDown(done);
    assertThat(secondRan.get()).isTrue();
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should not release a concurrent runner claim when an inline task fails fatally")
  void shouldNotReleaseConcurrentRunnerClaimWhenInlineTaskFailsFatally() throws Exception {
    // Given an inline executor that parks the fatal Error after the queue already released the
    // dead runner's claim (run()'s fail-open catch), leaving a window for a fresh claim
    TaskQueue queue = new TaskQueue();
    AssertionError fatal = new AssertionError("inline boom");
    CountDownLatch wrapperFailed = new CountDownLatch(1);
    CountDownLatch resumeInlineSubmitter = new CountDownLatch(1);
    Executor parkedInline =
        runnable -> {
          try {
            runnable.run();
          } catch (Error error) {
            wrapperFailed.countDown();
            uncheckedRun(resumeInlineSubmitter::await);
            throw error;
          }
        };
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    CountDownLatch inlineSubmitterDone = new CountDownLatch(1);
    Thread inlineSubmitter =
        new Thread(
            () -> {
              try {
                queue.execute(
                    parkedInline,
                    () -> {
                      throw fatal;
                    });
              } catch (Throwable e) {
                thrown.set(e);
              } finally {
                inlineSubmitterDone.countDown();
              }
            });
    inlineSubmitter.start();
    awaitCountDown(wrapperFailed);

    // When a second submission claims a fresh runner for the preserved backlog
    CountDownLatch blockingStarted = new CountDownLatch(1);
    CountDownLatch releaseBlocking = new CountDownLatch(1);
    queue.execute(
        createExecutor(),
        () -> {
          blockingStarted.countDown();
          uncheckedRun(releaseBlocking::await);
        });
    awaitCountDown(blockingStarted);

    // And the parked fatal path resumes, finalizing the dead runner
    resumeInlineSubmitter.countDown();
    awaitCountDown(inlineSubmitterDone);
    assertThat(thrown.get()).isSameAs(fatal);

    // Then a later submission must not start a second runner while the live one still runs:
    // it belongs behind the running task, never beside it
    AtomicBoolean thirdRan = new AtomicBoolean();
    CountDownLatch thirdDone = new CountDownLatch(1);
    try {
      queue.execute(
          DirectExecutor.INSTANCE,
          () -> {
            thirdRan.set(true);
            thirdDone.countDown();
          });
      assertThat(thirdRan.get()).isFalse();
    } finally {
      releaseBlocking.countDown();
    }

    // And the queued task still drains behind the running one
    awaitCountDown(thirdDone);
    assertThat(thirdRan.get()).isTrue();
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should propagate fatal Error instead of running the next task")
  void shouldPropagateFatalErrorInsteadOfRunningTheNextTask() throws Exception {
    // Given a parked runner with a fatal task followed by a marker task behind it
    TaskQueue queue = new TaskQueue();
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
              thread.setDaemon(true);
              return thread;
            });
    java.util.concurrent.CountDownLatch runnerStarted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseRunner = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean secondRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    AssertionError fatal = new AssertionError("boom");
    try {
      // Park the runner so fatal + marker enqueue deterministically behind an active runner
      queue.execute(
          pool,
          () -> {
            runnerStarted.countDown();
            uncheckedRun(releaseRunner::await);
          });
      awaitCountDown(runnerStarted);
      queue.execute(
          pool,
          () -> {
            throw fatal;
          });
      queue.execute(pool, () -> secondRan.set(true));
      releaseRunner.countDown();

      // When the runner hits the fatal task Then it dies loudly instead of continuing
      awaitCountDown(died);
      assertThat(uncaught.get()).isSameAs(fatal);
      Thread.sleep(200);
      assertThat(secondRan.get()).isFalse();
    } finally {
      releaseRunner.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should contain a sneaky callback failure and preserve survivors")
  void shouldContainASneakyCallbackFailureAndPreserveSurvivors() {
    // Given a blocked runner with a sneaky-callback trigger on a saturated executor plus survivors
    TaskQueue queue = new TaskQueue();
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    Executor blocking =
        command -> {
          runnerStarted.countDown();
          uncheckedRun(releaseRunner::await);
          command.run();
        };
    new Thread(() -> queue.execute(blocking, () -> {})).start();
    awaitCountDown(runnerStarted);
    Executor saturated =
        command -> {
          throw new RejectedExecutionException("boom");
        };
    queue.execute(saturated, new SneakyAware());
    int survivors = 3;
    CountDownLatch done = new CountDownLatch(survivors);
    Executor worker = createExecutor();
    for (int i = 0; i < survivors; i++) {
      queue.execute(worker, done::countDown);
    }

    // When the drain rejects the sneaky head Then the sneaky callback neither escapes the runner
    // nor starves the survivors behind it
    releaseRunner.countDown();
    awaitCountDown(done);
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should surface original rejection when a discard callback throws Error")
  void shouldSurfaceOriginalRejectionWhenADiscardCallbackThrowsError() {
    // Given a hostile callback throwing Error and an original scheduling failure
    AssertionError callbackError = new AssertionError("callback boom");
    final class HostileError implements Runnable, RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        throw callbackError;
      }
    }
    RejectedExecutionException schedulingFailure =
        new RejectedExecutionException("scheduling boom");
    TaskQueue queue = new TaskQueue();
    Executor failing =
        command -> {
          throw schedulingFailure;
        };

    // When the trigger is rejected with a hostile callback Then the original rejection surfaces
    // with the callback failure suppressed, not masking it
    try {
      queue.execute(failing, new HostileError());
      org.assertj.core.api.Assertions.fail("expected original rejection to propagate");
    } catch (RejectedExecutionException thrown) {
      assertThat(thrown).isSameAs(schedulingFailure);
      assertThat(thrown.getSuppressed()).contains(callbackError);
    }
    assertThat(queue.isIdle()).isTrue();
  }

  /** Rejection-aware runnable whose callback smuggles a checked failure. */
  static final class SneakyAware implements Runnable, RejectionAware {
    @Override
    public void run() {}

    @Override
    public void rejected(Throwable cause) {
      sneakyThrow(new java.io.IOException("callback boom"));
    }
  }

  @Test
  @DisplayName("should resume backlog on next submit after fatal Error")
  void shouldResumeBacklogOnNextSubmitAfterFatalError() throws Exception {
    // Given a runner killed by a fatal task with a pending marker behind it
    TaskQueue queue = new TaskQueue();
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
              thread.setDaemon(true);
              return thread;
            });
    AssertionError fatal = new AssertionError("boom");
    java.util.concurrent.atomic.AtomicBoolean secondRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    try {
      queue.execute(
          pool,
          () -> {
            throw fatal;
          });
      queue.execute(pool, () -> secondRan.set(true));
      awaitCountDown(died);

      // When the next submission arrives after the fatal death
      java.util.concurrent.CountDownLatch recovered = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.ExecutorService recovery =
          java.util.concurrent.Executors.newSingleThreadExecutor(
              r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
              });
      try {
        queue.execute(recovery, recovered::countDown);

        // Then the queue must not stall: preserved backlog drains and new work runs
        awaitCountDown(recovered);
        // Give the preserved marker a chance to run on the resumed runner
        sleep(200);
        assertThat(uncaught.get()).isSameAs(fatal);
        assertThat(secondRan.get()).isTrue();
        assertThat(queue.isIdle()).isTrue();
      } finally {
        recovery.shutdownNow();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should keep the runner when the executor starts it and then throws")
  void shouldKeepRunnerWhenExecutorStartsItThenThrows() throws InterruptedException {
    // Given an executor that dispatches the runner to another thread, waits until the task body
    // runs, and only then throws
    TaskQueue taskQueue = new TaskQueue();
    CountDownLatch bodyRunning = new CountDownLatch(1);
    CountDownLatch releaseBody = new CountDownLatch(1);
    AtomicReference<Throwable> submitFailure = new AtomicReference<>();
    AtomicReference<Throwable> rejection = new AtomicReference<>();
    AtomicInteger runs = new AtomicInteger();
    class Probe implements Runnable, RejectionAware {
      @Override
      public void run() {
        runs.incrementAndGet();
        bodyRunning.countDown();
        uncheckedRun(releaseBody::await);
      }

      @Override
      public void rejected(Throwable cause) {
        rejection.set(cause);
      }
    }
    Executor dispatchesThenThrows =
        command -> {
          Thread runner = new Thread(command, "dispatch-then-throw");
          runner.setDaemon(true);
          runner.start();
          uncheckedRun(bodyRunning::await);
          throw new RuntimeException("executor threw after dispatch");
        };

    // When the submission is scheduled through that executor
    Thread submitter =
        new Thread(
            () -> {
              try {
                taskQueue.execute(dispatchesThenThrows, new Probe());
              } catch (Throwable e) {
                submitFailure.set(e);
              }
            });
    submitter.start();
    submitter.join(5000);

    // Then the submission is accepted, not failed: the started runner owns the queue (so the
    // claim must not be cleared) and the task must never be notified as rejected
    assertThat(submitFailure.get()).isNull();
    assertThat(rejection.get()).isNull();
    assertThat(runs).hasValue(1);
    assertThat(taskQueue.isIdle()).isFalse();

    // And the runner keeps draining once the body releases
    releaseBody.countDown();
    await().atMost(Duration.ofSeconds(5)).until(taskQueue::isIdle);
  }

  @Test
  @DisplayName("should keep the runner when the executor throws after the runner already finished")
  void shouldKeepRunnerWhenExecutorThrowsAfterRunnerFinished() throws InterruptedException {
    // Given an executor that dispatches the runner, waits for the whole drain to finish, then
    // throws
    TaskQueue taskQueue = new TaskQueue();
    CountDownLatch taskRan = new CountDownLatch(1);
    AtomicReference<Throwable> submitFailure = new AtomicReference<>();
    AtomicReference<Throwable> rejection = new AtomicReference<>();
    class Probe implements Runnable, RejectionAware {
      @Override
      public void run() {
        taskRan.countDown();
      }

      @Override
      public void rejected(Throwable cause) {
        rejection.set(cause);
      }
    }
    Executor dispatchesThenThrows =
        command -> {
          Thread runner = new Thread(command, "dispatch-then-throw");
          runner.setDaemon(true);
          runner.start();
          uncheckedRun(taskRan::await);
          throw new RuntimeException("executor threw after dispatch");
        };

    // When the submission is scheduled through that executor
    Thread submitter =
        new Thread(
            () -> {
              try {
                taskQueue.execute(dispatchesThenThrows, new Probe());
              } catch (Throwable e) {
                submitFailure.set(e);
              }
            });
    submitter.start();
    submitter.join(5000);

    // Then the finished runner still counts as started: the task ran, it was never notified as
    // rejected, and the submission was not failed
    assertThat(taskRan.getCount()).isZero();
    assertThat(submitFailure.get()).isNull();
    assertThat(rejection.get()).isNull();
    await().atMost(Duration.ofSeconds(5)).until(taskQueue::isIdle);
  }

  @Test
  @DisplayName("should keep the handoff when the executor starts the runner and then throws")
  void shouldKeepHandoffWhenExecutorStartsRunnerThenThrows() throws InterruptedException {
    // Given a drain that hands off to an executor which dispatches the runner and only then throws
    TaskQueue taskQueue = new TaskQueue();
    CountDownLatch handoffRunning = new CountDownLatch(1);
    CountDownLatch releaseHandoff = new CountDownLatch(1);
    AtomicReference<Throwable> rejection = new AtomicReference<>();
    AtomicInteger runs = new AtomicInteger();
    class Probe implements Runnable, RejectionAware {
      @Override
      public void run() {
        runs.incrementAndGet();
        handoffRunning.countDown();
        uncheckedRun(releaseHandoff::await);
      }

      @Override
      public void rejected(Throwable cause) {
        rejection.set(cause);
      }
    }
    Executor dispatchesThenThrows =
        command -> {
          Thread runner = new Thread(command, "handoff-dispatch-then-throw");
          runner.setDaemon(true);
          runner.start();
          uncheckedRun(handoffRunning::await);
          throw new RuntimeException("executor threw after dispatch");
        };

    // When the inline drain reaches the queued handoff to that executor
    Thread submitter =
        new Thread(
            () ->
                taskQueue.execute(
                    Runnable::run, () -> taskQueue.execute(dispatchesThenThrows, new Probe())));
    submitter.start();
    submitter.join(5000);

    // Then the handoff is kept: the started runner owns the drain, and the head is never rejected
    assertThat(rejection.get()).isNull();
    assertThat(runs).hasValue(1);
    assertThat(taskQueue.isIdle()).isFalse();

    // And the handoff runner completes the drain once the body releases
    releaseHandoff.countDown();
    await().atMost(Duration.ofSeconds(5)).until(taskQueue::isIdle);
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
    throw (E) throwable;
  }
}
