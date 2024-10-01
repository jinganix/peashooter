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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Call tasks in order. The {@link TaskQueue} locks when submitting tasks and is lock-free during
 * task execution. By having a few threads submit tasks while the majority execute them, it can
 * reduce lock contention.
 */
public class TaskQueue {

  private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);

  /** task list */
  protected final Deque<Task> tasks = new ArrayDeque<>();

  /** queue runner */
  protected final Runnable runner;

  /** current {@link Executor} */
  protected Executor current;

  /** Constructor. */
  public TaskQueue() {
    runner = this::run;
  }

  /** Run tasks. */
  protected void run() {
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
    } catch (RejectedExecutionException e) {
      synchronized (tasks) {
        tasks.remove(task);
        // tasks will not be invoked unless execute method is called again
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

  /** Task. */
  protected static class Task {

    /** {@link Runnable} */
    protected final Runnable runnable;

    /** {@link Executor} for this task */
    protected final Executor exec;

    /**
     * Constructor.
     *
     * @param runnable {@link Runnable}
     * @param exec {@link Executor}
     */
    public Task(Runnable runnable, Executor exec) {
      this.runnable = runnable;
      this.exec = exec;
    }
  }
}
