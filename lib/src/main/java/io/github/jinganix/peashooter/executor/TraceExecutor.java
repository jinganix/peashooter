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

import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.trace.TraceRunnable;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Executor with tracing. */
public class TraceExecutor implements Executor {

  private final Executor delegate;

  private final Tracer tracer;

  /**
   * Constructor.
   *
   * @param delegate delegated {@link Executor}
   * @param tracer {@link Tracer}
   */
  public TraceExecutor(Executor delegate, Tracer tracer) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.tracer = Objects.requireNonNull(tracer, "tracer");
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
   * Returns the backing {@link Executor} for lifecycle and sizing introspection.
   *
   * @return backing executor
   */
  public Executor getDelegate() {
    return delegate;
  }

  /**
   * Executes with tracing. Direct use propagates delegate rejections to the caller per {@link
   * Executor} contract; when routed through {@link io.github.jinganix.peashooter.queue.TaskQueue},
   * scheduling failures reject only the triggering submission (propagated to its submitter) while
   * the remaining backlog is preserved.
   */
  @Override
  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable");
    // TraceRunnable already implements RejectionAware and forwards discards to its delegate,
    // so a single branch preserves rejection signals without a redundant wrapper subtype.
    if (runnable instanceof TraceRunnable) {
      this.delegate.execute(runnable);
    } else {
      this.delegate.execute(new TraceRunnable(tracer, runnable));
    }
  }
}
