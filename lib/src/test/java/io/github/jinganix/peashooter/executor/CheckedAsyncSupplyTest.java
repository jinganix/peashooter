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

import static io.github.jinganix.peashooter.utils.TestUtils.awaitIdle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.ThrowingSupplier;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Checked async supply")
class CheckedAsyncSupplyTest {

  private static ThreadPoolExecutor newPool(int threads) {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    pool.setThreadFactory(runnable -> {
      Thread thread = new Thread(runnable, "checked-async-pool");
      thread.setDaemon(true);
      return thread;
    });
    return pool;
  }

  @Test
  @DisplayName("should complete the future with the value when the checked supplier succeeds")
  void shouldCompleteFutureWithValueWhenCheckedSupplierSucceeds() throws Exception {
    // Given
    ExecutorService pool = newPool(2);
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);

    try {
      // When
      CompletableFuture<Integer> future = executor.supplyCheckedAsync("checked-key", () -> 7);

      // Then
      assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(7);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should carry the declared checked failure in the future when the supplier throws")
  void shouldCarryDeclaredCheckedFailureInFutureWhenSupplierThrows() throws Exception {
    // Given
    ExecutorService pool = newPool(2);
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
    IOException failure = new IOException("checked boom");
    ThrowingSupplier<Integer, IOException> supplier =
        () -> {
          throw failure;
        };

    try {
      // When
      CompletableFuture<Integer> future = executor.supplyCheckedAsync("checked-key", supplier);

      // Then
      assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IOException.class)
          .hasMessageContaining("checked boom");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should supply the value under every key when the multi-key checked chain succeeds")
  void shouldSupplyValueUnderEveryKeyWhenMultiKeyCheckedChainSucceeds() throws Exception {
    // Given a pool with enough threads for the nested key levels
    ExecutorService pool = newPool(3);
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);

    try {
      // When
      CompletableFuture<Integer> future =
          executor.supplyCheckedAsync(List.of("mkb", "mka", "mkb"), IOException.class, () -> 42);

      // Then
      assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(42);
      awaitIdle(executor, "mka", "mkb");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "should complete the multi-key future exceptionally when the checked supplier throws")
  void shouldCompleteMultiKeyFutureExceptionallyWhenCheckedSupplierThrows() throws Exception {
    // Given
    ExecutorService pool = newPool(3);
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
    IOException failure = new IOException("multi checked boom");
    ThrowingSupplier<Integer, IOException> supplier =
        () -> {
          throw failure;
        };

    try {
      // When
      CompletableFuture<Integer> future =
          executor.supplyCheckedAsync(List.of("mka", "mkb"), IOException.class, supplier);

      // Then
      assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IOException.class)
          .hasMessageContaining("multi checked boom");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should take the single-key path when the checked collection collapses to one key")
  void shouldTakeSingleKeyPathWhenCheckedCollectionCollapsesToOneKey() throws Exception {
    // Given
    ExecutorService pool = newPool(2);
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);

    try {
      // When
      CompletableFuture<Integer> future =
          executor.supplyCheckedAsync(
              List.of("checked-single", "checked-single"), IOException.class, () -> 9);

      // Then
      assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(9);
      awaitIdle(executor, "checked-single");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should reject a blank key and a null supplier for the checked async variants")
  void shouldRejectBlankKeyAndNullSupplierForCheckedAsyncVariants() {
    // Given
    OrderedTraceExecutor executor = new OrderedTraceExecutor(DirectExecutor.INSTANCE);

    // When / Then
    assertThatThrownBy(() ->
            executor.supplyCheckedAsync(" ", (ThrowingSupplier<Integer, IOException>) () -> 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> executor.supplyCheckedAsync("k", (ThrowingSupplier<Integer, IOException>) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> executor.supplyCheckedAsync(List.of("a", " "), IOException.class, () -> 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keys[1]");
  }
}
