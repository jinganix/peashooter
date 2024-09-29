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

package io.github.jinganix.peashooter.executor;

import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.trace.Span;
import io.github.jinganix.peashooter.trace.TraceRunnable;
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
}
