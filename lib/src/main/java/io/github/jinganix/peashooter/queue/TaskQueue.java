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

import io.github.jinganix.peashooter.executor.RejectionAware;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ordered task queue: one runner drains tasks strictly in submission order for this instance.
 *
 * <p>The {@code tasks} monitor is held only to enqueue, dequeue, and hand off between {@link
 * Executor}s; task bodies run outside the lock so submitters and executors contend minimally.
 *
 * <p><b>Executor submission failures (intentional, do not change casually):</b> when {@link
 * Executor#execute(Runnable)} throws any {@link RuntimeException} while scheduling the queue
 * runner, <em>all pending tasks</em> are discarded immediately (error log only; {@link #execute}
 * does not throw). Clearing the whole backlog preserves strict per-queue ordering — we never run
 * tasks enqueued after a scheduling gap. Callers that need at-least-once semantics must recover
 * themselves.
 *
 * <p><b>Task body failures:</b> any {@link Throwable} from a task {@link Runnable} is logged; the
 * queue continues with the next task. Exception propagation is the caller's responsibility (e.g.
 * {@link io.github.jinganix.peashooter.executor.OrderedTraceExecutor} wraps sync delegates in a
 * {@link java.util.concurrent.CompletableFuture}).
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

  /** Drains the queue until empty or an executor handoff schedules another runner. */
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
          } catch (RuntimeException e) {
            discardAllPendingTasks(e);
            return;
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
   * Enqueues {@code runnable} to run on {@code executor} when this queue reaches it.
   *
   * <p>Does not throw when runner scheduling fails; see class javadoc. At most one runner is active
   * per queue instance.
   *
   * @param executor schedules the queue runner for this task
   * @param runnable task body
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
      discardAllPendingTasks(e);
    }
  }

  /**
   * Whether the queue has no pending tasks and no active runner.
   *
   * <p>Returns {@code false} while a runner is scheduled ({@code current != null}) even if the
   * deque is momentarily empty, e.g. during executor handoff between consecutive tasks.
   *
   * @return {@code true} when no tasks are queued and no runner is in flight
   */
  public boolean isEmpty() {
    synchronized (tasks) {
      return tasks.isEmpty() && current == null;
    }
  }

  /**
   * Clears every pending task after an executor submission failure.
   *
   * <p>Does not stop a task already running on the current runner invocation.
   *
   * @param e rejection cause logged with the discard count
   */
  protected void discardAllPendingTasks(RuntimeException e) {
    final int discarded;
    final Runnable[] discardedRunnables;
    synchronized (tasks) {
      discarded = tasks.size();
      discardedRunnables = tasks.stream().map(task -> task.runnable).toArray(Runnable[]::new);
      tasks.clear();
      current = null;
    }
    log.error("Discarded {} pending task(s) due to rejected execution", discarded, e);
    for (Runnable runnable : discardedRunnables) {
      notifyDiscarded(runnable, e);
    }
  }

  /**
   * Notifies a discarded task runnable; overridden by integrations that complete sync waiters on
   * rejection.
   *
   * @param runnable task body that was discarded
   * @param e rejection cause passed to rejection-aware runnables
   */
  protected void notifyDiscarded(Runnable runnable, RuntimeException e) {
    if (runnable instanceof RejectionAware aware) {
      aware.rejected(e);
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
