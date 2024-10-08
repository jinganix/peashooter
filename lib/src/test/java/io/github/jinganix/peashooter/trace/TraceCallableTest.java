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

package io.github.jinganix.peashooter.trace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TraceCallable")
class TraceCallableTest {

  @Nested
  @DisplayName("call")
  class Call {

    @Nested
    @DisplayName("when delegate has no error")
    class WhenDelegateHasNoError {

      @Test
      @DisplayName("then no exception")
      void thenNoException() {
        TraceCallable<Integer> traceCallable = new TraceCallable<>(new DefaultTracer(), () -> 0);
        assertThatCode(traceCallable::call).doesNotThrowAnyException();
      }
    }

    @Nested
    @DisplayName("when delegate has error")
    class WhenDelegateHasError {

      @Test
      @DisplayName("then throw exception")
      void thenThrowException() {
        RuntimeException exception = new RuntimeException();
        TraceCallable<Integer> traceCallable =
            new TraceCallable<>(
                new DefaultTracer(),
                () -> {
                  throw exception;
                });
        assertThatThrownBy(traceCallable::call).isEqualTo(exception);
      }
    }
  }
}
