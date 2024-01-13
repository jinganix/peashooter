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

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Execute tasks sequentially and trace call chain. */
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
   * Constructor.
   *
   * @param executor {@link Executor}
   */
  public OrderedTraceExecutor(Executor executor) {
    this.queues = new DefaultTaskQueueProvider();
    if (TraceExecutor.class.isAssignableFrom(executor.getClass())) {
      TraceExecutor traceExecutor = (TraceExecutor) executor;
      this.tracer = traceExecutor.getTracer();
      this.selector = new DefaultExecutorSelector(traceExecutor);
    } else {
      this.tracer = new DefaultTracer();
      TraceExecutor traceExecutor = new TraceExecutor(executor, this.tracer);
      this.selector = new DefaultExecutorSelector(traceExecutor);
    }
  }

  /**
   * Constructor.
   *
   * @param queues {@link TaskQueueProvider}
   * @param selector {@link ExecutorSelector}
   * @param tracer {@link Tracer}
   */
  public OrderedTraceExecutor(TaskQueueProvider queues, ExecutorSelector selector, Tracer tracer) {
    this.queues = queues;
    this.selector = selector;
    this.tracer = tracer;
  }

  /**
   * Set timeout config.
   *
   * @param timeout timeout value
   * @param timeUnit {@link TimeUnit} for timeout
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
   * Remove a queue by key.
   *
   * @param key key of the queue
   */
  public void remove(String key) {
    this.queues.remove(key);
  }

  /**
   * Run {@link Runnable} asynchronously ordered by multiple keys.
   *
   * @param keys keys to lock
   * @param task {@link Runnable}
   */
  public void executeAsync(Collection<String> keys, Runnable task) {
    for (String key : keys) {
      Runnable innerRunnable = task;
      task = () -> executeSync(key, innerRunnable);
    }
    task.run();
  }

  /**
   * Run {@link Runnable} asynchronously ordered by key.
   *
   * @param key key to lock
   * @param task {@link Runnable}
   */
  public void executeAsync(String key, Runnable task) {
    TraceRunnable runnable = new OrderedTraceRunnable(getTracer(), key, false, task);
    TaskQueue queue = queues.get(key);
    queue.execute(selector.getExecutor(queue, runnable, false), runnable);
  }

  /**
   * Run {@link Runnable} synchronously ordered by key.
   *
   * @param key key to lock
   * @param task {@link Runnable}
   */
  public void executeSync(String key, Runnable task) {
    try {
      if (OrderedSpan.invokedBy(getTracer().getSpan(), key)) {
        task.run();
        return;
      }
      CompletableFuture<Void> future = new CompletableFuture<>();
      TraceRunnable runnable =
          new OrderedTraceRunnable(
              getTracer(),
              key,
              true,
              () -> {
                try {
                  task.run();
                  future.complete(null);
                } catch (RuntimeException ex) {
                  future.completeExceptionally(ex);
                }
              });
      TaskQueue queue = queues.get(key);
      queue.execute(selector.getExecutor(queue, runnable, true), runnable);
      future.get(timeout, timeUnit);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      if (e instanceof ExecutionException) {
        throw (RuntimeException) e.getCause();
      }
      throw new RuntimeException(e);
    }
  }

  /**
   * Run {@link Runnable} synchronously ordered by multiple keys.
   *
   * @param keys keys to lock
   * @param task {@link Runnable}
   */
  public void executeSync(Collection<String> keys, Runnable task) {
    for (String key : keys) {
      Runnable innerRunnable = task;
      task = () -> executeSync(key, innerRunnable);
    }
    task.run();
  }

  /**
   * Get result of the {@link Supplier} ordered by key.
   *
   * @param key key to lock
   * @param supplier {@link Supplier}
   * @param <R> return type
   * @return result of the {@link Supplier}
   */
  public <R> R supply(String key, Supplier<R> supplier) {
    try {
      if (OrderedSpan.invokedBy(getTracer().getSpan(), key)) {
        return supplier.get();
      }
      CompletableFuture<R> future = new CompletableFuture<>();
      TraceRunnable runnable =
          new OrderedTraceRunnable(
              getTracer(),
              key,
              true,
              () -> {
                try {
                  future.complete(supplier.get());
                } catch (RuntimeException ex) {
                  future.completeExceptionally(ex);
                }
              });
      TaskQueue queue = queues.get(key);
      queue.execute(selector.getExecutor(queue, runnable, true), runnable);
      return future.get(timeout, timeUnit);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      if (e instanceof ExecutionException) {
        throw (RuntimeException) e.getCause();
      }
      throw new RuntimeException(e);
    }
  }

  /**
   * Get result of the {@link Supplier} ordered by multiple keys.
   *
   * @param keys keys to lock
   * @param supplier {@link Supplier}
   * @param <R> return type
   * @return result of the {@link Supplier}
   */
  public <R> R supply(Collection<String> keys, Supplier<R> supplier) {
    for (String key : keys) {
      Supplier<R> innerSupplier = supplier;
      supplier = () -> supply(key, innerSupplier);
    }
    return supplier.get();
  }
}
