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
import java.util.function.Supplier;

/** Execute tasks sequentially and trace call chain. */
public class OrderedTraceExecutor {

  private final TraceExecutor executor;

  private final TaskQueues queues;

  /**
   * Constructor.
   *
   * @param executor {@link TraceExecutor}
   * @param queues {@link TaskQueues}
   */
  public OrderedTraceExecutor(TraceExecutor executor, TaskQueues queues) {
    this.executor = executor;
    this.queues = queues;
  }

  /**
   * Get the {@link Tracer}.
   *
   * @return {@link Tracer}
   */
  public Tracer getTracer() {
    return executor.getTracer();
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
    TraceRunnable runnable = new OrderedTraceRunnable(executor.getTracer(), key, false, task);
    TaskQueue queue = queues.get(key);
    queue.execute(executor, runnable);
  }

  /**
   * Execute synchronously.
   *
   * @param key key to lock
   * @param runnable {@link Runnable}
   */
  public void executeSync(String key, Runnable runnable) {
    try {
      if (OrderedSpan.invokedBy(executor.getSpan(), key)) {
        runnable.run();
        return;
      }
      CompletableFuture<Void> future = new CompletableFuture<>();
      TraceRunnable traceRunnable =
          new OrderedTraceRunnable(
              executor.getTracer(),
              key,
              true,
              () -> {
                try {
                  runnable.run();
                  future.complete(null);
                } catch (RuntimeException ex) {
                  future.completeExceptionally(ex);
                }
              });
      TaskQueue queue = queues.get(key);
      queue.execute(queue.isEmpty() ? DirectExecutor.INSTANCE : executor, traceRunnable);
      // TODO: timeout
      future.get();
    } catch (InterruptedException | ExecutionException e) {
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
   * @return result of the {@link Supplier}
   * @param <R> return type
   */
  public <R> R supply(String key, Supplier<R> supplier) {
    try {
      if (OrderedSpan.invokedBy(executor.getSpan(), key)) {
        return supplier.get();
      }
      CompletableFuture<R> future = new CompletableFuture<>();
      TraceRunnable runnable =
          new OrderedTraceRunnable(
              executor.getTracer(),
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
      // TODO: DirectExecutor only when same executor
      queue.execute(queue.isEmpty() ? DirectExecutor.INSTANCE : executor, runnable);
      return future.get();
    } catch (InterruptedException | ExecutionException e) {
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
   * @return result of the {@link Supplier}
   * @param <R> return type
   */
  public <R> R supply(Collection<String> keys, Supplier<R> supplier) {
    for (String key : keys) {
      Supplier<R> innerSupplier = supplier;
      supplier = () -> supply(key, innerSupplier);
    }
    return supplier.get();
  }
}
