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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
        when(taskQueue.tryLock(anyInt())).thenReturn(false);
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
          when(taskQueue.tryLock(anyInt())).thenReturn(true);
          taskQueue.run();
          verify(taskQueue, never()).shouldYield(anyInt());
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
            when(taskQueue.tryLock(anyInt())).thenReturn(true);
            when(taskQueue.shouldYield(anyInt())).thenReturn(true);

            CountDownLatch latch = new CountDownLatch(1);
            taskQueue.execute(DirectExecutor.INSTANCE, latch::countDown);

            latch.await();
            verify(taskQueue, times(1)).shouldYield(anyInt());
            verify(taskQueue, times(2)).tryLock(anyInt());
            verify(taskQueue, times(2)).unlock();
          }

          @Nested
          @DisplayName("when should not yield")
          class WhenShouldNotYield {

            @Test
            @DisplayName("then lock once")
            void thenLockOnce() throws InterruptedException {
              LockableTaskQueue taskQueue = spy(LockableTaskQueue.class);
              when(taskQueue.tryLock(anyInt())).thenReturn(true);
              when(taskQueue.shouldYield(anyInt())).thenReturn(false);

              CountDownLatch latch = new CountDownLatch(1);
              taskQueue.execute(DirectExecutor.INSTANCE, latch::countDown);

              latch.await();
              verify(taskQueue, times(1)).shouldYield(anyInt());
              verify(taskQueue, times(1)).tryLock(anyInt());
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
          Executors.newSingleThreadExecutor()
              .submit(
                  () ->
                      taskQueue.execute(
                          DirectExecutor.INSTANCE,
                          () -> {
                            latch1.countDown();
                            try {
                              latch2.await();
                              sleep(100);
                            } catch (InterruptedException e) {
                              throw new RuntimeException(e);
                            }
                          }));
          latch1.await();

          taskQueue.execute(DirectExecutor.INSTANCE, latch3::countDown);
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
            when(taskQueue.tryLock(anyInt())).thenReturn(true);
            when(taskQueue.shouldYield(anyInt())).thenReturn(true, false);

            executeTwoTasks(taskQueue);

            verify(taskQueue, times(2)).shouldYield(anyInt());
            verify(taskQueue, times(2)).tryLock(anyInt());
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
            when(taskQueue.tryLock(anyInt())).thenReturn(true);
            when(taskQueue.shouldYield(anyInt())).thenReturn(false);

            executeTwoTasks(taskQueue);

            verify(taskQueue, times(2)).shouldYield(anyInt());
            verify(taskQueue, times(1)).tryLock(anyInt());
            verify(taskQueue, times(1)).unlock();
          }
        }
      }
    }
  }
}
