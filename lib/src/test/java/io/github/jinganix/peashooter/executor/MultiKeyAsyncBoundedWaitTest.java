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

package io.github.jinganix.peashooter.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Multi-key async bounded wait")
class MultiKeyAsyncBoundedWaitTest {

  @Test
  @DisplayName("async multi-key on contended inner key must time out, not hang forever")
  void asyncMultiKeyMustTimeOutInsteadOfHang() throws Exception {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    pool.setThreadFactory(
        r -> {
          Thread t = new Thread(r, "multi-pool");
          t.setDaemon(true);
          return t;
        });
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
    executor.setTimeout(Duration.ofMillis(300));
    java.util.concurrent.CountDownLatch blockerStarted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseBlocker = new java.util.concurrent.CountDownLatch(1);
    // Occupy key b's queue runner so the async chain's inner acquisition must wait.
    executor.executeAsync(
        "mk-async-b",
        () -> {
          blockerStarted.countDown();
          try {
            releaseBlocker.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertThat(blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();
    try {
      CompletableFuture<Void> future =
          executor.submitAsync(List.of("mk-async-a", "mk-async-b"), () -> {});
      try {
        future.get(5, TimeUnit.SECONDS);
        fail("expected async multi-key to fail fast with timeout");
      } catch (java.util.concurrent.ExecutionException expected) {
        assertThat(expected.getCause()).isInstanceOf(TraceTimeoutException.class);
      }
    } finally {
      releaseBlocker.countDown();
      pool.shutdownNow();
    }
  }
}
