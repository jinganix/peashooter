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

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TaskQueue} extended with an external lock acquired around each batch of task execution.
 *
 * <p>Inherits {@link TaskQueue}'s submission-failure and task-body policies.
 *
 * <p><b>Lock lifecycle:</b> subclasses implement {@link #tryLock}, {@link #shouldYield}, and {@link
 * #unlock}. The queue calls {@code tryLock} once per batch, runs tasks while holding the lock,
 * optionally {@code unlock}s and re-{@code tryLock}s when {@code shouldYield} returns {@code true},
 * then {@code unlock}s when the batch ends. If {@code tryLock} fails while tasks are pending, the
 * runner is rescheduled (on a background thread when the head task uses {@link DirectExecutor}, to
 * avoid synchronous re-entry).
 *
 * <p><b>Executor handoff:</b> when consecutive tasks use different {@link Executor}s, the external
 * lock stays held across <em>asynchronous</em> handoff until the new runner starts. Synchronous
 * handoff (e.g. {@link DirectExecutor}) uses nested {@link #run()} depth to pair lock/unlock
 * correctly.
 *
 * <p><b>Subclass contract:</b> {@code tryLock} may be invoked multiple times per outer loop after
 * a yield. {@code unlock} is skipped while {@code runDepth > 1}. Pair every successful lock with
 * exactly one {@code unlock} at the outermost runner depth. Blocking or spinning in {@code tryLock}
 * is implementation-defined.
 */
public abstract class LockableTaskQueue extends TaskQueue {

  private static final Logger log = LoggerFactory.getLogger(LockableTaskQueue.class);

  private static final AtomicInteger RESCHEDULE_THREADS_CREATED = new AtomicInteger();

  /** Avoid synchronous re-entry when rescheduling after a failed {@link #tryLock}. */
  private static final Executor RESCHEDULE_EXECUTOR =
      Executors.newSingleThreadExecutor(
          runnable -> {
            RESCHEDULE_THREADS_CREATED.incrementAndGet();
            Thread thread = new Thread(runnable, "lockable-task-queue-reschedule");
            thread.setDaemon(true);
            return thread;
          });

  /** Number of reschedule worker threads created; for tests. */
  static int rescheduleThreadsCreated() {
    return RESCHEDULE_THREADS_CREATED.get();
  }

  private boolean locked = false;

  /** Nested {@link #run()} depth on the current thread (e.g. {@code DirectExecutor} handoff). */
  private int runDepth = 0;

  private final ExecutionStats stats;

  /** Constructor. */
  public LockableTaskQueue() {
    super();
    this.stats = new ExecutionDummyStats();
  }

  /**
   * Creates a queue that uses the given stats for lock and yield decisions.
   *
   * @param stats execution statistics passed to {@link #tryLock} and {@link #shouldYield}; reset
   *     at the start of each outer loop
   */
  public LockableTaskQueue(ExecutionStats stats) {
    super();
    this.stats = stats;
  }

  @Override
  protected void run() {
    runDepth++;
    try {
      runOuter();
    } finally {
      runDepth--;
    }
  }

  private void runOuter() {
    outer:
    for (; ; ) {
      boolean keepLock = false;
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
              boolean[] runnerStarted = {false};
              Runnable handoffRunner =
                  () -> {
                    runnerStarted[0] = true;
                    runner.run();
                  };
              try {
                task.exec.execute(handoffRunner);
              } catch (RuntimeException e) {
                discardAllPendingTasks(e);
                return;
              }
              keepLock = !runnerStarted[0];
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
          synchronized (tasks) {
            if (tasks.isEmpty()) {
              current = null;
              return;
            }
            current = null;
            Task pending = tasks.peek();
            current = pending.exec;
            try {
              rescheduleRunner(pending.exec);
            } catch (RuntimeException e) {
              discardAllPendingTasks(e);
              return;
            }
          }
          return;
        }
      } finally {
        if (!keepLock) {
          cachedUnlock();
        }
      }
    }
  }

  private boolean cachedTryLock(ExecutionStats stats) {
    if (!this.locked) {
      this.locked = tryLock(stats);
    }
    return this.locked;
  }

  private void rescheduleRunner(Executor executor) {
    if (executor == DirectExecutor.INSTANCE) {
      RESCHEDULE_EXECUTOR.execute(runner);
    } else {
      executor.execute(runner);
    }
  }

  private void cachedUnlock() {
    if (!this.locked || runDepth > 1) {
      return;
    }
    unlock();
    this.locked = false;
  }

  /**
   * Attempts to acquire the external lock for the current batch.
   *
   * @param stats batch statistics; reset before the first call in each outer loop
   * @return {@code true} if the lock was acquired
   */
  protected abstract boolean tryLock(ExecutionStats stats);

  /**
   * Whether to end the current batch, {@link #unlock}, and start a new one.
   *
   * <p>Fewer yields mean more consecutive tasks per lock hold and fewer lock round-trips.
   *
   * @param stats batch statistics, updated after each executed task
   * @return {@code true} to yield (unlock and re-{@link #tryLock} in the next batch)
   */
  protected abstract boolean shouldYield(ExecutionStats stats);

  /** Releases the external lock acquired by {@link #tryLock}. */
  protected abstract void unlock();
}
