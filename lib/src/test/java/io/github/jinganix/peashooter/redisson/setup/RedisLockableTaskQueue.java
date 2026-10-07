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

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.queue.ExecutionCountStats;
import io.github.jinganix.peashooter.queue.LockableTaskQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.redisson.api.RLock;

/**
 * {@link LockableTaskQueue} test double backed by a Redis fair lock.
 *
 * <p>Test-only: never used in production code.
 *
 * <p>Owns a per-instance reschedule scheduler (never a static pool: test classes share one JVM, so
 * a static pool closed by one class's {@code AfterAll} would break every class running after it).
 * Close every instance (try-with-resources) so contention-retry threads never outlive the test.
 *
 * <p><b>Lease contract:</b> the Redis lock is held under a fixed {@value #LEASE_SECONDS}-second
 * lease with <em>no renewal</em>. This test double deliberately omits a renewal thread to stay
 * simple: every test batch is assumed to complete well within {@value #LEASE_SECONDS} seconds. A
 * batch that overruns its lease fails loudly at {@code unlock}/{@code close} with {@link
 * IllegalStateException} instead of silently running on without mutual exclusion, and the stale
 * hold is left for Redis expiry rather than {@code forceUnlock}ed (which could release a new owner
 * that acquired the name since).
 *
 * <p><b>Fencing:</b> each successful {@code tryLock} mints a monotonic {@link #fencingToken()}.
 * Tasks that must not act on a stale hold capture the token at batch start and re-check it (plus
 * the lease) before any side effect; a changed token or a lapsed lease means the batch lost
 * ownership and must abort instead of writing.
 *
 * <p><b>Ownership (exception to the base rule):</b> unlike {@link LockableTaskQueue}, whose
 * scheduler stays caller-owned and which is not closeable, this double creates its own per-instance
 * rescheduler and therefore owns it: {@link #close()} shuts it down. Production code must follow
 * the base rule (share one scheduler, close it explicitly) and never copy this per-instance
 * ownership.
 */
public class RedisLockableTaskQueue extends LockableTaskQueue implements AutoCloseable {

  /**
   * Explicit lease replacing the Redisson watchdog: the watchdog renews only on the acquiring
   * thread, but {@link LockableTaskQueue} releases after an async handoff on another thread. Never
   * renewed; see the class-level lease contract.
   */
  private static final long LEASE_SECONDS = 30;

  private final ScheduledExecutorService rescheduler;

  private final RLock lock;

  /**
   * Local ownership guard: only the queue that acquired the lock may release it, from any thread.
   * Without it, a guarded {@code forceUnlock} could not tell "our hold released on another thread"
   * apart from "a lock we never owned".
   */
  private final AtomicBoolean held = new AtomicBoolean(false);

  /**
   * Monotonic fencing epoch: advanced exactly once per successful {@link #tryLock}. See the
   * class-level fencing contract.
   */
  private final AtomicLong fencing = new AtomicLong();

  /**
   * {@link System#nanoTime()} at which the current lease lapses. A lapsed lease means another owner
   * may hold the lock name, so release throws instead of {@code forceUnlock}ing theirs.
   */
  private final AtomicLong leaseEndNanos = new AtomicLong();

  public RedisLockableTaskQueue(String lockName) {
    this(lockName, newRescheduler());
  }

  private RedisLockableTaskQueue(String lockName, ScheduledExecutorService rescheduler) {
    super(new ExecutionCountStats(), rescheduler);
    this.rescheduler = rescheduler;
    this.lock = RedisClient.get().getFairLock(lockName);
  }

  private static ScheduledExecutorService newRescheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable);
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * Shuts down the instance rescheduler and releases a held lock; idempotent for unheld queues (a
   * second close after a successful release is a no-op). A failed test must never leave its lock in
   * Redis for the next test to contend with.
   *
   * @throws IllegalStateException when a held lock's lease already lapsed (the stale hold is left
   *     for Redis expiry; a new owner's hold is never released)
   */
  @Override
  public void close() {
    rescheduler.shutdownNow();
    releaseIfHeld();
  }

  long fencingToken() {
    return fencing.get();
  }

  boolean isReschedulerShutdown() {
    return rescheduler.isShutdown();
  }

  @Override
  protected boolean tryLock(ExecutionStats stats) {
    // Non-blocking by contract: the queue reschedules contended batches on exponential backoff
    // 1-100ms,
    // so waiting here would pin the runner thread (and the acquireLock monitor) for seconds.
    // Explicit lease instead of the watchdog: the watchdog binds renewal to the acquiring
    // thread, but unlock runs after an async handoff on another thread.
    boolean acquired;
    try {
      acquired = lock.tryLock(0, LEASE_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    if (acquired) {
      leaseEndNanos.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(LEASE_SECONDS));
      fencing.incrementAndGet();
      held.set(true);
    }
    return acquired;
  }

  @Override
  protected boolean shouldYield(ExecutionStats stats) {
    // Satisfy the saturation contract (see ExecutionStats#getExecutionCount): never compare
    // with == N, test >= N, because a saturated counter stays at MAX_VALUE.
    return stats.getExecutionCount() >= 5;
  }

  @Override
  protected void unlock() {
    // Thread-agnostic release: the batch may unlock on a different thread than the one that
    // acquired, so thread-bound RLock.unlock() would throw IllegalMonitorStateException.
    // forceUnlock works from any thread; the held guard keeps it from releasing a lock
    // this queue never acquired.
    releaseIfHeld();
  }

  /**
   * Releases the hold once, guarded by {@link #held}. Fails loudly when the lease already lapsed:
   * the batch overran its hold, so mutual exclusion is lost and the stale hold is left for Redis
   * expiry instead of {@code forceUnlock}ing a lock a new owner may have acquired since. The lease
   * is re-checked after the remote {@code isLocked} probe to narrow (but not close) the inherent
   * TOCTOU window between the checks and {@code forceUnlock}: atomic check-and-release would need a
   * server-side Lua script, which this test double deliberately avoids. Batches are therefore
   * expected to stay far inside the {@value #LEASE_SECONDS}-second lease; anything past it throws.
   *
   * @throws IllegalStateException when the lease lapsed before release
   */
  private void releaseIfHeld() {
    if (!held.compareAndSet(true, false)) {
      return;
    }
    // Past the lease our Redis lock has already lapsed; forceUnlock could release whatever
    // owner acquired the name since. Overflow-safe elapsed comparison, no subtraction of a
    // relative and an absolute clock.
    if (isLeaseExpired()) {
      throw leaseExpired();
    }
    if (lock.isLocked()) {
      if (isLeaseExpired()) {
        throw leaseExpired();
      }
      lock.forceUnlock();
    }
  }

  private boolean isLeaseExpired() {
    return System.nanoTime() - leaseEndNanos.get() >= 0;
  }

  private static IllegalStateException leaseExpired() {
    return new IllegalStateException(
        "Redis lock lease of "
            + LEASE_SECONDS
            + "s lapsed before unlock: the batch overran its hold and lost mutual exclusion;"
            + " leaving the stale hold for Redis expiry instead of releasing a new owner's lock");
  }
}
