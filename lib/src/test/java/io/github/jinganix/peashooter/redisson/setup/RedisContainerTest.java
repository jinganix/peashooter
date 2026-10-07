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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RedisContainer")
class RedisContainerTest {

  private String imageForArch(String arch) {
    String previous = System.getProperty("os.arch");
    System.setProperty("os.arch", arch);
    try {
      // Docker-free: getDockerImageName() resolves RemoteDockerImage (Docker client + pull),
      // which fails without Docker or on arch mismatch. getImage() returns the unresolved
      // RemoteDockerImage whose toString embeds the canonical name without any Docker call.
      return new RedisContainer().getImage().toString();
    } finally {
      if (previous == null) {
        System.clearProperty("os.arch");
      } else {
        System.setProperty("os.arch", previous);
      }
    }
  }

  @Test
  @DisplayName("should use the arm image for aarch64")
  void shouldUseArmImageForAarch64() {
    assertThat(imageForArch("aarch64")).contains("imageName=arm64v8/redis:");
  }

  @Test
  @DisplayName("should use the arm image for arm64")
  void shouldUseArmImageForArm64() {
    // Some distributions report "arm64" instead of "aarch64": both must resolve to ARM.
    assertThat(imageForArch("arm64")).contains("imageName=arm64v8/redis:");
  }

  @Test
  @DisplayName("should use the x86 image for amd64")
  void shouldUseX86ImageForAmd64() {
    assertThat(imageForArch("amd64")).contains("imageName=redis:").doesNotContain("arm64v8");
  }

  @Test
  @DisplayName("should publish no global state on start")
  void shouldPublishNoGlobalStateOnStart() {
    // Starting a container needs Docker; CI provides an external redis on both legs (one of
    // them a Docker-less macOS runner), so skip exactly like the other container lifecycle tests.
    assumeFalse(RedisExtension.isExternalRedis(), "requires container-managed redis");
    // The container exposes its address via getRedisUrl; only RedisExtension publishes it
    // as the redis-host property, so parallel/multi-JVM runs never overwrite each other.
    String previous = System.getProperty("redis-host");
    RedisContainer container = new RedisContainer();
    container.start();
    try {
      assertThat(System.getProperty("redis-host")).isEqualTo(previous);
      assertThat(container.getRedisUrl()).startsWith("redis://");
    } finally {
      container.stop();
    }
  }
}
