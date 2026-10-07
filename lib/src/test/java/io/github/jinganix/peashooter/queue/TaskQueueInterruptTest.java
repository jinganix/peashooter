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

import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue interrupt")
class TaskQueueInterruptTest {

  private static RuntimeException sneakyInterrupt(String message) {
    return smuggle(new InterruptedException(message));
  }

  // Test-only smuggling of a checked interrupt past Runnable to exercise the
  // carriesInterrupt path; production code never sneaks (it wraps in CompletionException).
  private static <E extends Throwable> RuntimeException smuggle(Throwable failure) throws E {
    throw (E) failure;
  }

  @Test
  @DisplayName("should restore interrupt flag when task throws sneaky interrupt inline")
  void shouldRestoreInterruptFlagWhenTaskThrowsSneakyInterruptInline() {
    // Given a cleared interrupt flag and an inline queue
    Thread.interrupted();
    TaskQueue queue = new TaskQueue();

    // When a task smuggles a checked interrupt past Runnable
    queue.execute(DirectExecutor.INSTANCE, () -> sneakyThrowInterrupt());

    // Then the interrupt flag is restored instead of swallowed
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    Thread.interrupted();
  }

  @Test
  @DisplayName("should end batch without running later tasks when task throws sneaky interrupt")
  void shouldEndBatchWithoutRunningLaterTasksWhenTaskThrowsSneakyInterrupt() throws Exception {
    // Given a parked runner with an interrupt task followed by a marker
    TaskQueue queue = new TaskQueue();
    AtomicReference<Thread> runnerThread = new AtomicReference<>();
    Executor capturingAsync =
        command -> {
          Thread thread = new Thread(command);
          thread.setDaemon(true);
          runnerThread.set(thread);
          thread.start();
        };
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean markerRan = new AtomicBoolean();
    queue.execute(
        capturingAsync,
        () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        });
    if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
      throw new IllegalStateException("runner did not start");
    }
    queue.execute(DirectExecutor.INSTANCE, () -> sneakyThrowInterrupt());
    queue.execute(DirectExecutor.INSTANCE, () -> markerRan.set(true));

    // When the head releases and the drain hits the interrupt
    release.countDown();
    Thread runner = runnerThread.get();
    if (runner != null) {
      runner.join(5000);
    }
    Thread.sleep(200);

    // Then the marker never runs and the runner thread keeps its interrupt
    assertThat(markerRan.get()).isFalse();
    assertThat(runnerThread.get().isInterrupted()).isTrue();
  }

  @Test
  @DisplayName("should end batch when interrupt arrives wrapped in completion failure")
  void shouldEndBatchWhenInterruptArrivesWrappedInCompletionFailure() throws Exception {
    // Given a parked runner with a wrapped interrupt followed by a marker
    TaskQueue queue = new TaskQueue();
    AtomicReference<Thread> runnerThread = new AtomicReference<>();
    Executor capturingAsync =
        command -> {
          Thread thread = new Thread(command);
          thread.setDaemon(true);
          runnerThread.set(thread);
          thread.start();
        };
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean markerRan = new AtomicBoolean();
    queue.execute(
        capturingAsync,
        () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        });
    if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
      throw new IllegalStateException("runner did not start");
    }
    queue.execute(
        DirectExecutor.INSTANCE,
        () -> {
          throw new CompletionException(new InterruptedException("wrapped boom"));
        });
    queue.execute(DirectExecutor.INSTANCE, () -> markerRan.set(true));

    // When the drain hits the wrapped interrupt
    release.countDown();
    Thread runner = runnerThread.get();
    if (runner != null) {
      runner.join(5000);
    }
    Thread.sleep(200);

    // Then the batch ends with the interrupt restored instead of continuing
    assertThat(markerRan.get()).isFalse();
    assertThat(runnerThread.get().isInterrupted()).isTrue();
  }

  private static void sneakyThrowInterrupt() {
    throw sneakyInterrupt("task interrupt");
  }
}
