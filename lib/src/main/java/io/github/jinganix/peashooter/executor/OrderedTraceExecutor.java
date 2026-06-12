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

package io.github.jinganix.peashooter.executor;

import io.github.jinganix.peashooter.ExecutorSelector;
import io.github.jinganix.peashooter.TaskQueueProvider;
import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.queue.TaskQueue;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import io.github.jinganix.peashooter.trace.OrderedSpan;
import io.github.jinganix.peashooter.trace.OrderedTraceRunnable;
import io.github.jinganix.peashooter.trace.TraceRunnable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Executes tasks in strict per-key order and records an ordered trace chain.
 *
 * <p><b>Per-key ordering:</b> each key maps to a {@link TaskQueue} from the configured {@link
 * TaskQueueProvider}. Tasks submitted for the same key run one at a time; different keys may run
 * concurrently.
 *
 * <p><b>Reentrant sync (queue bypass):</b> {@link #executeSync(String, Runnable)} and {@link
 * #supply(String, Supplier)} call {@link OrderedSpan#invokedBy(Span, String)} before enqueueing.
 * When the current thread is already running a <em>sync</em> span for the same key, the new work
 * executes inline on the current thread instead of joining the per-key queue. That avoids nested
 * sync deadlocks, but other threads blocked on the same key may be overtaken — cross-thread FIFO is
 * not guaranteed in this case. {@link #executeAsync} always enqueues and never uses this fast path.
 *
 * <p><b>Executor submission failures:</b> when the backing {@link Executor} throws while
 * scheduling the queue runner, all pending tasks for that key are discarded (error log only). Sync
 * callers block until {@link #setTimeout(long, TimeUnit) timeout}; the symptom matches a slow task,
 * not a rejection. Async callers get no error. Callers that need at-least-once semantics must
 * retry themselves.
 *
 * <p><b>Task failures:</b> sync paths propagate exceptions from the delegate via {@code
 * CompletableFuture}. Async paths rely on {@link TaskQueue}, which logs and continues.
 *
 * <p>The single-argument constructor uses {@link CaffeineTaskQueueProvider} (5-minute idle eviction
 * per key). Use the three-argument constructor for custom queues, e.g. distributed {@link
 * io.github.jinganix.peashooter.queue.LockableTaskQueue} instances.
 */
public class OrderedTraceExecutor {

  /** {@link TaskQueueProvider} */
  protected final TaskQueueProvider queues;

  /** {@link ExecutorSelector} */
  protected final ExecutorSelector selector;

  /** {@link Tracer} */
  protected final Tracer tracer;

  /** Sync call timeout */
  protected long timeout = 10;

  /** {@link TimeUnit} for timeout */
  protected TimeUnit timeUnit = TimeUnit.SECONDS;

  /**
   * Creates an executor with a default {@link CaffeineTaskQueueProvider} and wraps {@code executor}
   * in a {@link TraceExecutor} when it is not already one.
   *
   * @param executor backing thread pool (or other {@link Executor}) for queued work
   */
  public OrderedTraceExecutor(Executor executor) {
    this.queues = new CaffeineTaskQueueProvider();
    if (executor instanceof TraceExecutor traceExecutor) {
      this.tracer = traceExecutor.getTracer();
      this.selector = new DefaultExecutorSelector(traceExecutor);
    } else {
      this.tracer = new DefaultTracer();
      TraceExecutor traceExecutor = new TraceExecutor(executor, this.tracer);
      this.selector = new DefaultExecutorSelector(traceExecutor);
    }
  }

  /**
   * Creates an executor with explicit queue, executor-selection, and tracing components.
   *
   * @param queues per-key {@link TaskQueue} source
   * @param selector chooses the {@link Executor} for each submission
   * @param tracer records the ordered call chain
   */
  public OrderedTraceExecutor(TaskQueueProvider queues, ExecutorSelector selector, Tracer tracer) {
    this.queues = queues;
    this.selector = selector;
    this.tracer = tracer;
  }

  /**
   * Sets the maximum wait for {@link #executeSync(String, Runnable)} and {@link #supply(String,
   * Supplier)}. Each sync call reads the current values; there is no happens-before between
   * concurrent {@code setTimeout} and in-flight sync calls.
   *
   * @param timeout maximum wait
   * @param timeUnit unit for {@code timeout}
   */
  public void setTimeout(long timeout, TimeUnit timeUnit) {
    this.timeout = timeout;
    this.timeUnit = timeUnit;
  }

  /**
   * Get the {@link Tracer}.
   *
   * @return {@link Tracer}
   */
  public Tracer getTracer() {
    return tracer;
  }

  /**
   * Enqueues a {@link Runnable} for ordered, asynchronous execution under {@code key}.
   *
   * <p>Always goes through the per-key queue — there is no reentrant fast path (contrast {@link
   * #executeSync(String, Runnable)}). Task failures are logged by {@link TaskQueue}; they are not
   * propagated to the caller. Executor submission failures discard all pending tasks for the key
   * (log only).
   *
   * @param key ordering key; must not be {@code null}
   * @param task work to run; must not be {@code null}
   */
  public void executeAsync(String key, Runnable task) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(task, "task");
    TraceRunnable runnable = new OrderedTraceRunnable(getTracer(), key, false, task);
    TaskQueue queue = queues.get(key);
    queue.execute(selector.getExecutor(queue, runnable, false), runnable);
  }

  /**
   * Runs a {@link Runnable} under {@code key}, blocking until it finishes or times out.
   *
   * <p><b>Reentrant sync:</b> if {@link OrderedSpan#invokedBy(Span, String)} is {@code true} for
   * the current span chain and {@code key}, {@code task} runs immediately on the calling thread
   * without enqueueing. Other threads waiting on the same key are not consulted — nested same-key
   * sync can overtake them. See class javadoc.
   *
   * <p>Waits up to {@link #setTimeout(long, TimeUnit)}. A timeout only unblocks the caller; it
   * does not cancel work already queued or running. Executor submission failure discards pending
   * tasks and surfaces as timeout, not {@link java.util.concurrent.RejectedExecutionException}.
   * Delegate failures propagate as {@link RuntimeException} (or the original unchecked type).
   *
   * @param key ordering key; must not be {@code null}
   * @param task work to run; must not be {@code null}
   */
  public void executeSync(String key, Runnable task) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(task, "task");
    try {
      if (OrderedSpan.invokedBy(getTracer().getSpan(), key)) {
        runReentrantSync(key, task);
        return;
      }
      CompletableFuture<Void> future = new CompletableFuture<>();
      TraceRunnable runnable =
          new OrderedTraceRunnable(getTracer(), key, true, new SyncCallback(future, task));
      TaskQueue queue = queues.get(key);
      queue.execute(selector.getExecutor(queue, runnable, true), runnable);
      future.get(timeout, timeUnit);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw syncException(e);
    }
  }

  /**
   * Runs a {@link Runnable} while holding each key in turn via nested {@link #executeSync(String,
   * Runnable)} calls.
   *
   * <p>Keys are sorted in natural {@link String} order and de-duplicated before acquisition, so
   * concurrent callers using opposite key orders cannot deadlock. Keys are acquired sequentially,
   * not atomically — work on a later key may run before every key is held. {@code keys} must not be
   * empty ({@link IllegalArgumentException}).
   *
   * @param keys ordering keys; no element may be {@code null}
   * @param task work to run; must not be {@code null}
   */
  public void executeSync(Collection<String> keys, Runnable task) {
    Objects.requireNonNull(keys, "keys");
    Objects.requireNonNull(task, "task");
    for (String key : lockKeys(keys)) {
      Runnable finalTask = task;
      task = () -> executeSync(key, finalTask);
    }
    task.run();
  }

  /**
   * Runs a {@link Supplier} under {@code key}, blocking until a result is available or times out.
   *
   * <p>Reentrant sync, timeout, submission-failure, and exception semantics match {@link
   * #executeSync(String, Runnable)}.
   *
   * @param key ordering key; must not be {@code null}
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @return supplier result
   */
  public <R> R supply(String key, Supplier<R> supplier) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(supplier, "supplier");
    try {
      if (OrderedSpan.invokedBy(getTracer().getSpan(), key)) {
        return runReentrantSync(key, supplier);
      }
      CompletableFuture<R> future = new CompletableFuture<>();
      TraceRunnable runnable =
          new OrderedTraceRunnable(
              getTracer(), key, true, new SyncSupplierCallback<>(future, supplier));
      TaskQueue queue = queues.get(key);
      queue.execute(selector.getExecutor(queue, runnable, true), runnable);
      return future.get(timeout, timeUnit);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw syncException(e);
    }
  }

  /**
   * Runs a {@link Supplier} while holding each key in turn via nested {@link #supply(String,
   * Supplier)} calls.
   *
   * <p>Key ordering, de-duplication, and sequential (non-atomic) acquisition match {@link
   * #executeSync(java.util.Collection, Runnable)}. {@code keys} must not be empty.
   *
   * @param keys ordering keys; no element may be {@code null}
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @return supplier result
   */
  public <R> R supply(Collection<String> keys, Supplier<R> supplier) {
    Objects.requireNonNull(keys, "keys");
    Objects.requireNonNull(supplier, "supplier");
    for (String key : lockKeys(keys)) {
      Supplier<R> finalSupplier = supplier;
      supplier = () -> supply(key, finalSupplier);
    }
    return supplier.get();
  }

  private void runReentrantSync(String key, Runnable task) {
    new OrderedTraceRunnable(getTracer(), key, true, task).run();
  }

  private <R> R runReentrantSync(String key, Supplier<R> supplier) {
    AtomicReference<R> result = new AtomicReference<>();
    new OrderedTraceRunnable(getTracer(), key, true, () -> result.set(supplier.get())).run();
    return result.get();
  }

  private static Collection<String> lockKeys(Collection<String> keys) {
    if (keys.isEmpty()) {
      throw new IllegalArgumentException("keys must not be empty");
    }
    TreeSet<String> ordered = new TreeSet<>();
    for (String key : keys) {
      ordered.add(Objects.requireNonNull(key, "key"));
    }
    return new ArrayList<>(ordered);
  }

  private static final class SyncCallback implements Runnable, RejectionAware {

    private final CompletableFuture<Void> future;

    private final Runnable task;

    private SyncCallback(CompletableFuture<Void> future, Runnable task) {
      this.future = future;
      this.task = task;
    }

    @Override
    public void run() {
      try {
        task.run();
        future.complete(null);
      } catch (Throwable ex) {
        future.completeExceptionally(ex);
      }
    }

    @Override
    public void rejected(RuntimeException cause) {
      future.completeExceptionally(cause);
    }
  }

  private static final class SyncSupplierCallback<R> implements Runnable, RejectionAware {

    private final CompletableFuture<R> future;

    private final Supplier<R> supplier;

    private SyncSupplierCallback(CompletableFuture<R> future, Supplier<R> supplier) {
      this.future = future;
      this.supplier = supplier;
    }

    @Override
    public void run() {
      try {
        future.complete(supplier.get());
      } catch (Throwable ex) {
        future.completeExceptionally(ex);
      }
    }

    @Override
    public void rejected(RuntimeException cause) {
      future.completeExceptionally(cause);
    }
  }

  private static RuntimeException syncException(Exception e) {
    if (e instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      return new RuntimeException(e);
    }
    if (e instanceof ExecutionException) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        return (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      return new RuntimeException(cause);
    }
    return new RuntimeException(e);
  }
}
