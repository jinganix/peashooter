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

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue identity equality")
class TaskQueueIdentityEqualityTest {

  static class ExposingQueue extends TaskQueue {
    final AtomicBoolean failNext = new AtomicBoolean(false);

    @Override
    protected boolean onEnqueueLocked() {
      if (failNext.compareAndSet(true, false)) {
        throw new RuntimeException("hook boom");
      }
      return false;
    }

    private Object queueMonitor() {
      try {
        java.lang.reflect.Field lockField = TaskQueue.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        return lockField.get(this);
      } catch (ReflectiveOperationException e) {
        throw new RuntimeException(e);
      }
    }

    Deque<TaskQueue.Task> snapshot() {
      // Test-only traversal: synchronize on the private monitor via reflection instead of
      // renting production lock helpers, so no test surface leaks into production API.
      synchronized (queueMonitor()) {
        try {
          java.lang.reflect.Field headField = TaskQueue.class.getDeclaredField("head");
          headField.setAccessible(true);
          java.lang.reflect.Field nextField = TaskQueue.Task.class.getDeclaredField("next");
          nextField.setAccessible(true);
          Deque<TaskQueue.Task> copy = new java.util.ArrayDeque<>();
          Object current = headField.get(this);
          while (current != null) {
            copy.add((TaskQueue.Task) current);
            current = nextField.get(current);
          }
          return copy;
        } catch (ReflectiveOperationException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  @Test
  @DisplayName("removeTrigger must remove trigger instance, not first equal")
  void removeTriggerKeepsFirstDuplicate() throws Exception {
    ExposingQueue queue = new ExposingQueue();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Executor blocking =
        cmd -> {
          started.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          cmd.run();
        };
    new Thread(() -> queue.execute(blocking, () -> {})).start();
    started.await();

    Executor worker = cmd -> {};
    Runnable shared = () -> {};

    // First duplicate enqueued behind active runner.
    queue.execute(worker, shared);
    TaskQueue.Task firstTask = queue.snapshot().peekLast();

    // Second duplicate (trigger) fails its enqueue hook -> removeTrigger(trigger).
    queue.failNext.set(true);
    assertThatThrownBy(() -> queue.execute(worker, shared))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("hook boom");

    Deque<TaskQueue.Task> after = queue.snapshot();
    // head(blocking) + 1 survivor expected
    assertThat(after).hasSize(2);
    TaskQueue.Task remaining = after.peekLast();
    // Must be the FIRST instance, not the trigger instance.
    assertThat(remaining).isSameAs(firstTask);

    release.countDown();
  }

  @Test
  @DisplayName("should use identity equality for submitted tasks")
  void taskUsesIdentityEquality() {
    Executor exec = cmd -> {};
    Runnable shared = () -> {};
    TaskQueue.Task a = new TaskQueue.Task(shared, exec);
    TaskQueue.Task b = new TaskQueue.Task(shared, exec);
    // Identity semantics: distinct submissions are never equal even with same fields.
    assertThat(a).isNotEqualTo(b);
    assertThat(a == b).isFalse();
  }

  @Test
  @DisplayName("rejectHandoffHead must not remove equal-but-not-identical front")
  void rejectHeadRequiresIdentity() throws Exception {
    ExposingQueue queue = new ExposingQueue();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Executor blocking =
        cmd -> {
          started.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          cmd.run();
        };
    new Thread(() -> queue.execute(blocking, () -> {})).start();
    started.await();
    try {
      Executor worker = cmd -> {};
      Runnable shared = () -> {};
      // Queue two equal-but-distinct tasks behind the active runner via the public path.
      queue.execute(worker, shared);
      queue.execute(worker, shared);
      assertThat(queue.snapshot()).hasSize(3);

      // Fake a failedHead that is equal but NOT the front instance.
      TaskQueue.Task tailCopy = new TaskQueue.Task(shared, worker);
      Method m =
          TaskQueue.class.getDeclaredMethod(
              "rejectHandoffHead", TaskQueue.Task.class, Throwable.class);
      m.setAccessible(true);
      int before = queue.snapshot().size();
      m.invoke(queue, tailCopy, new RuntimeException("boom"));
      int afterSize = queue.snapshot().size();
      // Identity semantics: front != tailCopy instance so nothing removed by head-poll.
      // (notifyDiscarded runs on tailCopy runnable; shared is no-op so harmless.)
      assertThat(afterSize).isEqualTo(before);
    } finally {
      release.countDown();
    }
  }
}
