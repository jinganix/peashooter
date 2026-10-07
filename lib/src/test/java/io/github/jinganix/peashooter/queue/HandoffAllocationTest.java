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
import static io.github.jinganix.peashooter.utils.TestUtils.uncheckedRun;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Handoff allocation")
class HandoffAllocationTest {

  @Test
  @DisplayName("should reuse handoff wrapper when alternating inline executors in TaskQueue")
  void shouldReuseHandoffWrapperWhenAlternatingInlineExecutorsInTaskQueue()
      throws InterruptedException {
    // Given a runner parked on a blocking head while alternating inline tasks pile up
    TaskQueue queue = new TaskQueue();
    List<Runnable> captured = Collections.synchronizedList(new ArrayList<>());
    Executor inlineA =
        cmd -> {
          captured.add(cmd);
          cmd.run();
        };
    Executor inlineB =
        cmd -> {
          captured.add(cmd);
          cmd.run();
        };
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Executor blocking =
        cmd -> {
          started.countDown();
          uncheckedRun(release::await);
          cmd.run();
        };
    int total = 50;
    CountDownLatch done = new CountDownLatch(total);
    new Thread(() -> queue.execute(blocking, () -> {})).start();
    started.await();
    for (int i = 0; i < total; i++) {
      Executor exec = (i & 1) == 0 ? inlineA : inlineB;
      queue.execute(exec, done::countDown);
    }

    // When the head unblocks, all alternating handoffs drain inline on one thread
    release.countDown();
    awaitCountDown(done);

    // Then hot path must reuse the ThreadLocal handshake box: one identity, not N wrappers
    assertThat(captured).hasSizeGreaterThan(1);
    Set<Runnable> identities = Collections.newSetFromMap(new IdentityHashMap<>());
    identities.addAll(captured);
    assertThat(identities)
        .as(
            "alternating inline handoffs should reuse one wrapper, got %d distinct",
            identities.size())
        .hasSize(1);
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName(
      "should reuse handoff wrapper when alternating inline executors in LockableTaskQueue")
  void shouldReuseHandoffWrapperWhenAlternatingInlineExecutorsInLockableQueue()
      throws InterruptedException {
    // Given a lockable queue parked on a blocking head
    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r);
              t.setDaemon(true);
              return t;
            });
    try {
      LockableTaskQueue queue =
          new LockableTaskQueue(new ExecutionCountStats(), scheduler) {
            @Override
            protected boolean tryLock(io.github.jinganix.peashooter.ExecutionStats stats) {
              return true;
            }

            @Override
            protected boolean shouldYield(io.github.jinganix.peashooter.ExecutionStats stats) {
              return false;
            }

            @Override
            protected void unlock() {}
          };
      List<Runnable> captured = Collections.synchronizedList(new ArrayList<>());
      Executor inlineA =
          cmd -> {
            captured.add(cmd);
            cmd.run();
          };
      Executor inlineB =
          cmd -> {
            captured.add(cmd);
            cmd.run();
          };
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      Executor blocking =
          cmd -> {
            started.countDown();
            uncheckedRun(release::await);
            cmd.run();
          };
      int total = 50;
      CountDownLatch done = new CountDownLatch(total);
      new Thread(() -> queue.execute(blocking, () -> {})).start();
      started.await();
      for (int i = 0; i < total; i++) {
        Executor exec = (i & 1) == 0 ? inlineA : inlineB;
        queue.execute(exec, done::countDown);
      }
      release.countDown();
      awaitCountDown(done);

      // Then hot path must reuse one wrapper
      assertThat(captured).hasSizeGreaterThan(1);
      Set<Runnable> identities = Collections.newSetFromMap(new IdentityHashMap<>());
      identities.addAll(captured);
      assertThat(identities)
          .as(
              "lockable alternating handoffs should reuse one wrapper, got %d distinct",
              identities.size())
          .hasSize(1);
      // idle may need unlock drain; poll briefly
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (!queue.isIdle() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertThat(queue.isIdle()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  @DisplayName("should reuse the runner drain instead of a per-submit wrapper")
  void shouldReuseRunnerDrainInsteadOfPerSubmitWrapper() {
    // Given a queue whose executor captures the drain handed to it before running inline
    TaskQueue queue = new TaskQueue();
    List<Runnable> drains = new ArrayList<>();
    Executor capturing =
        command -> {
          HandoffBoxes.HandoffBox box = (HandoffBoxes.HandoffBox) command;
          drains.add(box.handshake.get().asyncDrain());
          command.run();
        };

    // When three sync submits each schedule the runner through that executor
    for (int i = 0; i < 3; i++) {
      queue.execute(capturing, () -> {});
    }

    // Then the stable runner field is handed off repeatedly, not a fresh wrapper per submit
    assertThat(drains).hasSize(3);
    assertThat(drains).allMatch(drain -> drain == drains.get(0));
    assertThat(queue.isIdle()).isTrue();
  }

  @Test
  @DisplayName("should allocate one box per async submit (sync-only reuse)")
  void shouldAllocateOneBoxPerAsyncSubmit() throws InterruptedException {
    // Given a queue with an async executor that captures without running inline
    TaskQueue queue = new TaskQueue();
    List<Runnable> captured = Collections.synchronizedList(new ArrayList<>());
    java.util.concurrent.ExecutorService async =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r);
              t.setDaemon(true);
              return t;
            });
    try {
      Executor capturing =
          cmd -> {
            captured.add(cmd);
            async.execute(cmd);
          };
      int total = 10;
      for (int i = 0; i < total; i++) {
        CountDownLatch done = new CountDownLatch(1);
        queue.execute(capturing, done::countDown);
        awaitCountDown(done);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!queue.isIdle() && System.nanoTime() < deadline) {
          Thread.sleep(10);
        }
      }

      // Then async runner starts hand the box to the runner thread: no sync reuse across
      // idle-to-idle runs, each run allocates anew
      assertThat(captured).hasSize(total);
      Set<Runnable> identities = Collections.newSetFromMap(new IdentityHashMap<>());
      identities.addAll(captured);
      assertThat(identities)
          .as(
              "async idle-to-idle submits must not claim sync-only reuse, got %d distinct",
              identities.size())
          .hasSize(total);
      assertThat(queue.isIdle()).isTrue();
    } finally {
      async.shutdownNow();
    }
  }
}
