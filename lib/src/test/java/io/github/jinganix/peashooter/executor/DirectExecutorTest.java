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

package io.github.jinganix.peashooter.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DirectExecutor")
class DirectExecutorTest {

  @Test
  @DisplayName("should not retain depth on read alone")
  void shouldNotRetainDepthOnRead() throws Exception {
    java.util.concurrent.atomic.AtomicReference<Integer> depthValue =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<Integer> depthAfterTask =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread probe =
        new Thread(
            () -> {
              int d = DirectExecutor.depth();
              depthValue.set(d);
              DirectExecutor.INSTANCE.execute(() -> depthAfterTask.set(DirectExecutor.depth()));
            });
    probe.start();
    probe.join();
    assertThat(depthValue.get()).isZero();
    // A read leaves no residue: the nested task still observes depth 1, not a leaked level.
    assertThat(depthAfterTask.get()).isEqualTo(1);
    assertThat(DirectExecutor.depth()).isZero();
  }

  @Test
  @DisplayName("should run the task on the calling thread when execute is invoked")
  void shouldRunTheTaskOnTheCallingThreadWhenExecuteIsInvoked() {
    // Given
    Runnable runnable = mock(Runnable.class);

    // When
    DirectExecutor.INSTANCE.execute(runnable);

    // Then
    verify(runnable, times(1)).run();
  }

  @Test
  @DisplayName("should reject null task on execute")
  void shouldRejectNullTaskOnExecute() {
    assertThatThrownBy(() -> DirectExecutor.INSTANCE.execute(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("runnable");
  }
}
