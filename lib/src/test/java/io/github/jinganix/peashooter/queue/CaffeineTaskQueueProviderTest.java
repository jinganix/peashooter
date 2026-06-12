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

package io.github.jinganix.peashooter.queue;

import static io.github.jinganix.peashooter.utils.TestUtils.awaitCountDown;
import static io.github.jinganix.peashooter.utils.TestUtils.sleep;
import static io.github.jinganix.peashooter.utils.TestUtils.uncheckedRun;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CaffeineTaskQueueProvider")
class CaffeineTaskQueueProviderTest {

  @Test
  @DisplayName("should reject null key on get")
  void shouldRejectNullKeyOnGet() {
    // Given
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();

    // When / Then
    assertThatThrownBy(() -> provider.get(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("key");
  }

  @Test
  @DisplayName("should not evict queue while a task is still running")
  void shouldNotEvictQueueWhileTaskIsStillRunning() {
    // Given
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMillis(50));
    String key = "key";
    Executor executor = newCachedThreadPool();
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);

    // When: one long-running task, then wait past idle expiry with no new get() calls
    provider
        .get(key)
        .execute(
            executor,
            () -> {
              firstStarted.countDown();
              int active = concurrent.incrementAndGet();
              maxConcurrent.updateAndGet(max -> Math.max(max, active));
              uncheckedRun(releaseFirst::await);
              concurrent.decrementAndGet();
            });
    awaitCountDown(firstStarted);
    sleep(200);
    provider.cleanUp();

    provider
        .get(key)
        .execute(
            executor,
            () -> {
              int active = concurrent.incrementAndGet();
              maxConcurrent.updateAndGet(max -> Math.max(max, active));
              concurrent.decrementAndGet();
              secondDone.countDown();
            });
    releaseFirst.countDown();
    awaitCountDown(secondDone);

    // Then: same-key tasks must not overlap (two queues would run concurrently)
    assertThat(maxConcurrent).hasValue(1);
  }
}
