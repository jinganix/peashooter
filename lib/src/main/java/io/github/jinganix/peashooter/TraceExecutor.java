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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executor with tracing. */
public class TraceExecutor implements ExecutorService {

  private final ExecutorService delegate;

  private final Tracer tracer;

  /**
   * Constructor.
   *
   * @param delegate delegated {@link ExecutorService}
   * @param tracer {@link Tracer}
   */
  public TraceExecutor(ExecutorService delegate, Tracer tracer) {
    this.delegate = delegate;
    this.tracer = tracer;
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
   * Get the {@link Span}.
   *
   * @return {@link Span}
   */
  public Span getSpan() {
    return tracer.getSpan();
  }

  @Override
  public void execute(Runnable runnable) {
    if (runnable instanceof TraceRunnable) {
      this.delegate.execute(runnable);
    } else {
      this.delegate.execute(new TraceRunnable(tracer, runnable));
    }
  }

  @Override
  public void shutdown() {
    this.delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return this.delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return this.delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return this.delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return this.delegate.awaitTermination(timeout, unit);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    if (task instanceof TraceCallable) {
      return this.delegate.submit(task);
    }
    return this.delegate.submit(new TraceCallable<>(tracer, task));
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    if (task instanceof TraceRunnable) {
      return this.delegate.submit(task, result);
    }
    return this.delegate.submit(new TraceRunnable(tracer, task), result);
  }

  @Override
  public Future<?> submit(Runnable task) {
    if (task instanceof TraceRunnable) {
      return this.delegate.submit(task);
    }
    return this.delegate.submit(new TraceRunnable(tracer, task));
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
      throws InterruptedException {
    return this.delegate.invokeAll(tasks);
  }

  @Override
  public <T> List<Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException {
    return this.delegate.invokeAll(tasks, timeout, unit);
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException {
    return this.delegate.invokeAny(tasks);
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return this.delegate.invokeAny(tasks, timeout, unit);
  }
}
