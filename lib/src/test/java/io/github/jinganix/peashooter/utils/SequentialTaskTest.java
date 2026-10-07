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

package io.github.jinganix.peashooter.utils;

import static java.util.concurrent.CompletableFuture.runAsync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SequentialTask")
class SequentialTaskTest {

  @Test
  @DisplayName("should release the lock when the delegate throws")
  void shouldReleaseLockWhenDelegateThrows() {
    // Given a task whose delegate fails
    AtomicReference<Thread> lock = new AtomicReference<>();
    RuntimeException failure = new RuntimeException("delegate failed");
    SequentialTask failing =
        new SequentialTask(
            lock,
            () -> {
              throw failure;
            });

    // When it runs, Then the original failure propagates and the lock is released
    try {
      failing.run();
      fail("expected delegate failure");
    } catch (RuntimeException e) {
      assertThat(e).isSameAs(failure);
    }
    assertThat(lock.get()).isNull();

    // And a subsequent task runs normally instead of misreporting concurrency
    AtomicBoolean ran = new AtomicBoolean(false);
    new SequentialTask(lock, () -> ran.set(true)).run();
    assertThat(ran.get()).isTrue();
  }

  @Test
  @DisplayName("should fail when two tasks run concurrently")
  void shouldFailWhenTwoTasksRunConcurrently() {
    // Given
    AtomicReference<Thread> lock = new AtomicReference<>();

    // When
    CompletableFuture<?> future =
        CompletableFuture.allOf(
            runAsync(new SequentialTask(lock, () -> TestUtils.sleep(1000))),
            runAsync(new SequentialTask(lock, () -> TestUtils.sleep(1000))));

    // Then
    assertThatThrownBy(future::join)
        .isInstanceOf(CompletionException.class)
        .rootCause()
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Task is running concurrently");
  }

  @Test
  @DisplayName("should guard mutual exclusion with a single atomic word, not split state")
  void shouldGuardMutualExclusionWithASingleAtomicWord() throws Exception {
    // Given the unlock window: owner=null then lock=false are two separate writes, so a
    // contender arriving between them observes lock==true with owner==null and fails
    // spuriously ("owned by unknown") even though nobody runs the delegate anymore.
    // A single AtomicReference<Thread> guard acquires and releases in one word.
    long splitStateFields =
        java.util.Arrays.stream(SequentialTask.class.getDeclaredFields())
            .filter(f -> f.getType() == AtomicBoolean.class || f.getName().equals("owner"))
            .count();
    assertThat(splitStateFields).as("SequentialTask must not keep split lock+owner state").isZero();
    assertThat(
            java.util.Arrays.stream(SequentialTask.class.getDeclaredFields())
                .anyMatch(f -> f.getType() == AtomicReference.class))
        .as("SequentialTask must hold a single AtomicReference guard")
        .isTrue();
  }

  @Test
  @DisplayName("should never report concurrency for strictly sequential executions")
  void shouldNeverReportConcurrencyForStrictlySequentialExecutions() throws Exception {
    // Given strictly sequential executions on one shared guard (single-threaded handoff)
    AtomicReference<Thread> guard = new AtomicReference<>();
    int tasks = 5_000;
    CountDownLatch done = new CountDownLatch(tasks);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      for (int i = 0; i < tasks; i++) {
        pool.execute(new SequentialTask(guard, done::countDown));
      }
      // When every task runs one at a time Then none misreports concurrency (no unlock gap)
      assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }
}
