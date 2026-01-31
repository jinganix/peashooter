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

import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.executor.DefaultExecutorSelector;
import io.github.jinganix.peashooter.executor.OrderedTraceExecutor;
import io.github.jinganix.peashooter.executor.TraceExecutor;
import io.github.jinganix.peashooter.redisson.setup.RedisClient;
import io.github.jinganix.peashooter.redisson.setup.RedisExtension;
import io.github.jinganix.peashooter.redisson.setup.RedisTaskQueueProvider;
import io.github.jinganix.peashooter.redisson.setup.TestItem;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import io.github.jinganix.peashooter.utils.SequentialTask;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

@DisplayName("MultiProcess")
@ExtendWith(RedisExtension.class)
public class RedisMultiProviderTest {

  private static final ExecutorService executorService = Executors.newCachedThreadPool();

  private final RedissonClient client = RedisClient.createClient();

  private final RedissonClient client2 = RedisClient.createClient();

  static OrderedTraceExecutor createExecutor() {
    Tracer tracer = new DefaultTracer();
    TraceExecutor traceExecutor = new TraceExecutor(executorService, tracer);
    DefaultExecutorSelector selector = new DefaultExecutorSelector(traceExecutor);
    return new OrderedTraceExecutor(new RedisTaskQueueProvider(), selector, tracer);
  }

  @BeforeEach
  void setup() {
    client.getKeys().flushall();
  }

  @AfterAll
  static void cleanup() {
    executorService.shutdown();
  }

  @Nested
  @DisplayName("when execute 10 task")
  class WhenExecute10Task {

    private List<Runnable> getTasks(
        int taskId, CountDownLatch latch, AtomicBoolean lock, RedissonClient client) {
      RList<TestItem> list = client.getList("list");
      OrderedTraceExecutor traceExecutor = createExecutor();
      List<Runnable> tasks = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        TestItem item = new TestItem(taskId, i);
        Runnable task =
            new SequentialTask(
                lock,
                () -> {
                  item.setMillis(System.currentTimeMillis());
                  list.add(item);
                  latch.countDown();
                });
        tasks.add(() -> traceExecutor.executeAsync("a", task));
      }
      return tasks;
    }

    @Test
    @DisplayName("then tasks are executed")
    void thenTasksAreExecuted() throws InterruptedException {
      CountDownLatch latch = new CountDownLatch(20);
      AtomicBoolean lock = new AtomicBoolean(false);
      List<CompletableFuture<Void>> futures =
          Stream.of(getTasks(0, latch, lock, client), getTasks(1, latch, lock, client2))
              .flatMap(List::stream)
              .map(CompletableFuture::runAsync)
              .toList();
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      latch.await();
      RList<TestItem> list = client.getList("list");
      List<TestItem> items = list.readAll();
      for (int i = 0; i < items.size() - 1; i++) {
        if ((i + 1) % 5 == 0) {
          assertThat(items.get(i).getTask()).isNotEqualTo(items.get(i + 1).getTask());
        } else {
          assertThat(items.get(i).getTask()).isEqualTo(items.get(i + 1).getTask());
        }
      }
    }
  }
}
