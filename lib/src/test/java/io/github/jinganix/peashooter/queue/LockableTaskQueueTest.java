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

    @Nested
    @DisplayName("when not locked")
    class WhenNotLocked {

      @Test
      @DisplayName("then do not unlock")
      void thenDoNotUnlock() {
        LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
        when(taskQueue.tryLock(any())).thenReturn(false);
        taskQueue.run();
        verify(taskQueue, never()).unlock();
      }
    }

    @Nested
    @DisplayName("when locked")
    class WhenLocked {

      @Nested
      @DisplayName("when no tasks")
      class WhenNoTasks {

        @Test
        @DisplayName("then not check yield")
        void thenNotCheckYield() {
          LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
          when(taskQueue.tryLock(any())).thenReturn(true);
          taskQueue.run();
          verify(taskQueue, never()).shouldYield(any());
          verify(taskQueue, times(1)).unlock();
        }
      }

      @Nested
      @DisplayName("when task executed")
      class WhenTaskExecuted {

        @Nested
        @DisplayName("when should yield")
        class WhenShouldYield {

          @Test
          @DisplayName("then lock twice")
          void thenLockTwice() throws InterruptedException {
            LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
            when(taskQueue.tryLock(any())).thenReturn(true);
            when(taskQueue.shouldYield(any())).thenReturn(true);

            CountDownLatch latch = new CountDownLatch(1);
            taskQueue.execute(DirectExecutor.INSTANCE, latch::countDown);

            latch.await();
            verify(taskQueue, times(1)).shouldYield(any());
            verify(taskQueue, times(2)).tryLock(any());
            verify(taskQueue, times(2)).unlock();
          }

          @Nested
          @DisplayName("when should not yield")
          class WhenShouldNotYield {

            @Test
            @DisplayName("then lock once")
            void thenLockOnce() throws InterruptedException {
              LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
              when(taskQueue.tryLock(any())).thenReturn(true);
              when(taskQueue.shouldYield(any())).thenReturn(false);

              CountDownLatch latch = new CountDownLatch(1);
              taskQueue.execute(DirectExecutor.INSTANCE, latch::countDown);

              latch.await();
              verify(taskQueue, times(1)).shouldYield(any());
              verify(taskQueue, times(1)).tryLock(any());
              verify(taskQueue, times(1)).unlock();
            }
          }
        }
      }

      @Nested
      @DisplayName("when 2 tasks executed")
      class When2TasksExecuted {

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

        @Nested
        @DisplayName("when should yield first")
        class WhenShouldYieldFirst {

          @Test
          @DisplayName("then lock twice")
          void thenLockTwice() throws InterruptedException {
            LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
            when(taskQueue.tryLock(any())).thenReturn(true);
            when(taskQueue.shouldYield(any())).thenReturn(true, false);

            executeTwoTasks(taskQueue);

            verify(taskQueue, times(2)).shouldYield(any());
            verify(taskQueue, times(2)).tryLock(any());
            verify(taskQueue, times(2)).unlock();
          }
        }

        @Nested
        @DisplayName("when should not yield")
        class WhenShouldNotYield {

          @Test
          @DisplayName("then lock once")
          void thenLockOnce() throws InterruptedException {
            LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
            when(taskQueue.tryLock(any())).thenReturn(true);
            when(taskQueue.shouldYield(any())).thenReturn(false);

            executeTwoTasks(taskQueue);

            verify(taskQueue, times(2)).shouldYield(any());
            verify(taskQueue, times(1)).tryLock(any());
            verify(taskQueue, times(1)).unlock();
          }
        }
      }
    }

    @Nested
    @DisplayName("when first task throw errors")
    class WhenFirstTaskThrowErrors {

      @Test
      @DisplayName("then run tasks sequentially")
      void thenRunTasksSequentially() throws InterruptedException {
        LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
        when(taskQueue.tryLock(any())).thenReturn(true);

        CountDownLatch latch = new CountDownLatch(1);
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
        assertThat(ref.get()).isGreaterThanOrEqualTo(100);
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
          LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
          when(taskQueue.tryLock(any())).thenReturn(true);

          Executor executor = mock(Executor.class);
          RejectedExecutionException exception = new RejectedExecutionException();
          doThrow(exception).when(executor).execute(any());

          assertThatThrownBy(() -> taskQueue.execute(executor, () -> {})).isEqualTo(exception);
        }
      }

      @Nested
      @DisplayName("when tasks is not empty")
      class WhenTasksIsNotEmpty {

        @Test
        @DisplayName("then task not called")
        void thenTaskNotCalled() throws InterruptedException {
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
          taskQueue.execute(executor, task);
          latch2.countDown();
          latch3.await();

          verify(executor, times(1)).execute(any());
          verify(task, never()).run();
        }
      }
    }
  }
}
