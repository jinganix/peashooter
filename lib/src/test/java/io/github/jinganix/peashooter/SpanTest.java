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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

@DisplayName("Span")
class SpanTest {

  @Nested
  @DisplayName("constructor")
  class Constructor {

    @Nested
    @DisplayName("when parent is null")
    class WhenParentIsNull {

      @Test
      @DisplayName("then gen new trace id")
      void thenGenNewTraceId() {
        try (MockedStatic<UUID> uuid = mockStatic(UUID.class)) {
          uuid.when(UUID::randomUUID).thenReturn(new UUID(0, 0));
          Span span = new Span(new DefaultTracer(), null);
          assertThat(span.getParent()).isNull();
          assertThat(span.getTraceId()).matches("\\w+-\\w+-\\w+-\\w+-\\w+");
        }
      }
    }

    @Nested
    @DisplayName("when parent is not null")
    class WhenParentIsNotNull {

      @Test
      @DisplayName("then inherit the trace id")
      void thenInheritTheTraceId() {
        Span parent = new Span(new DefaultTracer(), null);
        Span span = new Span(new DefaultTracer(), parent);
        assertThat(span.getParent()).isEqualTo(parent);
        assertThat(span.getTraceId()).isEqualTo(parent.getTraceId());
      }
    }
  }

  @Nested
  @DisplayName("isRoot")
  class IsRoot {

    @Nested
    @DisplayName("when parent is null")
    class WhenParentIsNull {

      @Test
      @DisplayName("then return true")
      void thenReturnTrue() {
        Span span = new Span(new DefaultTracer(), null);
        assertThat(span.isRoot()).isTrue();
      }
    }

    @Nested
    @DisplayName("when parent is not null")
    class WhenParentIsNotNull {

      @Test
      @DisplayName("then return false")
      void thenReturnFalse() {
        TraceIdGenerator traceIdGenerator = new DefaultTracer();
        Span span = new Span(traceIdGenerator, new Span(traceIdGenerator, null));
        assertThat(span.isRoot()).isFalse();
      }
    }
  }
}
