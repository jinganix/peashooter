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

import io.github.jinganix.peashooter.SpanAccessor;
import io.github.jinganix.peashooter.TraceCallback;
import io.github.jinganix.peashooter.TraceIdGenerator;
import io.github.jinganix.peashooter.Tracer;
import java.util.Objects;

/**
 * Composing {@link Tracer} that delegates each facet to the given collaborators.
 *
 * <p>No defaults: callers supply span storage, id generation, and callbacks explicitly, so a
 * missing facet fails at compile time instead of silently inheriting global ids or no-op callbacks.
 * Depend on the narrow {@link SpanAccessor}, {@link TraceIdGenerator}, or {@link TraceCallback}
 * directly when only one facet is needed.
 */
public class DelegatingTracer extends AbstractTracer {

  private final SpanAccessor spans;

  private final TraceIdGenerator ids;

  private final TraceCallback callbacks;

  /**
   * Full composition.
   *
   * @param spans span storage delegate
   * @param ids id generation delegate
   * @param callbacks callback delegate
   */
  public DelegatingTracer(SpanAccessor spans, TraceIdGenerator ids, TraceCallback callbacks) {
    this.spans = Objects.requireNonNull(spans, "spans");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
  }

  @Override
  public Span getSpan() {
    return spans.getSpan();
  }

  @Override
  public void setSpan(Span span) {
    spans.setSpan(span);
  }

  @Override
  public void clearSpan() {
    spans.clearSpan();
  }

  @Override
  public String nextTraceId() {
    return ids.nextTraceId();
  }

  @Override
  public String nextSpanId() {
    return ids.nextSpanId();
  }

  @Override
  public void beforeCall(Span span) {
    callbacks.beforeCall(span);
  }

  @Override
  public void afterCall(Span span, Throwable e) {
    callbacks.afterCall(span, e);
  }
}
