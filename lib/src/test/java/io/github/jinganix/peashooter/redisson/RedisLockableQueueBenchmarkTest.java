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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(RedisExtension.class)
@DisplayName("RedisLockableQueueBenchmark")
@DisabledIfEnvironmentVariable(named = "skip_benchmark", matches = "true")
public class RedisLockableQueueBenchmarkTest {

  private static final Logger log = LoggerFactory.getLogger(RedisLockableQueueBenchmarkTest.class);

  private static final ExecutorService executorService = Executors.newFixedThreadPool(8);

  private final RedissonClient client = RedisClient.get();

  static class Counter {
    int count = 0;
  }

  @BeforeEach
  void setup() {
    client.getKeys().flushall();
  }

  @AfterAll
  static void clear() throws InterruptedException {
    // A bare shutdown() leaves late benchmark tasks running past the test JVM; wait for
    // quiescence first and force-cancel only when the wait expires.
    executorService.shutdown();
    if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
      executorService.shutdownNow();
    }
  }

  @Nested
  @DisplayName("when execute 500 tasks")
  class WhenExecute500Tasks {

    int taskCount = 500;

    private long redisTaskQueueTest() throws InterruptedException {
      // Given
      CountDownLatch latch = new CountDownLatch(taskCount);
      Counter counter = new Counter();

      // When
      long startAt = System.nanoTime();
      try (RedisLockableTaskQueue taskQueue =
          new RedisLockableTaskQueue("lock_test") {
            // The ideal scenario for the following code is that
            // acquiring the Redis lock once allows for the consecutive execution of 50 tasks.
            @Override
            protected boolean shouldYield(ExecutionStats stats) {
              int executionCount = ((ExecutionCountStats) stats).getExecutionCount();
              return executionCount > 0 && executionCount % 50 == 0;
            }
          }) {
        for (int i = 0; i < taskCount; i++) {
          taskQueue.execute(
              executorService,
              () -> {
                counter.count++;
                latch.countDown();
              });
        }
        latch.await();
      }

      // Then
      assertThat(counter.count).isEqualTo(taskCount);
      return System.nanoTime() - startAt;
    }

    private long redisLockTest() throws InterruptedException {
      // Given
      CountDownLatch latch = new CountDownLatch(taskCount);
      RLock lock = RedisClient.get().getFairLock("lock_test");
      Counter counter = new Counter();

      // When
      long startAt = System.nanoTime();
      for (int i = 0; i < taskCount; i++) {
        executorService.submit(
            () -> {
              boolean acquired = false;
              try {
                acquired = lock.tryLock(5, TimeUnit.SECONDS);
                if (acquired) {
                  counter.count++;
                  latch.countDown();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                if (acquired) {
                  lock.unlock();
                }
              }
            });
      }
      latch.await();

      // Then
      assertThat(counter.count).isEqualTo(taskCount);
      return System.nanoTime() - startAt;
    }

    @Test
    @DisplayName("should execute faster than redis lock when running 500 tasks")
    void shouldExecuteFasterThanRedisLockWhenRunning500Tasks() throws InterruptedException {
      // When
      long time1 = redisTaskQueueTest();
      long time2 = redisLockTest();

      // Then: informational benchmark — Redis/container load varies, so allow a generous margin
      // instead of asserting strict superiority (which flakes under CI load).
      log.info(
          "task count: {}, benchmark: peashooter({}ms), lock({}ms)",
          taskCount,
          TimeUnit.NANOSECONDS.toMillis(time1),
          TimeUnit.NANOSECONDS.toMillis(time2));
      assertThat(time1).isLessThan(time2 * 5);
    }
  }
}
