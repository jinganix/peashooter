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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Handshake without backing-executor happens-before must degrade to async, never misclassify async
 * as sync (which would unlock a {@link LockableTaskQueue} early and break mutual exclusion).
 */
@DisplayName("Handoff without happens-before degrades to async")
class HandoffWithoutHappensBeforeTest {

  /** Executor with no happens-before: plain (non-volatile, unsynchronized) handoff slot. */
  static final class NoHappensBeforeExecutor implements Executor {
    Runnable slot;

    final Thread pump;

    NoHappensBeforeExecutor() {
      pump =
          new Thread(
              () -> {
                while (!Thread.currentThread().isInterrupted()) {
                  Runnable task = slot;
                  if (task != null) {
                    slot = null;
                    task.run();
                  }
                }
              });
      pump.setDaemon(true);
      pump.start();
    }

    @Override
    public void execute(Runnable command) {
      slot = command;
    }
  }

  @Test
  @DisplayName("handshake state uses atomics and no volatile fields")
  void handshakeStateUsesAtomicsWithoutVolatile() throws Exception {
    Class<?> box = HandoffBoxes.HandoffBox.class;
    for (Field field : box.getDeclaredFields()) {
      assertThat(Modifier.isVolatile(field.getModifiers()))
          .as("handshake field %s#%s must not be volatile", box.getSimpleName(), field.getName())
          .isFalse();
    }
    assertThat(box.getDeclaredField("runnerThread").getType())
        .as("%s.runnerThread", box.getSimpleName())
        .isEqualTo(AtomicReference.class);
    assertThat(box.getDeclaredField("callerReturned").getType())
        .as("%s.callerReturned", box.getSimpleName())
        .isEqualTo(AtomicBoolean.class);
    assertThat(box.getDeclaredField("handshake").getType())
        .as("%s.handshake", box.getSimpleName())
        .isEqualTo(AtomicReference.class);
    Class<?> handshake = HandoffBoxes.Handshake.class;
    assertThat(handshake.isRecord()).as("handshake must be an immutable record").isTrue();
  }

  @Test
  @DisplayName(
      "should run every task exactly once when a TaskQueue hands off over a no-HB executor")
  void taskQueueHandoffOverNoHbExecutorRunsEveryTaskExactlyOnce() throws Exception {
    TaskQueue queue = new TaskQueue();
    NoHappensBeforeExecutor noHb = new NoHappensBeforeExecutor();
    Executor inline = Runnable::run;
    int total = 100;
    CountDownLatch done = new CountDownLatch(total);
    AtomicInteger runs = new AtomicInteger();
    for (int i = 0; i < total; i++) {
      Executor exec = (i & 1) == 0 ? inline : noHb;
      queue.execute(
          exec,
          () -> {
            runs.incrementAndGet();
            done.countDown();
          });
    }
    awaitCountDown(done);
    assertThat(done.getCount()).isZero();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!queue.isIdle() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(runs).hasValue(total);
    assertThat(queue.isIdle()).isTrue();
    noHb.pump.interrupt();
  }

  @Test
  @DisplayName(
      "should keep mutual exclusion when a LockableTaskQueue hands off over a no-HB executor")
  void lockableHandoffOverNoHbExecutorKeepsMutualExclusion() throws Exception {
    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r);
              thread.setDaemon(true);
              return thread;
            });
    try {
      AtomicInteger concurrent = new AtomicInteger();
      AtomicInteger maxConcurrent = new AtomicInteger();
      AtomicBoolean violated = new AtomicBoolean();
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
      NoHappensBeforeExecutor noHb = new NoHappensBeforeExecutor();
      Executor inline = Runnable::run;
      int total = 100;
      CountDownLatch done = new CountDownLatch(total);
      for (int i = 0; i < total; i++) {
        Executor exec = (i & 1) == 0 ? inline : noHb;
        queue.execute(
            exec,
            () -> {
              int live = concurrent.incrementAndGet();
              maxConcurrent.accumulateAndGet(live, Math::max);
              if (live > 1) {
                violated.set(true);
              }
              try {
                Thread.sleep(1);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                concurrent.decrementAndGet();
                done.countDown();
              }
            });
      }
      awaitCountDown(done);
      assertThat(done.getCount()).isZero();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!queue.isIdle() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertThat(violated).isFalse();
      assertThat(maxConcurrent.get()).isLessThanOrEqualTo(1);
      assertThat(queue.isIdle()).isTrue();
      noHb.pump.interrupt();
    } finally {
      scheduler.shutdownNow();
    }
  }
}
