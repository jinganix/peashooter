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

import io.github.jinganix.peashooter.trace.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SpanAccessor scope idempotence")
class SpanAccessorScopeIdempotenceTest {

  static final class SimpleAccessor implements SpanAccessor {
    Span current;

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
  }

  @Test
  @DisplayName("double close keeps the restored span instead of clobbering it")
  void doubleCloseIsNoOp() {
    SimpleAccessor accessor = new SimpleAccessor();
    Span outer = Span.ofIds("11111111111111111111111111111111", "2222222222222222", null);
    Span inner = Span.ofIds("11111111111111111111111111111111", "3333333333333333", outer);
    accessor.setSpan(outer);
    SpanAccessor.Scope scope = accessor.scope(inner);
    assertThat(accessor.getSpan()).isSameAs(inner);
    scope.close();
    assertThat(accessor.getSpan()).isSameAs(outer);
    accessor.setSpan(inner);
    scope.close();
    assertThat(accessor.getSpan()).isSameAs(inner);
  }
}
