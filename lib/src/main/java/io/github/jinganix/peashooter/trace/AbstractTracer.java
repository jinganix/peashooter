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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skeletal {@link Tracer} for span storage plus the lenient fallback count.
 *
 * <p>No id-generation or callback defaults: subclasses implement {@link #nextTraceId}, {@link
 * #nextSpanId}, {@link #beforeCall}, and {@link #afterCall} explicitly so missing bridges fail at
 * compile time instead of silently inheriting global ids or no-op callbacks.
 *
 * <p>Also owns the per-tracer lenient untraced fallback count: incremented once per degraded run
 * where an {@link Exception} from tracer storage, span factory, or install forces the delegate to
 * run without tracing. Without this, lenient fallback only logs and silently breaks the span chain,
 * masking tracer bugs. Poll {@link #getTracerFallbackCount} for alerting. {@link Error}s stay loud
 * and never count. Per instance, so one misbehaving tracer never inflates another's alert signal.
 */
public abstract class AbstractTracer implements Tracer {

  /** Constructor. */
  public AbstractTracer() {}

  private final AtomicLong tracerFallbackCount = new AtomicLong();

  @Override
  public abstract Span getSpan();

  @Override
  public abstract void setSpan(Span span);

  @Override
  public abstract void clearSpan();

  @Override
  public abstract String nextTraceId();

  @Override
  public abstract String nextSpanId();

  @Override
  public abstract void beforeCall(Span span);

  @Override
  public abstract void afterCall(Span span, Throwable e);

  /**
   * Records one lenient untraced fallback against this tracer.
   *
   * <p>Called by {@code TraceScope} (same package) when this tracer's storage, span factory, or
   * install forces an untraced delegate run. Package-private: this mutates the alerting signal and
   * must not be reachable from consumer code. Saturated atomic increment: concurrent degraded runs
   * never lose an increment; the count pins at {@link Long#MAX_VALUE} instead of wrapping.
   */
  final void recordTracerFallback() {
    // Zero-allocation saturating increment: hand-rolled get/CAS loop instead of
    // updateAndGet (which allocates its lambda on every degraded run).
    long current;
    do {
      current = tracerFallbackCount.get();
      if (current >= Long.MAX_VALUE) {
        return;
      }
    } while (!tracerFallbackCount.compareAndSet(current, current + 1));
  }

  /**
   * Returns the number of lenient untraced fallbacks observed by this tracer.
   *
   * @return tracer fallback count for alerting
   */
  public final long getTracerFallbackCount() {
    return tracerFallbackCount.get();
  }
}
