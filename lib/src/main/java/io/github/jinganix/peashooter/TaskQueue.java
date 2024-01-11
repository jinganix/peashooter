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

import java.util.LinkedList;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Call tasks in order. */
public class TaskQueue {

  private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);

  private final LinkedList<Task> tasks = new LinkedList<>();

  private final Runnable runner;

  private Executor current;

  /** Constructor. */
  public TaskQueue() {
    runner = this::run;
  }

  private void run() {
    for (; ; ) {
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
          } catch (RuntimeException e) {
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
    }
  }

  /**
   * Execute.
   *
   * @param executor {@link Executor}
   * @param runnable {@link Runnable}
   */
  public void execute(Executor executor, Runnable runnable) {
    Task task = new Task(runnable, executor);
    synchronized (tasks) {
      tasks.add(task);
      if (current != null) {
        return;
      }
      current = executor;
    }
    try {
      executor.execute(runner);
    } catch (RuntimeException e) {
      synchronized (tasks) {
        tasks.remove(task);
        // TODO: tasks not empty
        current = null;
        throw e;
      }
    }
  }

  /**
   * Whether the task queue is empty.
   *
   * @return true if no task.
   */
  public boolean isEmpty() {
    synchronized (tasks) {
      return current == null;
    }
  }

  private static class Task {

    private final Runnable runnable;

    private final Executor exec;

    public Task(Runnable runnable, Executor exec) {
      this.runnable = runnable;
      this.exec = exec;
    }
  }
}
