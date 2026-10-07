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

import static io.github.jinganix.peashooter.utils.TestUtils.awaitCountDown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue rejection count")
class TaskQueueDiscardCountTest {

  @Test
  @DisplayName("should reject only the triggering submit when executor rejects on idle queue")
  void shouldRejectOnlyTheTriggeringSubmitWhenExecutorRejects() {
    // Given an idle queue and a saturated executor
    TaskQueue queue = new TaskQueue();
    Executor rejecting = mock(Executor.class);
    doThrow(new RejectedExecutionException("rejected")).when(rejecting).execute(any());
    Runnable trigger = mock(Runnable.class);

    // When the triggering submit is rejected Then it fails visibly to the submitter
    assertThatThrownBy(() -> queue.execute(rejecting, trigger))
        .isInstanceOf(RejectedExecutionException.class)
        .hasMessageContaining("rejected");
    verify(trigger, never()).run();

    // And the queue stays usable for the next submission instead of stranding
    CountDownLatch latch = new CountDownLatch(1);
    Executor healthy = command -> new Thread(command).start();
    queue.execute(healthy, latch::countDown);
    awaitCountDown(latch);
    // The latch fires inside the task body while the runner still holds its claim; the claim
    // is released only on the runner's next (empty) poll, so poll for quiescence instead of
    // asserting immediately.
    await().atMost(Duration.ofSeconds(10)).until(queue::isIdle);
    assertThat(queue.isIdle()).isTrue();
  }
}
