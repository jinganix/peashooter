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
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.jinganix.peashooter.TaskQueueProvider;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * {@link TaskQueueProvider} implemented with {@link Caffeine}.
 *
 * <p>Each distinct key maps to its own {@link TaskQueue}. Entries expire after the configured
 * access idle period once the queue is fully idle (no pending tasks and no active runner); while
 * work is in flight the entry is pinned so a long-running task cannot be split across two queue
 * instances for the same key.
 */
public class CaffeineTaskQueueProvider implements TaskQueueProvider {

  private static final long PINNED_NANOS = Long.MAX_VALUE;

  private final Duration expireAfterAccess;

  private final LoadingCache<String, TaskQueue> queues;

  /** Constructor. */
  public CaffeineTaskQueueProvider() {
    this(Duration.ofMinutes(5));
  }

  /**
   * Create a provider that expires idle queues after the given access duration.
   *
   * <p>Expiration is based on last access to the queue entry while the queue is idle. Entries stay
   * pinned while tasks are pending or a runner is active. Tune this value for the expected key
   * cardinality and how long per-key ordering must be preserved across idle gaps.
   *
   * @param expireAfterAccess idle period after which an unused queue entry is evicted
   */
  public CaffeineTaskQueueProvider(Duration expireAfterAccess) {
    this.expireAfterAccess = expireAfterAccess;
    this.queues =
        Caffeine.newBuilder()
            .expireAfter(new QueueExpiry(expireAfterAccess))
            .build(this::createQueue);
  }

  @Override
  public TaskQueue get(String key) {
    return queues.get(Objects.requireNonNull(key, "key"));
  }

  /** Triggers maintenance such as expiration; intended for tests. */
  void cleanUp() {
    queues.cleanUp();
  }

  private TaskQueue createQueue(String key) {
    return new TaskQueue() {

      @Override
      public void execute(Executor executor, Runnable runnable) {
        super.execute(executor, runnable);
        refreshExpiry(key);
      }

      @Override
      protected void run() {
        try {
          super.run();
        } finally {
          refreshExpiry(key);
        }
      }
    };
  }

  private void refreshExpiry(String key) {
    TaskQueue queue = queues.getIfPresent(key);
    if (queue != null) {
      queues.asMap().replace(key, queue);
    }
  }

  private static final class QueueExpiry implements Expiry<String, TaskQueue> {

    private final long idleNanos;

    private QueueExpiry(Duration expireAfterAccess) {
      this.idleNanos = expireAfterAccess.toNanos();
    }

    @Override
    public long expireAfterCreate(String key, TaskQueue queue, long currentTime) {
      return durationFor(queue);
    }

    @Override
    public long expireAfterUpdate(
        String key, TaskQueue queue, long currentTime, long currentDuration) {
      return durationFor(queue);
    }

    @Override
    public long expireAfterRead(
        String key, TaskQueue queue, long currentTime, long currentDuration) {
      return durationFor(queue);
    }

    private long durationFor(TaskQueue queue) {
      return queue.isEmpty() ? idleNanos : PINNED_NANOS;
    }
  }
}
