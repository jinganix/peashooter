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

package io.github.jinganix.peashooter.trace;

import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.executor.RejectionAware;

/**
 * {@link TraceRunnable} that installs an {@link OrderedSpan} for per-key ordering and tracing.
 *
 * <p>The {@code sync} flag is stored on the span and drives {@link OrderedSpan#invokedBy(Span,
 * String)} for reentrant sync detection.
 */
public class OrderedTraceRunnable extends TraceRunnable implements RejectionAware {

  private final Span span;

  /**
   * Constructor.
   *
   * @param tracer {@link Tracer}
   * @param span {@link Span}
   * @param delegate {@link Runnable}
   */
  public OrderedTraceRunnable(Tracer tracer, Span span, Runnable delegate) {
    super(tracer, delegate);
    this.span = span;
  }

  /**
   * Constructor that builds an {@link OrderedSpan} from the ordering key and sync flag.
   *
   * @param tracer {@link Tracer}
   * @param key per-key ordering identifier
   * @param sync {@code true} for {@link
   *     io.github.jinganix.peashooter.executor.OrderedTraceExecutor} sync paths; {@code false} for
   *     async
   * @param delegate {@link Runnable}
   */
  public OrderedTraceRunnable(Tracer tracer, String key, boolean sync, Runnable delegate) {
    super(tracer, delegate);
    this.span = new OrderedSpan(this.tracer, this.parent, key, sync);
  }

  @Override
  public Span createSpan() {
    return this.span;
  }

  @Override
  public void rejected(RuntimeException cause) {
    if (delegate instanceof RejectionAware aware) {
      aware.rejected(cause);
    }
  }
}
