/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
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
 *
 * https://github.com/jinganix/peashooter
 */

package io.github.jinganix.peashooter.redisson.setup;

import io.github.jinganix.peashooter.LockableTaskQueue;
import io.github.jinganix.peashooter.TaskQueue;
import io.github.jinganix.peashooter.TaskQueueProvider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;

public class RedisTaskQueueProvider implements TaskQueueProvider {

  private final Map<String, TaskQueue> queues = new ConcurrentHashMap<>();

  @Override
  public void remove(String key) {
    this.queues.remove(key);
  }

  @Override
  public TaskQueue get(String key) {
    return queues.computeIfAbsent(
        key,
        x ->
            new LockableTaskQueue() {
              private final RLock rLock = RedisClient.client.getFairLock(key);

              @Override
              protected boolean tryLock(int executedCount) {
                try {
                  return rLock.tryLock(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  return false;
                }
              }

              @Override
              protected boolean shouldYield(int executedCount) {
                return executedCount > 0 && executedCount % 5 == 0;
              }

              @Override
              protected void unlock() {
                rLock.forceUnlock();
              }
            });
  }
}
