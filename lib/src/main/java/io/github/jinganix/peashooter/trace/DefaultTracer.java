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

/** Default implementation for {@link Tracer}. */
public class DefaultTracer extends AbstractTracer {

  private final ThreadLocal<Span> spanHolder = new ThreadLocal<>();

  /** Constructor. */
  public DefaultTracer() {}

  @Override
  public Span getSpan() {
    return spanHolder.get();
  }

  @Override
  public void setSpan(Span span) {
    if (span == null) {
      spanHolder.remove();
    } else {
      spanHolder.set(span);
    }
  }

  @Override
  public void clearSpan() {
    spanHolder.remove();
  }

  @Override
  public String nextTraceId() {
    return TraceIds.nextTraceId();
  }

  @Override
  public String nextSpanId() {
    return TraceIds.nextSpanId();
  }

  @Override
  public void beforeCall(Span span) {}

  @Override
  public void afterCall(Span span, Throwable e) {}
}
