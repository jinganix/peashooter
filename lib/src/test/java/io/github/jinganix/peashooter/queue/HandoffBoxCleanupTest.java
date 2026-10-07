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
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Handoff box cleanup")
class HandoffBoxCleanupTest {

  @Test
  @DisplayName("should schedule follow-up work after async scheduling")
  void shouldScheduleFollowUpWorkAfterAsyncScheduling() throws InterruptedException {
    // Given a thread submitting through an async pool
    TaskQueue queue = new TaskQueue();
    Executor pool =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setDaemon(true);
              return thread;
            });
    CountDownLatch done = new CountDownLatch(1);

    // When the submission schedules async Then it completes without retaining the submitter
    queue.execute(pool, done::countDown);
    awaitCountDown(done);

    // And a later submission still works via a fresh handoff
    CountDownLatch again = new CountDownLatch(1);
    queue.execute(pool, again::countDown);
    awaitCountDown(again);
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should drain the peer after async handoff")
  void shouldDrainPeerAfterAsyncHandoff() throws InterruptedException {
    // Given a drain parked on a blocking head with a peer queued for an async pool
    TaskQueue queue = new TaskQueue();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Executor blocking =
        command -> {
          started.countDown();
          uncheckedRun(release::await);
          command.run();
        };
    Executor pool =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setDaemon(true);
              return thread;
            });
    CountDownLatch secondDone = new CountDownLatch(1);
    Thread drainer =
        new Thread(
            () -> {
              // Parks inside the blocking head until the main thread releases it
              queue.execute(blocking, () -> {});
              uncheckedRun(secondDone::await);
            });
    drainer.start();

    // A peer queued for an async pool while the drainer is parked forces an async handoff
    // mid-drain once released
    awaitCountDown(started);
    queue.execute(pool, secondDone::countDown);
    release.countDown();
    drainer.join(10_000);

    assertThat(secondDone.getCount()).isZero();
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should drain the peer after lockable async handoff")
  void shouldDrainPeerAfterLockableAsyncHandoff() throws InterruptedException {
    // Given a lockable queue drained on a dedicated thread with an executor switch mid-drain
    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setDaemon(true);
              return thread;
            });
    try {
      LockableTaskQueue queue =
          new LockableTaskQueue(new ExecutionCountStats(), scheduler) {
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
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      Executor blocking =
          command -> {
            started.countDown();
            uncheckedRun(release::await);
            command.run();
          };
      Executor pool =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                return thread;
              });
      CountDownLatch secondDone = new CountDownLatch(1);
      Thread drainer =
          new Thread(
              () -> {
                // Parks inside the blocking head until the main thread releases it
                queue.execute(blocking, () -> {});
                uncheckedRun(secondDone::await);
              });
      drainer.start();

      // A peer queued for an async pool while the drainer is parked forces an async handoff
      // mid-drain once released
      awaitCountDown(started);
      queue.execute(pool, secondDone::countDown);
      release.countDown();
      drainer.join(10_000);

      assertThat(secondDone.getCount()).isZero();
      assertThat(queue.isIdle()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
  }
}
