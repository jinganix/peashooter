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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A single-key collection sync call must behave exactly like its scalar counterpart: nested
 * same-key with no queued peers runs inline instead of enqueueing behind the caller and
 * self-deadlocking.
 */
@DisplayName("Single-key collection sync reentrancy")
class MultiKeySingleKeySyncReentrancyTest {

  @Test
  @DisplayName("should run nested single-key collection sync inline instead of self-deadlocking")
  void shouldRunNestedSingleKeyCollectionSyncInline() {
    // Given a one-thread pool: enqueueing behind the running caller can never complete
    ExecutorService pool =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "single-key-sync-reentrancy");
              thread.setDaemon(true);
              return thread;
            });
    try {
      OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
      executor.setTimeout(Duration.ofSeconds(2));
      AtomicInteger inlineRuns = new AtomicInteger();

      // When the same key is re-entered through the collection overloads
      executor.executeSync(
          "k",
          () -> {
            executor.executeSync(List.of("k"), inlineRuns::incrementAndGet);
            executor.supply(
                List.of("k"),
                () -> {
                  inlineRuns.incrementAndGet();
                  return null;
                });
            executor.supplyChecked(
                List.of("k"),
                RuntimeException.class,
                () -> {
                  inlineRuns.incrementAndGet();
                  return null;
                });
          });

      // Then every nested call ran inline rather than timing out
      assertThat(inlineRuns).hasValue(3);
    } finally {
      pool.shutdownNow();
    }
  }
}
