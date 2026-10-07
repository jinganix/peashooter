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

import java.util.Locale;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Start a redis docker container.
 *
 * <p>Side-effect free: starting publishes no global state. The address is exposed via {@link
 * #getRedisUrl()}; only {@link RedisExtension} publishes it as the {@code redis-host} system
 * property, so parallel or multi-JVM runs never overwrite each other's address.
 *
 * <p>Final on purpose: the constructor calls {@link GenericContainer#addExposedPort(int)}, and a
 * subclass could observe a partially initialized {@code this} if it overrode the method.
 */
public final class RedisContainer extends GenericContainer<RedisContainer> {

  /** REDIS_PORT. */
  public static final int REDIS_PORT = 6379;

  private static final String VERSION = "8.6.0-alpine";

  /** Constructor. */
  public RedisContainer() {
    super(DockerImageName.parse((isArm64() ? "arm64v8/redis:" : "redis:") + VERSION));
    this.addExposedPort(REDIS_PORT);
  }

  private static boolean isArm64() {
    // Some distributions report "arm64" instead of "aarch64" (and 32-bit ones "armv7l"):
    // match the arm family instead of a single spelling, or x86 images get pulled on ARM.
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    return arch.contains("aarch64") || arch.contains("arm");
  }

  /**
   * Address of the started container.
   *
   * @return redis URL for the mapped port
   */
  public String getRedisUrl() {
    return "redis://" + this.getHost() + ":" + getMappedPort(REDIS_PORT);
  }
}
