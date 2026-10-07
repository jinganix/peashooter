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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/** RedisClient must stay lazy and fail with an actionable message when unconfigured. */
@DisplayName("RedisClient")
class RedisClientTest {

  private String backup;

  @BeforeEach
  void clearAddress() {
    assumeTrue(System.getenv("redis-host") == null, "requires no redis-host env var");
    backup = System.getProperty("redis-host");
    System.clearProperty("redis-host");
  }

  @AfterEach
  void restoreAddress() {
    if (backup != null) {
      System.setProperty("redis-host", backup);
    } else {
      System.clearProperty("redis-host");
    }
  }

  @Test
  @DisplayName("should not connect when class is loaded")
  void shouldNotConnectWhenClassIsLoaded() {
    // Given no redis-host address configured
    // When the RedisClient class is loaded
    // Then it must not eagerly connect or throw ExceptionInInitializerError
    assertThatCode(() -> Class.forName("io.github.jinganix.peashooter.redisson.setup.RedisClient"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("should give actionable message when address is missing")
  void shouldGiveActionableMessageWhenAddressIsMissing() {
    // Given no redis-host address configured
    // When a client is created
    // Then the failure must name redis-host instead of a raw null-address NPE
    assertThatThrownBy(RedisClient::createClient).hasMessageContaining("redis-host");
  }

  @Test
  @DisplayName("should shutdown cached client on close")
  void shouldShutdownCachedClientOnClose() throws Exception {
    // Given a cached global client (fake to avoid Docker)
    AtomicBoolean shutdown = new AtomicBoolean(false);
    RedissonClient fake =
        (RedissonClient)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {RedissonClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("shutdown")) {
                    shutdown.set(true);
                    return null;
                  }
                  if (method.getName().equals("toString")) {
                    return "fake-redisson";
                  }
                  if (method.getReturnType() == boolean.class) {
                    return false;
                  }
                  return null;
                });
    Field field = RedisClient.class.getDeclaredField("client");
    field.setAccessible(true);
    field.set(null, fake);
    try {
      // When closed Then netty threads are released and the cache is cleared
      RedisClient.close();
      assertThat(shutdown.get()).isTrue();
      assertThat(field.get(null)).isNull();
      // And close is idempotent
      RedisClient.close();
    } finally {
      field.set(null, null);
    }
  }
}
