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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Rejects the tail trigger (not the preserved head) when a backlog waits with no runner. */
class TaskQueueTriggerTailRejectionTest {
  static final class RejectCapture implements Runnable, RejectionAware {
    final Runnable delegate;
    final AtomicReference<Throwable> rejected = new AtomicReference<>();

    RejectCapture(Runnable delegate) {
      this.delegate = delegate;
    }

    @Override
    public void run() {
      delegate.run();
    }

    @Override
    public void rejected(Throwable cause) {
      rejected.set(cause);
    }
  }

  @Test
  @DisplayName("should remove tail trigger and preserve head when backlog waits with no runner")
  void shouldRemoveTailTriggerAndPreserveHead() {
    TaskQueue queue = new TaskQueue();
    List<Runnable> runners = new ArrayList<>();
    Executor capturing = runners::add;

    Runnable interruptTask =
        () -> {
          throw new CompletionException(new InterruptedException("boom"));
        };
    CompletableFuture<String> preservedDone = new CompletableFuture<>();
    RejectCapture preserved = new RejectCapture(() -> preservedDone.complete("preserved"));
    RejectCapture tail = new RejectCapture(() -> {});

    // Preserved backlog with no runner: interrupt ends the batch, preserved stays queued.
    queue.execute(capturing, interruptTask);
    queue.execute(capturing, preserved);
    assertThat(runners).hasSize(1);
    runners.remove(0).run();
    assertThat(Thread.interrupted()).isTrue();
    assertThat(queue.hasPending()).isTrue();

    Executor rejecting =
        command -> {
          throw new RejectedExecutionException("saturated");
        };
    assertThatThrownBy(() -> queue.execute(rejecting, tail))
        .isInstanceOf(RejectedExecutionException.class);
    // Only the tail trigger fails; the preserved head stays queued and is never notified.
    assertThat(tail.rejected.get()).isInstanceOf(RejectedExecutionException.class);
    assertThat(preserved.rejected.get()).isNull();
    assertThat(queue.hasPending()).isTrue();
    assertThat(queue.size()).isEqualTo(1);

    // A resumed runner is stored on the capturing executor: run it to drain the survivor.
    assertThat(runners).hasSize(1);
    runners.remove(0).run();
    assertThat(preservedDone).isCompletedWithValue("preserved");
    assertThat(preserved.rejected.get()).isNull();
    assertThat(queue.isIdle()).isTrue();

    // The rejected tail never runs later (removed, not preserved).
    List<String> order = new ArrayList<>();
    queue.execute(Runnable::run, () -> order.add("last"));
    assertThat(order).containsExactly("last");
    assertThat(queue.isIdle()).isTrue();
  }
}
