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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import io.github.jinganix.peashooter.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Tracer adapters")
class TracerAdaptersTest {

  static final class SpanStorageOnly extends AbstractTracer {
    private Span current;

    @Override
    public Span getSpan() {
      return current;
    }

    @Override
    public void setSpan(Span span) {
      current = span;
    }

    @Override
    public void clearSpan() {
      current = null;
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

  @Test
  @DisplayName("should store spans with explicit id and callback bridges")
  void shouldStoreSpansWithExplicitIdAndCallbackBridges() {
    // Given a Tracer that only bridges span storage (no ID/callback stubs)
    Tracer tracer = new SpanStorageOnly();

    // When span storage is used plus default ID generation and callbacks
    Span span = Span.ofIds("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", null);
    tracer.setSpan(span);
    Span observed = tracer.getSpan();
    String traceId = tracer.nextTraceId();
    String spanId = tracer.nextSpanId();
    tracer.beforeCall(observed);
    tracer.afterCall(observed, null);

    // Then storage works and defaults are usable without stubs
    assertThat(observed).isSameAs(span);
    assertThat(traceId).matches("[0-9a-f]{32}");
    assertThat(spanId).matches("[0-9a-f]{16}");
    assertThat(tracer.getSpan()).isSameAs(span);
    tracer.clearSpan();
    assertThat(tracer.getSpan()).isNull();
  }

  @Test
  @DisplayName("should delegate storage and ids through explicit collaborators")
  void shouldDelegateStorageAndIdsThroughExplicitCollaborators() {
    // Given a bare SpanAccessor with no ID/callback implementations
    io.github.jinganix.peashooter.SpanAccessor storage = new DefaultTracer();
    storage.clearSpan();

    // When wrapped with the delegating adapter (explicit storage, ids, callbacks)
    DefaultTracer ids = new DefaultTracer();
    io.github.jinganix.peashooter.TraceCallback callbacks =
        new io.github.jinganix.peashooter.TraceCallback() {
          @Override
          public void beforeCall(Span span) {}

          @Override
          public void afterCall(Span span, Throwable e) {}
        };
    Tracer tracer = new DelegatingTracer(storage, ids, callbacks);
    Span span = Span.ofIds("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", null);
    tracer.setSpan(span);

    // Then span storage is bridged and defaults cover IDs/callbacks
    assertThat(tracer.getSpan()).isSameAs(span);
    assertThat(tracer.nextTraceId()).matches("[0-9a-f]{32}");
    assertThat(tracer.nextSpanId()).matches("[0-9a-f]{16}");
    tracer.beforeCall(span);
    tracer.afterCall(span, new RuntimeException("boom"));
    assertThat(tracer.getSpan()).isSameAs(span);
    tracer.clearSpan();
    assertThat(tracer.getSpan()).isNull();
    assertThat(storage.getSpan()).isNull();
  }
}
