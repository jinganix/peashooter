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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

@DisplayName("TaskQueueBenchmark")
@DisabledIfEnvironmentVariable(named = "skip_benchmark", matches = "true")
class TaskQueueBenchmarkTest {

  private static final ExecutorService executorService = Executors.newFixedThreadPool(8);

  static class Counter {
    int count = 0;
  }

  @AfterAll
  static void clear() {
    executorService.shutdown();
  }

  @Nested
  @DisplayName("when execute 5,000,000 tasks")
  class WhenExecuteTasks {

    int taskCount = 5_000_000;

    void count(CountDownLatch latch, Counter counter) {
      counter.count++;
      latch.countDown();
    }

    private long taskQueueTest() throws InterruptedException {
      CountDownLatch latch = new CountDownLatch(taskCount);

      TaskQueue taskQueue = new TaskQueue();
      Counter counter = new Counter();
      long startAt = System.nanoTime();
      for (int i = 0; i < taskCount; i++) {
        taskQueue.execute(executorService, () -> count(latch, counter));
      }
      latch.await();
      assertThat(counter.count).isEqualTo(taskCount);
      return System.nanoTime() - startAt;
    }

    private long synchronizedTest() throws InterruptedException {
      CountDownLatch latch = new CountDownLatch(taskCount);
      long startAt = System.nanoTime();
      Counter counter = new Counter();
      String KEY = "lock_test";
      for (int i = 0; i < taskCount; i++) {
        executorService.submit(
            () -> {
              synchronized (KEY) {
                count(latch, counter);
              }
            });
      }
      latch.await();
      assertThat(counter.count).isEqualTo(taskCount);
      return System.nanoTime() - startAt;
    }

    private long reentrantLockTest() throws InterruptedException {
      CountDownLatch latch = new CountDownLatch(taskCount);
      long startAt = System.nanoTime();
      Counter counter = new Counter();
      ReentrantLock lock = new ReentrantLock();
      for (int i = 0; i < taskCount; i++) {
        executorService.submit(
            () -> {
              lock.lock();
              try {
                count(latch, counter);
              } finally {
                lock.unlock();
              }
            });
      }
      latch.await();
      assertThat(counter.count).isEqualTo(taskCount);
      return System.nanoTime() - startAt;
    }

    @Test
    @DisplayName("then task is executed")
    void thenTaskIsExecuted() throws InterruptedException {
      long time1 = taskQueueTest();
      long time2 = synchronizedTest();
      long time3 = reentrantLockTest();
      System.out.printf(
          "task count: %d, benchmark: peashooter(%dms), synchronized(%dms), reentrantLock(%dms)",
          taskCount,
          TimeUnit.NANOSECONDS.toMillis(time1),
          TimeUnit.NANOSECONDS.toMillis(time2),
          TimeUnit.NANOSECONDS.toMillis(time3));
      assertThat(time1).isLessThan(time2);
      assertThat(time1).isLessThan(time3);
    }
  }
}
