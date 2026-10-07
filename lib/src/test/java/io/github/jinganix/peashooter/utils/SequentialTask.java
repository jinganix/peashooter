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

package io.github.jinganix.peashooter.utils;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class SequentialTask implements Runnable {

  private final AtomicReference<Thread> guard;

  private final Runnable delegate;

  private volatile String key;

  public SequentialTask(AtomicReference<Thread> guard, Runnable delegate) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /** Attaches key context for failure diagnostics; may be called once before use. */
  public SequentialTask withKey(String key) {
    this.key = key;
    return this;
  }

  @Override
  public void run() {
    Thread current = Thread.currentThread();
    if (!guard.compareAndSet(null, current)) {
      Thread holding = guard.get();
      throw new RuntimeException(
          "Task is running concurrently"
              + (key != null ? " for key '" + key + "'" : "")
              + " on thread '"
              + current.getName()
              + "', owned by '"
              + (holding != null ? holding.getName() : "unknown")
              + "'");
    }
    try {
      this.delegate.run();
    } finally {
      if (!guard.compareAndSet(current, null)) {
        // Unreachable when the delegate runs inline on this thread: the guard still holds
        // us. Reset loudly instead of clobbering another holder or leaking the guard.
        guard.set(null);
        throw new IllegalStateException(
            "SequentialTask guard corrupted on thread '"
                + current.getName()
                + "', expected owner '"
                + current.getName()
                + "'");
      }
    }
  }
}
