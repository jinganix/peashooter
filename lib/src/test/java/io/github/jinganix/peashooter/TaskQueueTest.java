/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * https://github.com/jinganix/peashooter
 */

package io.github.jinganix.peashooter;

import static io.github.jinganix.peashooter.TestUtils.sleep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("OrderedTaskQueue")
class TaskQueueTest {

  ExecutorService executor() {
    return Executors.newSingleThreadExecutor();
  }

  @Nested
  @DisplayName("execute")
  class Execute {

    @Nested
    @DisplayName("when run two tasks")
    class WhenRunTwoTasks {

      @Test
      @DisplayName("then run tasks sequentially")
      void thenRunTasksSequentially() {
        TaskQueue taskQueue = new TaskQueue();
        ExecutorService executor = executor();
        taskQueue.execute(executor, () -> sleep(100));
        AtomicReference<Long> ref = new AtomicReference<>();
        long millis = System.currentTimeMillis();
        taskQueue.execute(executor, () -> ref.set(System.currentTimeMillis() - millis));

        await()
            .atMost(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(ref.get()).isGreaterThanOrEqualTo(100));
      }
    }

    @Nested
    @DisplayName("when run two executors")
    class WhenRunTwoExecutors {

      @Test
      @DisplayName("then run tasks sequentially")
      void thenRunTasksSequentially() {
        TaskQueue taskQueue = new TaskQueue();
        taskQueue.execute(executor(), () -> sleep(100));
        AtomicReference<Long> ref = new AtomicReference<>();
        long millis = System.currentTimeMillis();
        taskQueue.execute(executor(), () -> ref.set(System.currentTimeMillis() - millis));

        await()
            .atMost(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(ref.get()).isGreaterThanOrEqualTo(100));
      }
    }

    @Nested
    @DisplayName("when first task throw errors")
    class WhenFirstTaskThrowErrors {

      @Test
      @DisplayName("then run tasks sequentially")
      void thenRunTasksSequentially() {
        TaskQueue taskQueue = new TaskQueue();
        taskQueue.execute(
            executor(),
            () -> {
              sleep(100);
              throw new RuntimeException("error");
            });
        AtomicReference<Long> ref = new AtomicReference<>();
        long millis = System.currentTimeMillis();
        taskQueue.execute(executor(), () -> ref.set(System.currentTimeMillis() - millis));

        await()
            .atMost(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(ref.get()).isGreaterThanOrEqualTo(100));
      }
    }

    @Nested
    @DisplayName("when executor throw errors")
    class WhenExecutorThrowErrors {

      @Nested
      @DisplayName("when tasks is empty")
      class WhenTasksIsEmpty {

        @Test
        @DisplayName("then throw exception")
        void thenThenThrowException() {
          TaskQueue taskQueue = new TaskQueue();
          Executor executor = mock(Executor.class);
          RuntimeException exception = new RuntimeException();
          doThrow(exception).when(executor).execute(any());

          assertThatThrownBy(() -> taskQueue.execute(executor, () -> {})).isEqualTo(exception);
        }
      }

      @Nested
      @DisplayName("when tasks is not empty")
      class WhenTasksIsNotEmpty {

        @Test
        @DisplayName("then current is null")
        void thenCurrentIsNull() throws InterruptedException {
          TaskQueue taskQueue = new TaskQueue();
          Executor executor = mock(Executor.class);
          doThrow(new RuntimeException()).when(executor).execute(any());
          Runnable runnable = mock(Runnable.class);

          CountDownLatch latch = new CountDownLatch(1);
          taskQueue.execute(
              Executors.newSingleThreadExecutor(),
              () -> {
                latch.countDown();
                sleep(100);
              });
          latch.await();
          taskQueue.execute(executor, runnable);
          verify(executor, after(300).times(1)).execute(any());
          verify(runnable, never()).run();
        }
      }
    }
  }
}
