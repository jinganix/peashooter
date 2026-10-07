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

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ErrorPolicy interrupt chain")
class ErrorPolicyInterruptChainTest {

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  @DisplayName("rethrowUnchecked restores interrupt when wrapped in CompletionException")
  void restoresInterruptWhenWrapped() {
    Thread.interrupted();
    CompletionException wrapped = new CompletionException(new InterruptedException("stop"));
    assertThatThrownBy(() -> ErrorPolicy.UNCHECKED.rethrowUnchecked(wrapped)).isSameAs(wrapped);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  @DisplayName("rethrowDelegate restores interrupt when wrapped in ExecutionException")
  void delegateRestoresInterruptWhenWrapped() throws Exception {
    Thread.interrupted();
    ExecutionException wrapped = new ExecutionException(new InterruptedException("stop"));
    try {
      ErrorPolicy.CALLABLE.rethrowDelegate(wrapped, null);
    } catch (ExecutionException e) {
      assertThat(e).isSameAs(wrapped);
    }
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  @DisplayName("no interrupt restored for ordinary failures")
  void noInterruptForOrdinaryFailures() {
    Thread.interrupted();
    IllegalStateException failure = new IllegalStateException("boom");
    assertThatThrownBy(() -> ErrorPolicy.UNCHECKED.rethrowUnchecked(failure)).isSameAs(failure);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }
}
