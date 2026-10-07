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

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * Redis jupiter extension. Every test class gets its own extension instance, but all instances
 * share one lazily started container: the second and later {@link #beforeAll} calls observe the
 * same instance instead of building containers that are silently discarded. The lazy holder is
 * fully synchronized, so parallel test execution still starts exactly one container. A failed start
 * is never cached: the next {@link #beforeAll} retries instead of running tests against a missing
 * address.
 *
 * <p>Single owner of the {@code redis-host} global: {@link RedisContainer} itself publishes no
 * global state, and this extension publishes the shared container address exactly once (when it
 * starts the container). {@link #ownedUrl} pins that address so it is never mistaken for
 * user-provided configuration.
 */
public class RedisExtension implements BeforeAllCallback {

  private static final Object LOCK = new Object();

  private static final Namespace NAMESPACE = Namespace.create(RedisExtension.class);

  private static volatile RedisContainer shared;

  /**
   * Address published by our own container start. The external-redis check compares against it so
   * our own address is never mistaken for user configuration.
   */
  private static volatile String ownedUrl;

  static {
    Runtime.getRuntime()
        .addShutdownHook(new Thread(RedisExtension::close, "redis-container-shutdown"));
  }

  /** Closes the shared container at the end of the test run (root store). */
  private static final class SharedCleanup implements AutoCloseable {
    @Override
    public void close() {
      RedisExtension.close();
    }
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    sharedContainer();
    if (context != null) {
      context
          .getRoot()
          .getStore(NAMESPACE)
          .computeIfAbsent("redis-shared-cleanup", key -> new SharedCleanup());
    }
  }

  /**
   * Returns the shared container, starting it once on first use.
   *
   * @return the shared started container, or {@code null} when an external redis is configured via
   *     the {@code redis-host} environment variable or system property (no container is owned then)
   */
  static RedisContainer sharedContainer() {
    synchronized (LOCK) {
      if (isExternalRedis()) {
        return null;
      }
      if (shared != null) {
        return shared;
      }
      RedisContainer created = new RedisContainer().withReuse(isReuseEnabled());
      created.start();
      ownedUrl = created.getRedisUrl();
      System.setProperty("redis-host", ownedUrl);
      shared = created;
      return created;
    }
  }

  /**
   * Whether Testcontainers reuse is explicitly opted in. Reuse is off by default so CI parallel
   * runs never share dirty containers; set {@code TESTCONTAINERS_REUSE_ENABLE=true} (env or system
   * property) to keep the container across runs.
   */
  static boolean isReuseEnabled() {
    if ("true".equalsIgnoreCase(System.getenv("TESTCONTAINERS_REUSE_ENABLE"))) {
      return true;
    }
    return "true".equalsIgnoreCase(System.getProperty("TESTCONTAINERS_REUSE_ENABLE"));
  }

  /**
   * Stops the shared container (when owned) and shuts down the global client, clearing only the
   * address we published. Idempotent; also wired to the root {@link ExtensionContext.Store} and a
   * JVM shutdown hook so neither netty threads nor the container outlive the test JVM. With reuse
   * enabled the container is intentionally left running for the next run.
   */
  static void close() {
    synchronized (LOCK) {
      try {
        RedisClient.close();
      } finally {
        RedisContainer container = shared;
        shared = null;
        String owned = ownedUrl;
        ownedUrl = null;
        if (container != null && !container.isShouldBeReused()) {
          try {
            container.stop();
          } catch (RuntimeException ignored) {
            // Best effort: container may already be stopped by Ryuk or another JVM.
          }
        }
        if (owned != null && owned.equals(System.getProperty("redis-host"))) {
          System.clearProperty("redis-host");
        }
      }
    }
  }

  /**
   * Whether an external redis address is configured. Our own container address never counts: it is
   * tracked in {@link #ownedUrl} when started.
   *
   * @return {@code true} when the {@code redis-host} environment variable or system property points
   *     somewhere we did not start
   */
  static boolean isExternalRedis() {
    if (System.getenv("redis-host") != null) {
      return true;
    }
    String address = System.getProperty("redis-host");
    return address != null && !address.equals(ownedUrl);
  }
}
