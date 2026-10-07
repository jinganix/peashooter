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

package io.github.jinganix.peashooter.redisson.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.queue.ExecutionCountStats;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RLock;

/** Redis lock lifecycle: non-blocking tryLock and unlock ownership. */
@ExtendWith(RedisExtension.class)
@DisplayName("RedisLockableTaskQueue")
class RedisLockableTaskQueueTest {

  static final class ExposedQueue extends RedisLockableTaskQueue {
    ExposedQueue(String lockName) {
      super(lockName);
    }

    boolean exposedTryLock(ExecutionStats stats) {
      return tryLock(stats);
    }

    void exposedUnlock() {
      unlock();
    }

    long exposedFencingToken() {
      return fencingToken();
    }
  }

  /** Simulates a batch that overran the fixed lease: the local lease lapses while held. */
  private static void expireLease(ExposedQueue queue) throws Exception {
    java.lang.reflect.Field field = RedisLockableTaskQueue.class.getDeclaredField("leaseEndNanos");
    field.setAccessible(true);
    java.util.concurrent.atomic.AtomicLong leaseEnd =
        (java.util.concurrent.atomic.AtomicLong) field.get(queue);
    leaseEnd.set(System.nanoTime() - 1);
  }

  @Test
  @DisplayName("should shut down its rescheduler on close")
  void shouldShutDownItsReschedulerOnClose() {
    // Given a queue owning its rescheduler
    ExposedQueue queue = new ExposedQueue("lifecycle-" + UUID.randomUUID());

    // When closed Then the rescheduler shuts down (idempotently) instead of leaking a thread
    assertThat(queue.isReschedulerShutdown()).isFalse();
    queue.close();
    assertThat(queue.isReschedulerShutdown()).isTrue();
    queue.close();
    assertThat(queue.isReschedulerShutdown()).isTrue();
  }

  @Test
  @DisplayName("should return immediately when the lock is contended")
  void shouldReturnImmediatelyWhenLockIsContended() throws Exception {
    // Given a lock held by another owner
    String name = "lock-contention-" + UUID.randomUUID();
    RLock external = RedisClient.get().getFairLock(name);
    external.lock();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (ExposedQueue queue = new ExposedQueue(name)) {
      // When contending for the same lock from a different thread
      Future<long[]> future =
          pool.submit(
              () -> {
                long start = System.nanoTime();
                boolean acquired = queue.exposedTryLock(new ExecutionCountStats());
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                return new long[] {acquired ? 1 : 0, elapsedMs};
              });
      long[] result = future.get(15, TimeUnit.SECONDS);

      // Then it must fail fast instead of blocking ~5s inside tryLock
      assertThat(result[0]).isEqualTo(0);
      assertThat(result[1]).isLessThan(1000);
    } finally {
      pool.shutdownNow();
      external.unlock();
    }
  }

  @Test
  @DisplayName("should not release a lock it does not own")
  void shouldNotReleaseLockItDoesNotOwn() throws Exception {
    // Given a lock held by this thread
    String name = "lock-unowned-" + UUID.randomUUID();
    RLock external = RedisClient.get().getFairLock(name);
    external.lock();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // When a queue that never acquired the lock unlocks from a different thread
      Future<?> future =
          pool.submit(
              () -> {
                try (ExposedQueue queue = new ExposedQueue(name)) {
                  queue.exposedUnlock();
                } catch (Exception ignored) {
                  // Strict unlock may fail loudly when contended; either way the hold must survive.
                }
              });
      future.get(15, TimeUnit.SECONDS);

      // Then the hold must survive instead of being force-released
      assertThat(external.isLocked()).isTrue();
    } finally {
      pool.shutdownNow();
      if (external.isLocked()) {
        external.unlock();
      }
    }
  }

  @Test
  @DisplayName("should release the lock from a different thread after handoff")
  void shouldReleaseLockFromDifferentThreadAfterHandoff() throws Exception {
    // Given a lock acquired on one runner thread (async handoff moves release elsewhere)
    String name = "lock-handoff-" + UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (ExposedQueue queue = new ExposedQueue(name)) {
      Future<Boolean> acquired = pool.submit(() -> queue.exposedTryLock(new ExecutionCountStats()));
      assertThat(acquired.get(15, TimeUnit.SECONDS)).isTrue();

      // When released from a different thread
      Future<?> released = pool.submit(queue::exposedUnlock);
      released.get(15, TimeUnit.SECONDS);

      // Then the lock must be free (thread-agnostic release per LockableTaskQueue contract)
      RLock probe = RedisClient.get().getFairLock(name);
      assertThat(probe.isLocked()).isFalse();
    } finally {
      pool.shutdownNow();
      RLock probe = RedisClient.get().getFairLock(name);
      if (probe.isLocked()) {
        probe.forceUnlock();
      }
    }
  }

  @Test
  @DisplayName("should throw when unlock runs after lease expiry")
  void shouldThrowWhenUnlockAfterLeaseExpired() throws Exception {
    // Given a queue holding the lock whose batch overran the fixed lease
    String name = "lock-lease-" + UUID.randomUUID();
    ExposedQueue queue = new ExposedQueue(name);
    try {
      assertThat(queue.exposedTryLock(new ExecutionCountStats())).isTrue();
      expireLease(queue);

      // When the over-long batch unlocks Then it must fail loudly instead of silently
      // running on without mutual exclusion
      assertThatThrownBy(queue::exposedUnlock)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("lease");
    } finally {
      queue.close();
      RLock probe = RedisClient.get().getFairLock(name);
      if (probe.isLocked()) {
        probe.forceUnlock();
      }
    }
  }

  @Test
  @DisplayName("should throw without releasing the new owner when closing after lease expiry")
  void shouldThrowWithoutReleasingNewOwnerWhenClosingAfterLeaseExpiry() throws Exception {
    // Given a queue whose lease lapsed while another owner acquired the same lock name
    String name = "lock-lease-close-" + UUID.randomUUID();
    ExposedQueue queue = new ExposedQueue(name);
    RLock nextOwner = RedisClient.get().getFairLock(name);
    try {
      assertThat(queue.exposedTryLock(new ExecutionCountStats())).isTrue();
      expireLease(queue);
      // Simulate the Redis-side lapse: the stale hold is gone, a new owner holds the name.
      RLock stale = RedisClient.get().getFairLock(name);
      if (stale.isLocked()) {
        stale.forceUnlock();
      }
      nextOwner.lock();

      // When closed after the lease lapsed Then it must throw instead of forceUnlocking
      // the new owner's hold (TOCTOU cross-release)
      assertThatThrownBy(queue::close)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("lease");
      assertThat(nextOwner.isLocked()).isTrue();
    } finally {
      queue.close();
      if (nextOwner.isLocked()) {
        nextOwner.unlock();
      }
      RLock probe = RedisClient.get().getFairLock(name);
      if (probe.isLocked()) {
        probe.forceUnlock();
      }
    }
  }

  @Test
  @DisplayName("should expose a monotonic fencing token per lock acquisition")
  void shouldExposeMonotonicFencingTokenWhenLockAcquired() {
    // Given a queue that never acquired the lock
    String name = "lock-fencing-" + UUID.randomUUID();
    try (ExposedQueue queue = new ExposedQueue(name)) {
      long before = queue.exposedFencingToken();

      // When the lock is acquired Then the fencing token advances exactly once per hold
      assertThat(queue.exposedTryLock(new ExecutionCountStats())).isTrue();
      long afterAcquire = queue.exposedFencingToken();
      assertThat(afterAcquire).isGreaterThan(before);

      // And a release keeps the token stable while the next acquisition advances it again
      queue.exposedUnlock();
      assertThat(queue.exposedFencingToken()).isEqualTo(afterAcquire);
      assertThat(queue.exposedTryLock(new ExecutionCountStats())).isTrue();
      assertThat(queue.exposedFencingToken()).isGreaterThan(afterAcquire);
    }
  }

  @Test
  @DisplayName("should release a held lock on close")
  void shouldReleaseHeldLockOnClose() {
    // Given a queue holding the lock
    String name = "lock-close-" + UUID.randomUUID();
    ExposedQueue queue = new ExposedQueue(name);
    try {
      assertThat(queue.exposedTryLock(new ExecutionCountStats())).isTrue();

      // When closed while locked Then the Redis hold must be released, not leaked
      queue.close();
      RLock probe = RedisClient.get().getFairLock(name);
      assertThat(probe.isLocked()).isFalse();
    } finally {
      queue.close();
      RLock probe = RedisClient.get().getFairLock(name);
      if (probe.isLocked()) {
        probe.forceUnlock();
      }
    }
  }
}
