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

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Tracer fallback concurrency")
class TracerFallbackConcurrencyTest {

  @Test
  @DisplayName("should keep every fallback when recorded concurrently")
  void shouldKeepEveryFallbackWhenRecordedConcurrently() throws Exception {
    // Given one tracer shared by many degraded runs
    DefaultTracer tracer = new DefaultTracer();
    int threads = 8;
    int perThread = 5_000;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.execute(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              for (int j = 0; j < perThread; j++) {
                tracer.recordTracerFallback();
              }
              done.countDown();
            });
      }

      // When every thread records at once
      start.countDown();
      done.await();

      // Then no increment is lost to a read-modify-write race
      assertThat(tracer.getTracerFallbackCount()).isEqualTo((long) threads * perThread);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should saturate at max value instead of wrapping")
  void shouldSaturateAtMaxValueInsteadOfWrapping() throws Exception {
    // Given a fallback count one below saturation
    DefaultTracer tracer = new DefaultTracer();
    Field field = AbstractTracer.class.getDeclaredField("tracerFallbackCount");
    field.setAccessible(true);
    ((AtomicLong) field.get(tracer)).set(Long.MAX_VALUE - 1);

    // When recording past the maximum Then the count pins instead of wrapping
    tracer.recordTracerFallback();
    assertThat(tracer.getTracerFallbackCount()).isEqualTo(Long.MAX_VALUE);
    tracer.recordTracerFallback();
    assertThat(tracer.getTracerFallbackCount()).isEqualTo(Long.MAX_VALUE);
  }
}
