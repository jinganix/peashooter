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

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Redis jupiter extension. */
public class RedisExtension implements BeforeAllCallback {

  private static final AtomicBoolean STARTED = new AtomicBoolean(false);

  private final RedisContainer container = new RedisContainer();

  /**
   * Start a container before all tests.
   *
   * @param context {@link ExtensionContext}
   */
  @Override
  public void beforeAll(ExtensionContext context) {
    if (System.getenv("redis-host") != null) {
      return;
    }
    if (STARTED.compareAndSet(false, true)) {
      this.container.withReuse(true).start();
    }
  }
}
