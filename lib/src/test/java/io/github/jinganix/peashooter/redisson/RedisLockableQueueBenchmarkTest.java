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

package io.github.jinganix.peashooter.redisson;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.queue.ExecutionCountStats;
import io.github.jinganix.peashooter.redisson.setup.RedisClient;
import io.github.jinganix.peashooter.redisson.setup.RedisExtension;
import io.github.jinganix.peashooter.redisson.setup.RedisLockableTaskQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

@ExtendWith(RedisExtension.class)
@DisplayName("RedisLockableQueueBenchmark")
@DisabledIfEnvironmentVariable(named = "skip_benchmark", matches = "true")
public class RedisLockableQueueBenchmarkTest {

  private static final ExecutorService executorService = Executors.newFixedThreadPool(8);

  private final RedissonClient client = RedisClient.client;

  static class Counter {
    int count = 0;
  }

  @BeforeEach
  void setup() {
    client.getKeys().flushall();
  }

  @AfterAll
  static void clear() {
    executorService.shutdown();
  }

  @Nested
  @DisplayName("when execute 500 tasks")
  class WhenExecuteTasks {

    int taskCount = 500;

    private long redisTaskQueueTest() throws InterruptedException {
      CountDownLatch latch = new CountDownLatch(taskCount);
      RedisLockableTaskQueue taskQueue =
          new RedisLockableTaskQueue("lock_test") {
            // The ideal scenario for the following code is that
            // acquiring the Redis lock once allows for the consecutive execution of 50 tasks.
            @Override
            protected boolean shouldYield(ExecutionStats stats) {
              int executionCount = ((ExecutionCountStats) stats).getExecutionCount();
              return executionCount > 0 && executionCount % 50 == 0;
            }
          };
      Counter counter = new Counter();
      long startAt = System.nanoTime();
      for (int i = 0; i < taskCount; i++) {
        taskQueue.execute(
            executorService,
            () -> {
              counter.count++;
              latch.countDown();
            });
      }
      latch.await();
      assertThat(counter.count).isEqualTo(taskCount);
      return System.nanoTime() - startAt;
    }

    private long redisLockTest() throws InterruptedException {
      CountDownLatch latch = new CountDownLatch(taskCount);
      long startAt = System.nanoTime();
      RLock lock = RedisClient.client.getFairLock("lock_test");
      Counter counter = new Counter();
      for (int i = 0; i < taskCount; i++) {
        executorService.submit(
            () -> {
              try {
                if (lock.tryLock(5, TimeUnit.SECONDS)) {
                  counter.count++;
                  latch.countDown();
                }
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              } finally {
                lock.forceUnlock();
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
      long time1 = redisTaskQueueTest();
      long time2 = redisLockTest();
      System.out.printf(
          "task count: %d, benchmark: peashooter(%dms), lock(%dms)",
          taskCount, TimeUnit.NANOSECONDS.toMillis(time1), TimeUnit.NANOSECONDS.toMillis(time2));
      assertThat(time1).isLessThan(time2);
    }
  }
}
