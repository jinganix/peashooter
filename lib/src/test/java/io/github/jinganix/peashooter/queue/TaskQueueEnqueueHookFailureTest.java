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

package io.github.jinganix.peashooter.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue enqueue hook failure")
class TaskQueueEnqueueHookFailureTest {

  static final class FailingHookQueue extends TaskQueue {
    final AtomicBoolean failedHookCalled = new AtomicBoolean();

    @Override
    protected boolean onEnqueueLocked() {
      throw new IllegalStateException("hook boom");
    }

    @Override
    protected void onEnqueueFailed() {
      failedHookCalled.set(true);
    }
  }

  @Test
  @DisplayName("throwing onEnqueueLocked removes trigger, reconciles, and stays idle")
  void reconcilesOnHookFailure() {
    FailingHookQueue queue = new FailingHookQueue();
    assertThatThrownBy(() -> queue.execute(DirectExecutor.INSTANCE, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hook boom");
    assertThat(queue.isIdle()).isTrue();
    assertThat(queue.hasPending()).isFalse();
    assertThat(failing(queue)).isTrue();
  }

  static final class FailingErrorHookQueue extends TaskQueue {
    final AtomicBoolean failedHookCalled = new AtomicBoolean();

    @Override
    protected boolean onEnqueueLocked() {
      throw new AssertionError("hook error");
    }

    @Override
    protected void onEnqueueFailed() {
      failedHookCalled.set(true);
    }
  }

  @Test
  @DisplayName("error in onEnqueueLocked still reconciles instead of leaking")
  void reconcilesOnHookError() {
    FailingErrorHookQueue queue = new FailingErrorHookQueue();
    assertThatThrownBy(() -> queue.execute(DirectExecutor.INSTANCE, () -> {}))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("hook error");
    assertThat(queue.isIdle()).isTrue();
    assertThat(queue.hasPending()).isFalse();
    assertThat(queue.failedHookCalled.get()).isTrue();
  }

  private static boolean failing(FailingHookQueue queue) {
    return queue.failedHookCalled.get();
  }

  static final class CountingRunnerFinishedQueue extends TaskQueue {
    final AtomicInteger runnerFinished = new AtomicInteger();

    @Override
    protected void onRunnerFinished() {
      runnerFinished.incrementAndGet();
    }
  }

  @Test
  @DisplayName("reconciles the runner exactly once when scheduling fails with an error")
  void reconcilesRunnerExactlyOnceWhenSchedulingFailsWithError() {
    // Given a queue whose selected executor throws an Error while scheduling the runner
    CountingRunnerFinishedQueue queue = new CountingRunnerFinishedQueue();
    Executor failing =
        command -> {
          throw new AssertionError("executor error");
        };

    // When
    assertThatThrownBy(() -> queue.execute(failing, () -> {})).isInstanceOf(AssertionError.class);

    // Then the reconcile hook runs once (onReject and the fail-open catch must not both run it)
    assertThat(queue.runnerFinished).hasValue(1);
    assertThat(queue.isIdle()).isTrue();
    assertThat(queue.hasPending()).isFalse();
  }
}
