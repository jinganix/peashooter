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
import java.util.concurrent.Callable;

/**
 * {@link Callable} with task chain tracing.
 *
 * @param <V> return value
 */
public class TraceCallable<V> implements Callable<V> {

  /** {@link Tracer} */
  private final Tracer tracer;

  /** parent {@link Span} */
  private final Span parent;

  /** delegated {@link Callable} */
  private final Callable<V> delegate;

  /**
   * Constructor.
   *
   * @param tracer {@link Tracer}
   * @param delegate {@link Callable}
   */
  public TraceCallable(Tracer tracer, Callable<V> delegate) {
    this.tracer = tracer;
    this.parent = tracer.getSpan();
    this.delegate = delegate;
  }

  @Override
  public V call() throws Exception {
    Span span = new Span(tracer, this.parent);
    tracer.setSpan(span);
    tracer.beforeCall(span);
    try {
      V v = this.delegate.call();
      tracer.afterCall(span, null);
      return v;
    } catch (Exception e) {
      tracer.afterCall(span, e);
      throw e;
    }
  }
}
