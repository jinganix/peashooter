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

package io.github.jinganix.peashooter.queue;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.jinganix.peashooter.TaskQueueProvider;
import java.time.Duration;

/** {@link TaskQueueProvider} implemented with {@link Caffeine}. */
public class CaffeineTaskQueueProvider implements TaskQueueProvider {

  private final LoadingCache<String, TaskQueue> queues;

  /** Constructor. */
  public CaffeineTaskQueueProvider() {
    this(Duration.ofMinutes(5));
  }

  /**
   * Expire a queue after access.
   *
   * @param expireAfterAccess {@link Duration}
   */
  public CaffeineTaskQueueProvider(Duration expireAfterAccess) {
    this.queues =
        Caffeine.newBuilder().expireAfterAccess(expireAfterAccess).build(key -> new TaskQueue());
  }

  @Override
  public TaskQueue get(String key) {
    return queues.get(key);
  }
}
