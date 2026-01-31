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

/** {@link Runnable} with task chain tracing. */
public class TraceRunnable implements Runnable {

  /** {@link Tracer} */
  protected final Tracer tracer;

  /** parent {@link Span} */
  protected final Span parent;

  /** delegated {@link Runnable} */
  protected final Runnable delegate;

  /**
   * Constructor.
   *
   * @param tracer {@link Tracer}
   * @param delegate {@link Runnable}
   */
  public TraceRunnable(Tracer tracer, Runnable delegate) {
    this.tracer = tracer;
    this.parent = tracer.getSpan();
    this.delegate = delegate;
  }

  /**
   * Create a new {@link Span}.
   *
   * @return {@link Span}
   */
  protected Span createSpan() {
    return new Span(tracer, this.parent);
  }

  @Override
  public void run() {
    Span span = createSpan();
    tracer.setSpan(span);
    tracer.beforeCall(span);
    try {
      this.delegate.run();
      tracer.afterCall(span, null);
    } catch (Exception e) {
      tracer.afterCall(span, e);
      throw e;
    }
  }
}
