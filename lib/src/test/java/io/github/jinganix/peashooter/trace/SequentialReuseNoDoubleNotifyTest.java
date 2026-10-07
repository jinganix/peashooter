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

package io.github.jinganix.peashooter.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SequentialReuseNoDoubleNotify")
class SequentialReuseNoDoubleNotifyTest {

  static class CountingProbe
      implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
    final AtomicInteger runs = new AtomicInteger();
    final AtomicInteger rejects = new AtomicInteger();

    @Override
    public void run() {
      runs.incrementAndGet();
    }

    @Override
    public void rejected(Throwable cause) {
      rejects.incrementAndGet();
    }
  }

  @Test
  @DisplayName("should not notify delegate again on sequential second run after success")
  void shouldNotNotifyDelegateAgainOnSequentialSecondRunAfterSuccess() {
    DefaultTracer tracer = new DefaultTracer();
    CountingProbe probe = new CountingProbe();
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", true, probe);
    runnable.run();
    assertThat(probe.runs.get()).isEqualTo(1);
    assertThat(probe.rejects.get()).isEqualTo(0);

    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThat(probe.runs.get()).isEqualTo(1);
    assertThat(probe.rejects.get())
        .as("sequential reuse after a completed run must not dispatch discard again")
        .isEqualTo(0);
  }

  @Test
  @DisplayName("should notify once for a concurrent loser while the winner is in flight")
  void shouldNotifyOnceForConcurrentLoserWhileWinnerIsInFlight() throws Exception {
    DefaultTracer tracer = new DefaultTracer();
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    CountingProbe probe =
        new CountingProbe() {
          @Override
          public void run() {
            super.run();
            entered.countDown();
            try {
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("delegate not released");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
          }
        };
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", true, probe);
    java.util.concurrent.atomic.AtomicReference<Throwable> loserFailure =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread winner =
        new Thread(
            () -> {
              try {
                runnable.run();
              } catch (Throwable failure) {
                loserFailure.compareAndSet(null, failure);
              }
            });
    winner.start();
    assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThat(probe.rejects.get())
        .as("concurrent loser must dispatch once so a sync waiter fails fast")
        .isEqualTo(1);

    release.countDown();
    winner.join(10_000);
    assertThat(probe.runs.get()).isEqualTo(1);
    assertThat(probe.rejects.get()).isEqualTo(1);
  }
}
