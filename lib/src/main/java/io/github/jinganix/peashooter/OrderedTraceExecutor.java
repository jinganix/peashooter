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

  /** {@link TaskQueues} */
  protected final TaskQueues queues;

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
    this.queues = new DefaultTaskQueues();
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
   * @param queues {@link TaskQueues}
   * @param selector {@link ExecutorSelector}
   * @param tracer {@link Tracer}
   */
  public OrderedTraceExecutor(TaskQueues queues, ExecutorSelector selector, Tracer tracer) {
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
   * Execute asynchronously.
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
   * Execute synchronously.
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
   * Lock the key and return result of the {@link Supplier}
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
   * Lock keys and return result of the {@link Supplier}
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
