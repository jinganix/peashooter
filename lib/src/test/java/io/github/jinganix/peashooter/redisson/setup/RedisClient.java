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

import java.util.Objects;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

public final class RedisClient {

  private static volatile RedissonClient client;

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(RedisClient::close, "redis-client-shutdown"));
  }

  private RedisClient() {}

  public static RedissonClient get() {
    RedissonClient result = client;
    if (result == null) {
      synchronized (RedisClient.class) {
        result = client;
        if (result == null) {
          result = createClient();
          client = result;
        }
      }
    }
    return result;
  }

  /**
   * Shuts down the cached client and clears it, releasing netty threads. Idempotent; also wired as
   * a JVM shutdown hook so the single-test JVM never leaks threads.
   */
  public static void close() {
    synchronized (RedisClient.class) {
      RedissonClient cached = client;
      client = null;
      if (cached != null) {
        cached.shutdown();
      }
    }
  }

  public static RedissonClient createClient() {
    Config config = new Config();
    String address = System.getenv("redis-host");
    if (address == null) {
      address = System.getProperty("redis-host");
    }
    Objects.requireNonNull(
        address,
        "redis-host is not set. Set env var 'redis-host' or system property 'redis-host'"
            + " to a redis URL (e.g. redis://127.0.0.1:6379)."
            + " RedisExtension normally sets it via Testcontainers.");
    config.useSingleServer().setAddress(address);
    return Redisson.create(config);
  }
}
