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

import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue discard identity")
class TaskQueueDiscardIdentityTest {

  private static final Executor DROP = runnable -> {};

  @Test
  @DisplayName("should report false when the failed head already left the queue")
  void shouldReportFalseWhenFailedHeadAlreadyLeftQueue() {
    TaskQueue queue = new TaskQueue();
    TaskQueue.Task stranger = new TaskQueue.Task(() -> {}, DROP);

    assertThat(queue.discardHeadAndRepoint(stranger)).isFalse();
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should remove the failed head only when it is still queued")
  void shouldRemoveFailedHeadOnlyWhenStillQueued() {
    TaskQueue queue = new TaskQueue();
    queue.execute(DROP, () -> {});
    TaskQueue.Task head = queue.peekHeadOrClearClaim();

    assertThat(queue.discardHeadAndRepoint(head)).isTrue();
    assertThat(queue.isIdle()).isTrue();
  }
}
