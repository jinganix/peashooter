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

package io.github.jinganix.peashooter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jinganix.peashooter.trace.DefaultTracer;
import io.github.jinganix.peashooter.trace.Span;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SpanAccessor scope foreign thread")
class SpanAccessorScopeForeignThreadTest {

  @Test
  @DisplayName("should throw when closed from foreign thread")
  void shouldThrowWhenClosedFromForeignThread() throws Exception {
    // Given a scope opened on the main thread
    DefaultTracer tracer = new DefaultTracer();
    Span spanA = Span.ofIds("11111111111111111111111111111111", "2222222222222222", null);
    Span spanB = Span.ofIds("33333333333333333333333333333333", "4444444444444444", null);
    SpanAccessor.Scope scope = tracer.scope(spanA);
    try {
      assertThat(tracer.getSpan()).isSameAs(spanA);

      // When a foreign thread closes it
      AtomicReference<Throwable> error = new AtomicReference<>();
      AtomicReference<Span> childBefore = new AtomicReference<>();
      AtomicReference<Span> childAfter = new AtomicReference<>();
      Thread foreign =
          new Thread(
              () -> {
                tracer.setSpan(spanB);
                childBefore.set(tracer.getSpan());
                try {
                  scope.close();
                } catch (Throwable e) {
                  error.set(e);
                } finally {
                  childAfter.set(tracer.getSpan());
                }
              });
      foreign.start();
      foreign.join();

      // Then it must throw and corrupt neither thread's span
      assertThat(error.get()).isInstanceOf(IllegalStateException.class);
      assertThat(childBefore.get()).isSameAs(spanB);
      assertThat(childAfter.get()).isSameAs(spanB);
      assertThat(tracer.getSpan()).isSameAs(spanA);
    } finally {
      scope.close();
    }
  }
}
