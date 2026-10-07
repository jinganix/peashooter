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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceFallbackCount")
class TraceFallbackCountTest {

  @Test
  @DisplayName("should count lenient fallbacks on the owning tracer only")
  void shouldCountLenientFallbacksOnTheOwningTracerOnly() {
    // Given a tracer whose install always fails with an Exception (tracer bug)
    DefaultTracer backing = new DefaultTracer();
    Span previous = Span.child(backing, null);
    backing.setSpan(previous);
    java.util.concurrent.atomic.AtomicBoolean failNext =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (failNext.getAndSet(false)) {
              super.setSpan(span);
              throw new RuntimeException("install boom");
            }
            super.setSpan(span);
          }

          @Override
          public Span getSpan() {
            return backing.getSpan();
          }

          @Override
          public void clearSpan() {
            backing.clearSpan();
          }
        };
    DefaultTracer untouched = new DefaultTracer();
    AtomicBoolean delegateRan = new AtomicBoolean();

    // When lenient install degrades to an untraced run
    TraceScope.runLenient(tracer, () -> Span.child(tracer, null), () -> delegateRan.set(true));

    // Then the delegate still runs, and only the owning tracer observes the fallback
    assertThat(delegateRan.get()).isTrue();
    assertThat(tracer.getTracerFallbackCount()).isEqualTo(1);
    assertThat(untouched.getTracerFallbackCount()).isEqualTo(0);
    backing.clearSpan();
  }

  @Test
  @DisplayName("should count fallback on the owning tracer when span creation fails")
  void shouldCountFallbackOnTheOwningTracerWhenSpanCreationFails() {
    // Given a TraceRunnable whose span creation fails with an Exception
    DefaultTracer tracer = new DefaultTracer();
    DefaultTracer untouched = new DefaultTracer();
    AtomicBoolean delegateRan = new AtomicBoolean();
    TraceRunnable runnable =
        new TraceRunnable(tracer, () -> delegateRan.set(true)) {
          @Override
          protected Span createSpan() {
            throw new RuntimeException("span boom");
          }
        };

    // When it degrades to an untraced run
    runnable.run();

    // Then the fallback is counted on the owning tracer instead of silently running untraced
    assertThat(delegateRan.get()).isTrue();
    assertThat(tracer.getTracerFallbackCount()).isEqualTo(1);
    assertThat(untouched.getTracerFallbackCount()).isEqualTo(0);
    tracer.clearSpan();
  }
}
