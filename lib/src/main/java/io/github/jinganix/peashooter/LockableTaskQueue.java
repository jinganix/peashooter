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

package io.github.jinganix.peashooter;

/** Ordered task queue that could be locked. */
public abstract class LockableTaskQueue extends TaskQueue {

  /** Constructor. */
  public LockableTaskQueue() {
    super();
  }

  @Override
  protected void run() {
    int index = 0;
    while (tryLock(index)) {
      try {
        for (; ; index++) {
          if (super.runTask()) {
            return;
          }
          if (shouldYield(index)) {
            break;
          }
        }
      } finally {
        unlock();
      }
    }
  }

  /**
   * Try lock.
   *
   * @param index tasks iteration index in this loop
   * @return true if locked
   */
  protected abstract boolean tryLock(int index);

  /**
   * Should yield and reLock.
   *
   * @param index tasks iteration index in this loop
   * @return true when should yield
   */
  protected abstract boolean shouldYield(int index);

  /** Unlock. */
  protected abstract void unlock();
}
