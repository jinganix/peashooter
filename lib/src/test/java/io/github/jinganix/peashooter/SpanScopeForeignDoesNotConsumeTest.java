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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SpanScope foreign close must not consume scope")
class SpanScopeForeignDoesNotConsumeTest {

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
  @DisplayName("foreign close throws but owner close still restores previous")
  void foreignCloseDoesNotConsumeScope() throws Exception {
    SimpleAccessor accessor = new SimpleAccessor();
    Span outer = Span.ofIds("11111111111111111111111111111111", "2222222222222222", null);
    Span inner = Span.ofIds("11111111111111111111111111111111", "3333333333333333", outer);
    accessor.setSpan(outer);
    SpanAccessor.Scope scope = accessor.scope(inner);
    assertThat(accessor.getSpan()).isSameAs(inner);

    AtomicReference<Throwable> foreignError = new AtomicReference<>();
    Thread foreign =
        new Thread(
            () -> {
              try {
                scope.close();
              } catch (Throwable e) {
                foreignError.set(e);
              }
            });
    foreign.start();
    foreign.join();

    // Given: foreign close must throw
    assertThat(foreignError.get()).isInstanceOf(IllegalStateException.class);
    // When: owner closes (must still restore, not no-op leak)
    scope.close();
    // Then: previous restored
    assertThat(accessor.getSpan()).isSameAs(outer);
  }
}
