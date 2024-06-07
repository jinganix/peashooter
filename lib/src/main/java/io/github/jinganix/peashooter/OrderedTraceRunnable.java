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

/** Task with ordered key and sync flag. */
public class OrderedTraceRunnable extends TraceRunnable {

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
   * Constructor.
   *
   * @param tracer {@link Tracer}
   * @param key trace key
   * @param sync true if a sync call
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
}
