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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("W3C context inject version")
class W3CContextInjectVersionTest {

  @Test
  @DisplayName("should inject from a 00 context preserving sampled")
  void shouldInjectFrom00Context() {
    W3CTraceContext.Context context =
        W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    Span span = Span.ofIds(context.traceId(), TraceIds.nextSpanId(), null);

    assertThat(W3CTraceContext.inject(context, span)).endsWith("-01");
  }

  @Test
  @DisplayName("should throw on non-00 context instead of silently downgrading")
  void shouldThrowOnNon00Context() {
    W3CTraceContext.Context context =
        W3CTraceContext.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    Span span =
        W3CTraceContext.extractParent("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    assertThatThrownBy(() -> W3CTraceContext.inject(context, span))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("01");
    assertThatThrownBy(() -> W3CTraceContext.inject(context, span, "rojo=00f067aa0ba902b7"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("01");
  }
}
