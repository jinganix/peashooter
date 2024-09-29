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

package io.github.jinganix.peashooter.queue;

import io.github.jinganix.peashooter.ExecutionStats;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ordered task queue that could be locked. */
public abstract class LockableTaskQueue extends TaskQueue {

  private static final Logger log = LoggerFactory.getLogger(LockableTaskQueue.class);

  private boolean locked = false;

  private final ExecutionStats stats;

  /** Constructor. */
  public LockableTaskQueue() {
    super();
    this.stats = new ExecutionDummyStats();
  }

  /**
   * Constructor
   *
   * @param stats {@link ExecutionCountStats}
   */
  public LockableTaskQueue(ExecutionStats stats) {
    super();
    this.stats = stats;
  }

  @Override
  protected void run() {
    for (; ; ) {
      try {
        stats.reset();
        while (cachedTryLock(stats)) {
          final Task task;
          synchronized (tasks) {
            task = tasks.poll();
            if (task == null) {
              current = null;
              return;
            }
            if (task.exec != current) {
              tasks.addFirst(task);
              current = task.exec;
              try {
                task.exec.execute(runner);
              } catch (RejectedExecutionException e) {
                // tasks will not be invoked unless execute method is called again
                current = null;
              }
              return;
            }
          }
          try {
            task.runnable.run();
          } catch (Throwable t) {
            log.error("Caught unexpected Throwable", t);
          }
          stats.record();
          if (shouldYield(stats)) {
            break;
          }
        }
        if (!this.locked) {
          return;
        }
      } finally {
        cachedUnlock();
      }
    }
  }

  private boolean cachedTryLock(ExecutionStats stats) {
    if (!this.locked) {
      this.locked = tryLock(stats);
    }
    return this.locked;
  }

  private void cachedUnlock() {
    if (this.locked) {
      unlock();
      this.locked = false;
    }
  }

  /**
   * Try lock.
   *
   * @param stats task {@link ExecutionStats} in this loop
   * @return true if locked
   */
  protected abstract boolean tryLock(ExecutionStats stats);

  /**
   * Should yield, it will unlock then reLock. Fewer yields allow tasks to execute more
   * consecutively. Reduce the number of lock acquisitions and improving performance.
   *
   * @param stats task {@link ExecutionStats} in this loop
   * @return true when should yield
   */
  protected abstract boolean shouldYield(ExecutionStats stats);

  /** Unlock. */
  protected abstract void unlock();
}
