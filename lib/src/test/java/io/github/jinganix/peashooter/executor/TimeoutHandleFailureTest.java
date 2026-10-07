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

import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Timeout/interrupt must carry a cancellable/dedup handle and supplyAsync must return a cancellable
 * future. Timeout means submitted: late completion is dropped via cancel attempt and the caller
 * retries non-idempotent work with the key/future handle.
 */
@DisplayName("Timeout handle failure")
class TimeoutHandleFailureTest {

  private static OrderedTraceExecutor executor() {
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(),
            new DefaultExecutorSelector(
                new TraceExecutor(
                    java.util.concurrent.Executors.newSingleThreadExecutor(), new DefaultTracer())),
            new DefaultTracer());
    executor.setTimeout(Duration.ofMillis(50));
    return executor;
  }

  @Test
  @DisplayName("should carry cancellable future when supply times out")
  void shouldCarryCancellableFutureWhenSupplyTimesOut() {
    OrderedTraceExecutor executor = executor();
    CountDownLatch release = new CountDownLatch(1);
    executor.executeAsync(
        "k",
        () -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      AtomicReference<TraceTimeoutException> captured = new AtomicReference<>();
      assertThatThrownBy(() -> executor.supply("k", () -> "late"))
          .isInstanceOf(TraceTimeoutException.class)
          .satisfies(e -> captured.set((TraceTimeoutException) e));
      TraceTimeoutException timeout = captured.get();
      assertThat(timeout.getKey()).isEqualTo("k");
      CompletableFuture<?> future = timeout.getFuture();
      assertThat(future).isNotNull();
      assertThat(future.isCancelled() || future.isDone()).isTrue();
    } finally {
      release.countDown();
    }
  }

  @Test
  @DisplayName("should carry cancellable future when sync is interrupted")
  void shouldCarryCancellableFutureWhenSyncIsInterrupted() {
    OrderedTraceExecutor executor = executor();
    CountDownLatch release = new CountDownLatch(1);
    executor.executeAsync(
        "k",
        () -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    Thread.currentThread().interrupt();
    try {
      AtomicReference<TraceInterruptedException> captured = new AtomicReference<>();
      assertThatThrownBy(() -> executor.executeSync("k", () -> {}))
          .isInstanceOf(TraceInterruptedException.class)
          .satisfies(e -> captured.set((TraceInterruptedException) e));
      TraceInterruptedException interrupted = captured.get();
      assertThat(interrupted.getKey()).isEqualTo("k");
      assertThat(interrupted.getFuture()).isNotNull();
    } finally {
      Thread.interrupted();
      release.countDown();
    }
  }

  @Test
  @DisplayName("should return cancellable future when supplyAsync runs")
  void shouldReturnCancellableFutureWhenSupplyAsyncRuns() throws Exception {
    OrderedTraceExecutor executor = executor();
    executor.setTimeout(Duration.ofSeconds(5));
    CompletableFuture<String> future = executor.supplyAsync("k", () -> "ok");
    assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
  }
}
