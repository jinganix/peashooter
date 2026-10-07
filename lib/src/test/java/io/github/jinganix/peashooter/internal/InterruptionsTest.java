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

package io.github.jinganix.peashooter.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Interruptions single owner")
class InterruptionsTest {

  @Test
  @DisplayName("detects direct, wrapped, and self-cyclic failures")
  void detectsChains() {
    assertThat(Interruptions.carriesInterrupt(new InterruptedException())).isTrue();
    assertThat(Interruptions.carriesInterrupt(new CompletionException(new InterruptedException())))
        .isTrue();
    assertThat(Interruptions.carriesInterrupt(new IllegalStateException("boom"))).isFalse();
    assertThat(Interruptions.carriesInterrupt(null)).isFalse();
    Throwable cyclic =
        new IllegalStateException("cyclic") {
          @Override
          public synchronized Throwable getCause() {
            return this;
          }
        };
    assertThat(Interruptions.carriesInterrupt(cyclic)).isFalse();
  }

  @Test
  @DisplayName("returns promptly on a two-node cause cycle instead of hanging")
  void returnsOnTwoNodeCycle() {
    // A <-> B cycles are constructible with plain initCause (only self-causation is
    // rejected), and the self-loop guard cannot see them: the walk must still terminate.
    Throwable first = new IllegalStateException("first");
    Throwable second = new IllegalStateException("second");
    first.initCause(second);
    second.initCause(first);
    assertThat(
            assertTimeoutPreemptively(
                Duration.ofSeconds(5), () -> Interruptions.carriesInterrupt(first)))
        .isFalse();
  }

  @Test
  @DisplayName("still detects an interruption buried in a cyclic chain")
  void detectsInterruptInCyclicChain() {
    Throwable first = new IllegalStateException("first");
    InterruptedException interrupt = new InterruptedException("interrupt");
    first.initCause(interrupt);
    interrupt.initCause(first);
    assertThat(
            assertTimeoutPreemptively(
                Duration.ofSeconds(5), () -> Interruptions.carriesInterrupt(first)))
        .isTrue();
  }
}
