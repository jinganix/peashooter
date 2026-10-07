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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Executor fixture")
class ExecutorFixtureTest {

  @Test
  @DisplayName("should run pooled tasks on daemon threads so leaks never pin the test JVM")
  void shouldRunPooledTasksOnDaemonThreads() throws Exception {
    // Given a privately owned holder
    try (ExecutorFixture holders = new ExecutorFixture()) {
      Map<String, Executor> executors = holders.executors();

      // When each thread-owning pool runs one task, Then every worker must be daemon
      for (Map.Entry<String, Executor> entry : executors.entrySet()) {
        if (entry.getValue() instanceof DirectExecutor) {
          continue;
        }
        ExecutorService service = (ExecutorService) entry.getValue();
        AtomicBoolean daemon = new AtomicBoolean(false);
        Future<?> future = service.submit(() -> daemon.set(Thread.currentThread().isDaemon()));
        future.get(10, TimeUnit.SECONDS);
        assertThat(daemon.get()).as(entry.getKey()).isTrue();
      }
    }
  }

  @Test
  @DisplayName("should run direct executor inline on the calling thread")
  void shouldRunDirectExecutorInline() {
    try (ExecutorFixture holders = new ExecutorFixture()) {
      AtomicReference<Thread> runner = new AtomicReference<>();
      holders.executors().get("DirectExecutor").execute(() -> runner.set(Thread.currentThread()));

      assertThat(runner.get()).isSameAs(Thread.currentThread());
    }
  }

  @Test
  @DisplayName("should give each holder its own pools instead of sharing across holders")
  void shouldGiveEachHolderItsOwnPools() {
    // Given two holders for two test classes
    try (ExecutorFixture first = new ExecutorFixture();
        ExecutorFixture second = new ExecutorFixture()) {

      // When / Then every pool is a distinct instance per holder, and the view is read-only
      // so no caller can corrupt the holder's pools for other tests
      assertThat(second.executors().keySet()).isEqualTo(first.executors().keySet());
      for (String name : first.executors().keySet()) {
        Executor owned = first.executors().get(name);
        Executor other = second.executors().get(name);
        if (owned instanceof ExecutorService && other instanceof ExecutorService) {
          assertThat(other).as(name).isNotSameAs(owned);
        } else {
          assertThat(other).as(name).isSameAs(owned);
        }
      }
      assertThatThrownBy(() -> second.executors().put("extra", Runnable::run))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  @DisplayName("should shut down only its own pools on close")
  void shouldShutDownOnlyItsOwnPoolsOnClose() {
    // Given two holders sharing the JVM
    ExecutorFixture first = new ExecutorFixture();
    ExecutorFixture second = new ExecutorFixture();
    List<ExecutorService> firstPools = threadPools(first.executors());
    List<ExecutorService> secondPools = threadPools(second.executors());
    assertThat(firstPools).isNotEmpty();
    for (ExecutorService service : firstPools) {
      assertThat(service.isShutdown()).isFalse();
    }

    // When one holder closes Then only its own pools are shut down
    first.close();
    try {
      for (ExecutorService service : firstPools) {
        assertThat(service.isShutdown()).isTrue();
      }
      for (ExecutorService service : secondPools) {
        assertThat(service.isShutdown()).isFalse();
      }
    } finally {
      second.close();
    }
  }

  @Test
  @DisplayName("should not interrupt other holders in-flight tasks when one holder closes")
  void shouldNotInterruptOtherHoldersInFlightTasksWhenOneHolderCloses() throws Exception {
    // Given an in-flight task on one holder's pool
    ExecutorFixture first = new ExecutorFixture();
    ExecutorFixture second = new ExecutorFixture();
    try {
      ExecutorService pool = (ExecutorService) first.executors().get("SingleThreadExecutor");
      CountDownLatch release = new CountDownLatch(1);
      Future<String> flight =
          pool.submit(
              () -> {
                release.await();
                return "done";
              });

      // When the other holder closes (e.g. another class's AfterAll)
      second.close();

      // Then the in-flight task still runs to completion instead of being interrupted
      release.countDown();
      assertThat(flight.get(10, TimeUnit.SECONDS)).isEqualTo("done");
    } finally {
      first.close();
      second.close();
    }
  }

  private static List<ExecutorService> threadPools(Map<String, Executor> executors) {
    List<ExecutorService> services = new ArrayList<>();
    for (Executor executor : executors.values()) {
      if (executor instanceof ExecutorService service) {
        services.add(service);
      }
    }
    return services;
  }
}
