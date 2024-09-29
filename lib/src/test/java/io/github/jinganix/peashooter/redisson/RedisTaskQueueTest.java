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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

@DisplayName("MultiProcess")
@ExtendWith(RedisExtension.class)
public class RedisTaskQueueTest {

  private final OrderedTraceExecutor traceExecutor = createExecutor();

  private final RedissonClient client = RedisClient.client;

  static OrderedTraceExecutor createExecutor() {
    Tracer tracer = new DefaultTracer();
    TraceExecutor traceExecutor = new TraceExecutor(Executors.newCachedThreadPool(), tracer);
    DefaultExecutorSelector selector = new DefaultExecutorSelector(traceExecutor);
    return new OrderedTraceExecutor(new RedisTaskQueueProvider(), selector, tracer);
  }

  @BeforeEach
  void setup() {
    client.getKeys().flushall();
  }

  @Nested
  @DisplayName("when execute 1 task")
  class WhenExecute1Task {

    @Test
    @DisplayName("then task is executed")
    void thenTaskIsExecuted() {
      RList<TestItem> list = client.getList("list");
      TestItem value =
          traceExecutor.supply(
              "a",
              () -> {
                TestItem item = new TestItem(0, 1).setMillis(System.currentTimeMillis());
                list.add(item);
                return item;
              });
      assertThat(list.get(0)).usingRecursiveComparison().isEqualTo(value);
    }
  }

  @Nested
  @DisplayName("when execute 10 task")
  class WhenExecute10Task {

    @Test
    @DisplayName("then tasks are executed")
    void thenTasksAreExecuted() throws InterruptedException {
      RList<TestItem> list = client.getList("list");
      List<TestItem> items = new ArrayList<>();
      CountDownLatch latch = new CountDownLatch(10);
      for (int i = 0; i < 10; i++) {
        TestItem item = new TestItem(0, i);
        items.add(item);
        traceExecutor.executeAsync(
            "a",
            () -> {
              item.setMillis(System.currentTimeMillis());
              list.add(item);
              latch.countDown();
            });
      }
      latch.await();
      assertThat(list.readAll()).usingRecursiveComparison().isEqualTo(items);
    }
  }
}
