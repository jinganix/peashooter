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

package io.github.jinganix.peashooter.redisson.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RedisExtension")
class RedisExtensionTest {

  @Test
  @DisplayName("should share one started container across extension instances")
  void shouldShareOneStartedContainerAcrossInstances() {
    assumeFalse(RedisExtension.isExternalRedis(), "requires container-managed redis");
    // Given one extension instance per test class (JUnit creates a new instance each time)
    RedisExtension first = new RedisExtension();
    RedisExtension second = new RedisExtension();

    // When both run beforeAll Then they share the same started container: nothing is
    // silently discarded and the address property points at it
    first.beforeAll(null);
    second.beforeAll(null);

    RedisContainer shared = RedisExtension.sharedContainer();
    assertThat(shared).isNotNull().isSameAs(RedisExtension.sharedContainer());
    assertThat(System.getProperty("redis-host")).isNotBlank();
  }

  @Test
  @DisplayName("should return the same container under concurrent start")
  void shouldReturnSameContainerUnderConcurrentStart() throws Exception {
    assumeFalse(RedisExtension.isExternalRedis(), "requires container-managed redis");
    // Given several extension instances starting concurrently (parallel test execution)
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<RedisContainer>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  new RedisExtension().beforeAll(null);
                  return RedisExtension.sharedContainer();
                }));
      }

      // When / Then every instance observes the single shared container
      Set<RedisContainer> distinct = new HashSet<>();
      for (Future<RedisContainer> future : futures) {
        distinct.add(future.get());
      }
      assertThat(distinct).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should disable reuse unless explicitly enabled")
  void shouldDisableReuseUnlessExplicitlyEnabled() {
    // Given no opt-in (default CI): reuse must stay off so parallel runs
    // never share dirty containers
    String previous = System.getProperty("TESTCONTAINERS_REUSE_ENABLE");
    System.clearProperty("TESTCONTAINERS_REUSE_ENABLE");
    try {
      assertThat(RedisExtension.isReuseEnabled()).isFalse();
      assertThat(new RedisContainer().withReuse(RedisExtension.isReuseEnabled()).isShouldBeReused())
          .isFalse();
    } finally {
      if (previous != null) {
        System.setProperty("TESTCONTAINERS_REUSE_ENABLE", previous);
      }
    }

    // When explicitly opted in Then reuse is honoured
    System.setProperty("TESTCONTAINERS_REUSE_ENABLE", "true");
    try {
      assertThat(RedisExtension.isReuseEnabled()).isTrue();
    } finally {
      if (previous != null) {
        System.setProperty("TESTCONTAINERS_REUSE_ENABLE", previous);
      } else {
        System.clearProperty("TESTCONTAINERS_REUSE_ENABLE");
      }
    }
  }

  @Test
  @DisplayName("should stop shared container on close")
  void shouldStopSharedContainerOnClose() throws Exception {
    assumeFalse(RedisExtension.isExternalRedis(), "requires container-managed redis");
    // Given a started shared container (reuse off by default)
    String reuseBackup = System.getProperty("TESTCONTAINERS_REUSE_ENABLE");
    System.clearProperty("TESTCONTAINERS_REUSE_ENABLE");
    try {
      new RedisExtension().beforeAll(null);
      RedisContainer shared = RedisExtension.sharedContainer();
      assertThat(shared).isNotNull();
      assertThat(shared.isRunning()).isTrue();
      assertThat(shared.isShouldBeReused()).isFalse();

      // When closed Then the container stops and the shared slot is cleared
      // (do not call sharedContainer() here: it would lazily restart a new one)
      RedisExtension.close();
      assertThat(shared.isRunning()).isFalse();
      java.lang.reflect.Field field = RedisExtension.class.getDeclaredField("shared");
      field.setAccessible(true);
      assertThat(field.get(null)).isNull();
    } finally {
      if (reuseBackup != null) {
        System.setProperty("TESTCONTAINERS_REUSE_ENABLE", reuseBackup);
      }
    }
  }

  @Test
  @DisplayName("should leave an explicitly configured redis-host alone")
  void shouldLeaveExplicitRedisHostAlone() {
    // Given an external redis address (saving whatever another test class started)
    String previous = System.getProperty("redis-host");
    String configured = "redis://127.0.0.1:6399";
    System.setProperty("redis-host", configured);
    try {
      // When extensions run Then no container is owned and the address is untouched
      new RedisExtension().beforeAll(null);
      new RedisExtension().beforeAll(null);

      assertThat(RedisExtension.sharedContainer()).isNull();
      assertThat(System.getProperty("redis-host")).isEqualTo(configured);
    } finally {
      if (previous == null) {
        System.clearProperty("redis-host");
      } else {
        System.setProperty("redis-host", previous);
      }
    }
  }
}
