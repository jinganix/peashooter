/*
 * Copyright (c) 2020 linqu.tech, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jinganix.peashooter.redisson.setup;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Start a redis docker container. */
public class RedisContainer extends GenericContainer<RedisContainer> {

  /** REDIS_PORT. */
  public static final Integer REDIS_PORT = 6379;

  private static final String VERSION = "8.2.1-alpine";

  /** Constructor. */
  public RedisContainer() {
    super(DockerImageName.parse((isArm64() ? "arm64v8/redis:" : "redis:") + VERSION));
    this.addExposedPort(REDIS_PORT);
  }

  private static boolean isArm64() {
    return System.getProperty("os.arch").equals("aarch64");
  }

  /** Start a container. */
  @Override
  public void start() {
    super.start();
    System.setProperty("redis-host", getUrl());
  }

  private String getUrl() {
    return "redis://" + this.getHost() + ":" + getMappedPort(REDIS_PORT);
  }
}
