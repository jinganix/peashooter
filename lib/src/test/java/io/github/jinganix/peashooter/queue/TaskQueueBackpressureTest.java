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
import static io.github.jinganix.peashooter.utils.TestUtils.uncheckedRun;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue backpressure")
class TaskQueueBackpressureTest {

  static final class RecordingAware implements Runnable, RejectionAware {
    final AtomicReference<Throwable> seen = new AtomicReference<>();

    @Override
    public void run() {}

    @Override
    public void rejected(Throwable cause) {
      seen.set(cause);
    }
  }

  @Test
  @DisplayName(
      "should preserve backlog and fail only triggering submit when bounded executor rejects")
  void shouldPreserveBacklogWhenBoundedExecutorRejects() throws InterruptedException {
    // Given a runner parked on a blocking head task with prior work, a saturated trigger in the
    // middle, and tail work behind it (saturated bounds lose the whole key today)
    TaskQueue taskQueue = new TaskQueue();
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    Executor blocking =
        command -> {
          runnerStarted.countDown();
          uncheckedRun(releaseRunner::await);
          command.run();
        };
    Thread head = new Thread(() -> taskQueue.execute(blocking, () -> {}));
    head.setDaemon(true);
    head.start();
    runnerStarted.await();

    Executor worker = Executors.newCachedThreadPool();
    AtomicInteger completed = new AtomicInteger();
    int prior = 3;
    int tail = 5;
    CountDownLatch tailDone = new CountDownLatch(tail);
    for (int i = 0; i < prior; i++) {
      taskQueue.execute(worker, completed::incrementAndGet);
    }
    Executor saturated =
        command -> {
          throw new RejectedExecutionException("saturated");
        };
    RecordingAware trigger = new RecordingAware();
    taskQueue.execute(saturated, trigger);
    for (int i = 0; i < tail; i++) {
      taskQueue.execute(
          worker,
          () -> {
            completed.incrementAndGet();
            tailDone.countDown();
          });
    }

    // When the head releases and the drain reaches the saturated handoff
    releaseRunner.countDown();

    // Then only the triggering submit fails visibly (future-style notification) while the
    // backlog behind it is preserved and still drains instead of being discarded with it
    awaitCountDown(tailDone);
    assertThat(trigger.seen.get()).isInstanceOf(RejectedExecutionException.class);
    assertThat(completed.get()).isEqualTo(prior + tail);
    assertThat(taskQueue.isIdle()).isTrue();
  }
}
