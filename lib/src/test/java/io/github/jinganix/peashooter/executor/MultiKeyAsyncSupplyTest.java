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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Multi-key async supply")
class MultiKeyAsyncSupplyTest {

  private static ThreadPoolExecutor newPool(int threads) {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    pool.setThreadFactory(runnable -> {
      Thread thread = new Thread(runnable, "multi-async-pool");
      thread.setDaemon(true);
      return thread;
    });
    return pool;
  }

  @Test
  @DisplayName("should run the body once when the multi-key collection de-duplicates")
  void shouldRunBodyOnceWhenMultiKeyCollectionDeduplicates() throws Exception {
    // Given
    ExecutorService pool = newPool(3);
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
    List<String> runs = Collections.synchronizedList(new ArrayList<>());

    try {
      // When
      CompletableFuture<Integer> future =
          executor.supplyAsync(
              List.of("mkb", "mka", "mkb"),
              () -> {
                runs.add("run");
                return 42;
              });

      // Then
      assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(42);
      assertThat(runs).containsExactly("run");
      assertThat(executor.isIdle("mka")).isTrue();
      assertThat(executor.isIdle("mkb")).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should complete the multi-key future exceptionally when the task throws")
  void shouldCompleteMultiKeyFutureExceptionallyWhenTaskThrows() throws Exception {
    // Given
    ExecutorService pool = newPool(3);
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
    IllegalStateException failure = new IllegalStateException("multi task boom");

    try {
      // When
      CompletableFuture<Void> future =
          executor.submitAsync(
              List.of("mka", "mkb"),
              () -> {
                throw failure;
              });

      // Then
      assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasMessageContaining("multi task boom");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should take the single-key path when the collection collapses to one key")
  void shouldTakeSingleKeyPathWhenCollectionCollapsesToOneKey() throws Exception {
    // Given
    ExecutorService pool = newPool(2);
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);

    try {
      // When
      CompletableFuture<Void> submitted =
          executor.submitAsync(List.of("single-key", "single-key"), () -> {});
      CompletableFuture<Integer> supplied = executor.supplyAsync(List.of("single-key"), () -> 5);

      // Then
      assertThat(submitted.get(10, TimeUnit.SECONDS)).isNull();
      assertThat(supplied.get(10, TimeUnit.SECONDS)).isEqualTo(5);
      assertThat(executor.isIdle("single-key")).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }
}
