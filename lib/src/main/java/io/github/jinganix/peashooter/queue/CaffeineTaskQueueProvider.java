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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.jinganix.peashooter.TaskQueueProvider;
import io.github.jinganix.peashooter.internal.KeySanitizer;
import io.github.jinganix.peashooter.internal.Keys;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TaskQueueProvider} implemented with {@link Caffeine}.
 *
 * <p>Each distinct key maps to its own {@link TaskQueue}. Entries expire after the configured
 * access idle period once the queue is fully idle (no pending tasks and no active runner); while
 * work is in flight the entry is pinned so a long-running task cannot be split across two queue
 * instances for the same key.
 *
 * <p>Maintenance: idle expiry is driven automatically by Caffeine's system scheduler, so idle
 * entries are reclaimed even when callers never invoke {@link #cleanUp()}. The soft key-count bound
 * (default {@link #DEFAULT_MAXIMUM_SIZE}) converges via an adaptive piggyback sweep on the submit
 * slow path (new keys / idle revivals, the only path that grows cardinality): a sampled size probe
 * sweeps at most one capped scan when over bound, so a caller that never calls {@link #cleanUp()}
 * still converges without stalling any submit. {@link #cleanUp()} remains as the explicit trigger
 * for a full synchronous sweep (same cap per call). Neither path ever evicts a busy queue; submit
 * fast paths never sweep. {@link #estimatedSize()} exposes the current entry count (pure getter, no
 * logging), {@link #checkBoundAndWarn()} reports a sustained overshoot with a WARN, and {@link
 * #cleanUp()} returns the actual evicted count plus a WARN so the drive frequency can be sized.
 *
 * <p>Drive-interval sizing: one sweep (piggyback or {@link #cleanUp()}) evicts at most {@value
 * #MAX_SWEEP_SCAN} idle entries (fewer when busy queues consume scan budget). With a sustained
 * new-key rate of {@code R} keys/sec, the piggyback converges on its own while {@code R} stays
 * modest; drive {@link #cleanUp()} explicitly with interval {@code T <= MAX_SWEEP_SCAN / R} under
 * sustained bursts (e.g. {@code R = 1000} keys/s needs {@code T <= ~250ms}); an overshoot of {@code
 * D = size - bound} needs at least {@code ceil(D / MAX_SWEEP_SCAN)} sweeps to converge. Poll {@link
 * #estimatedSize()} and call {@link #checkBoundAndWarn()} while over bound, then shrink {@code T}
 * or grow the bound while sweeps keep returning a full scan's worth of evictions.
 */
public class CaffeineTaskQueueProvider implements TaskQueueProvider {

  private static final Logger log = LoggerFactory.getLogger(CaffeineTaskQueueProvider.class);

  /**
   * Submit-path over-bound WARN sample rate: one in every this many slow-path (new/idle-revival)
   * submits probes {@code estimatedSize()} and WARNs while over the bound. The fast path (repeated
   * busy submits) never probes, so steady-state hot keys pay zero extra cost and submit still never
   * sweeps — the probe only observes.
   */
  private static final int SUBMIT_WARN_SAMPLE = 256;

  // Pinned while work is in flight. 100 years stays below Caffeine's MAXIMUM_EXPIRY
  // (Long.MAX_VALUE >> 1, ~150 years); the ticker origin is arbitrary and may start near
  // Long.MAX_VALUE, but durationFor returns this unchanged and Caffeine's (now - expiry)
  // comparison stays correct across the wrap.
  private static final long PINNED_NANOS = Duration.ofDays(365 * 100).toNanos();

  private final Cache<String, PinnedTaskQueue> queues;

  /**
   * Soft entry bound applied by idle-only sweeping. Busy queues are never evicted to meet the
   * bound: only idle entries are removed, so the size may temporarily exceed the bound while many
   * keys are in flight.
   */
  private final long maximumSize;

  /**
   * Maximum cache entries scanned per sweep baseline. One {@link #cleanUp()} never pays more than
   * this many idle probes, no matter how far over the bound the map is; the soft bound then
   * converges across later cleanups (and idle expiry) instead of stalling one caller with an O(N)
   * scan. Sized so one sweep stays well under a millisecond.
   */
  private static final int MAX_SWEEP_SCAN = 256;

  /** Default idle expiry: 5 minutes. Named so the default is documented in one place. */
  private static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofMinutes(5);

  /**
   * Default soft entry bound applied when no explicit bound is given.
   *
   * <p>Sized for typical per-key ordering workloads: well above hot-key cardinality but low enough
   * that high-cardinality misuse (e.g. per-request or per-user ids with no reuse) converges via
   * idle-only sweeping instead of growing memory linearly until idle expiry.
   */
  public static final long DEFAULT_MAXIMUM_SIZE = 10_000;

  /** Constructor with 5-minute idle expiry and {@link #DEFAULT_MAXIMUM_SIZE} soft bound. */
  public CaffeineTaskQueueProvider() {
    this(DEFAULT_EXPIRE_AFTER_ACCESS, DEFAULT_MAXIMUM_SIZE, Ticker.systemTicker());
  }

  /**
   * Create a provider that expires idle queues after the given access duration.
   *
   * <p>Expiration is based on last access to the queue entry while the queue is idle. Entries stay
   * pinned while tasks are pending or a runner is active. Tune this value for the expected key
   * cardinality and how long per-key ordering must be preserved across idle gaps.
   *
   * <p>The soft key-count bound defaults to {@link #DEFAULT_MAXIMUM_SIZE}: idle entries past the
   * bound are swept (never a busy queue, so the size may temporarily exceed the bound while many
   * keys are in flight). Size the bound for peak key cardinality via {@link
   * #CaffeineTaskQueueProvider(Duration, long)} when 10k does not fit.
   *
   * @param expireAfterAccess idle period after which an unused queue entry is evicted
   * @throws NullPointerException if {@code expireAfterAccess} is {@code null}
   * @throws IllegalArgumentException if {@code expireAfterAccess} is zero or negative
   */
  public CaffeineTaskQueueProvider(Duration expireAfterAccess) {
    this(expireAfterAccess, DEFAULT_MAXIMUM_SIZE, Ticker.systemTicker());
  }

  /**
   * Create a provider with idle expiry and key cardinality bound.
   *
   * <p>Unlike a raw Caffeine LRU bound (which can evict even pinned, busy queues and split per-key
   * ordering), the bound here is enforced by sweeping only idle entries: a busy queue is never
   * evicted to meet it, so the size may temporarily exceed the bound while many keys are in flight.
   * Size the bound for peak key cardinality.
   *
   * @param expireAfterAccess idle period after which an unused queue entry is evicted
   * @param maximumSize soft maximum cached queues; idle entries are swept past this size, must be
   *     positive
   * @throws NullPointerException if {@code expireAfterAccess} is {@code null}
   * @throws IllegalArgumentException if {@code expireAfterAccess} is zero or negative, or {@code
   *     maximumSize} is not positive
   */
  public CaffeineTaskQueueProvider(Duration expireAfterAccess, long maximumSize) {
    this(expireAfterAccess, maximumSize, Ticker.systemTicker());
  }

  private static long requirePositiveMaximumSize(long maximumSize) {
    if (maximumSize <= 0) {
      throw new IllegalArgumentException("maximumSize must be positive");
    }
    return maximumSize;
  }

  /**
   * Creates a provider with an explicit ticker and the default cardinality bound.
   *
   * <p>Package-private test hook (the equivalent of {@code @VisibleForTesting} without adding a
   * dependency): production callers use the public constructors with the system ticker. A manual
   * ticker makes idle expiry deterministic (wall-clock sleeps are flaky under load), so expiry
   * contracts can be asserted without timing margins.
   *
   * @param expireAfterAccess idle period after which an unused queue entry is evicted
   * @param ticker time source for entry expiry
   */
  CaffeineTaskQueueProvider(Duration expireAfterAccess, Ticker ticker) {
    this(expireAfterAccess, DEFAULT_MAXIMUM_SIZE, ticker);
  }

  /**
   * Creates a provider with an explicit ticker and cardinality bound.
   *
   * <p>Package-private test hook (the equivalent of {@code @VisibleForTesting} without adding a
   * dependency): production callers use the public constructors with the system ticker. A manual
   * ticker makes idle expiry deterministic (wall-clock sleeps are flaky under load), so expiry
   * contracts can be asserted without timing margins.
   *
   * @param expireAfterAccess idle period after which an unused queue entry is evicted
   * @param maximumSize soft maximum cached queues, must be positive
   * @param ticker time source for entry expiry
   */
  CaffeineTaskQueueProvider(Duration expireAfterAccess, long maximumSize, Ticker ticker) {
    Objects.requireNonNull(expireAfterAccess, "expireAfterAccess");
    if (expireAfterAccess.isNegative() || expireAfterAccess.isZero()) {
      throw new IllegalArgumentException("expireAfterAccess must be positive");
    }
    requirePositiveMaximumSize(maximumSize);
    Objects.requireNonNull(ticker, "ticker");
    this.maximumSize = maximumSize;
    this.queues =
        Caffeine.newBuilder()
            .ticker(ticker)
            .expireAfter(new QueueExpiry(expireAfterAccess))
            .scheduler(Scheduler.systemScheduler())
            .build();
  }

  @Override
  public boolean isIdle(String key) {
    PinnedTaskQueue queue = peekQueue(key);
    return queue == null || queue.isIdle();
  }

  @Override
  public boolean hasPending(String key) {
    PinnedTaskQueue queue = peekQueue(key);
    return queue != null && queue.hasPending();
  }

  /**
   * Pure-read probe shared by {@link #isIdle} and {@link #hasPending}: no creation, no pin, no
   * sweep. A read-only probe must never materialize an entry for a key that was never submitted,
   * and never hands out the live queue, so callers cannot bypass the getForSubmit fence with an
   * unfenced execute.
   */
  private PinnedTaskQueue peekQueue(String key) {
    return queues.asMap().get(Keys.requireKey(key));
  }

  /**
   * {@inheritDoc}
   *
   * <p>The lazy queue factory runs inside the Caffeine bin lock (an atomic get-or-create), so it
   * must not re-enter this provider; any provider call from the factory can deadlock on that lock
   * or corrupt the submit fence.
   *
   * <p><b>Lock order:</b> cache bin lock {@code ->} queue monitor is the only allowed direction
   * (the mapping function takes the queue monitor briefly via {@code pinForSubmit}, which never
   * blocks on third-party code). The reverse direction is forbidden: no path may hold the queue
   * monitor while entering the cache (all {@code refreshExpiry} calls run outside the monitor, and
   * {@link #invalidateIfIdle} probes only the lock-free pin). Keep this order frozen or the bin
   * lock plus monitor can deadlock under contention.
   */
  @Override
  public TaskQueue getForSubmit(String key) {
    Keys.requireKey(key);
    // Hot path: lock-free get plus pin. A queue already pinned at pin time is immune to
    // eviction (sweep and expiry both retain busy entries, and the pin serializes with the
    // drain on the queue monitor), so no bin lock or expiry re-arm is needed. An idle entry
    // needs its variable expiry re-armed to the pinned duration, which only a cache write
    // provides: skip the optimistic pin and take the atomic slow path directly, so idle
    // revival pays a single bin-locked write instead of pin + abort (with its extra
    // refreshExpiry write) + re-pin. New keys miss and go slow directly. Fast-path submits
    // never sweep: cardinality is reclaimed by the slow-path piggyback and cleanUp, so
    // steady-state submit pays zero ticker reads, zero size sums, and zero sweep probes.
    PinnedTaskQueue fast = queues.asMap().get(key);
    if (fast != null) {
      if (fast.isPinned()) {
        boolean becameBusy = fast.pinForSubmit();
        if (!becameBusy) {
          return fast;
        }
        // Rare race: observed pinned but drained before the pin, so this pin transitioned
        // idle->busy and needs an expiry re-arm. Undo it and take the slow path.
        fast.abortSubmit();
      }
      // Else idle: fall through to the slow path without a prior pin/abort.
    }
    // Slow path: atomic get-or-create plus pin under the cache bin lock. Eviction decisions
    // in invalidateIfIdle (computeIfPresent on the same bin) cannot slip between the lookup
    // and the pin, which closes the detach window that used to hand two live queues to one
    // key. The update also re-arms variable expiry to the pinned duration.
    PinnedTaskQueue created =
        queues
            .asMap()
            .compute(
                key,
                (k, existing) -> {
                  PinnedTaskQueue q = existing != null ? existing : createQueue(k);
                  q.pinForSubmit();
                  return q;
                });
    // Adaptive piggyback: slow-path submits (new keys / idle revivals, the only path
    // that grows cardinality) sample the size and sweep one capped scan while over the bound,
    // so a caller that never drives cleanUp still converges. The fast path (repeated
    // busy submits) never probes, so steady-state hot keys pay zero extra cost; one sweep
    // costs at most MAX_SWEEP_SCAN probes and never evicts a busy queue.
    maybeAutoSweepOnSubmit();
    return created;
  }

  @Override
  public void abortSubmit(String key, TaskQueue queue) {
    Keys.requireKey(key);
    Objects.requireNonNull(queue, "queue");
    // Identity-gated: a detached or foreign instance releases nothing, so a stale caller can
    // never drop the fence of the live entry. Pinned entries cannot be evicted concurrently
    // (expiry and sweep both skip busy queues), so a plain lookup is sufficient — no bin lock.
    if (queue instanceof PinnedTaskQueue pinned && queues.asMap().get(key) == queue) {
      pinned.abortSubmit();
    }
  }

  /**
   * Evicts idle entries until back under the soft bound. Never evicts a busy queue.
   *
   * @return actual number of entries evicted by this sweep
   */
  private int sweepIdle() {
    // Bounded scan (see MAX_SWEEP_SCAN): each iteration is one map probe, so one sweep costs
    // O(cap) regardless of map size. Busy entries consume scan budget too — otherwise a
    // busy-heavy map would still scan unboundedly without evicting.
    // Snapshot size once: estimatedSize sums striped counters, so re-reading it per iteration
    // would pay ~256 counter sums per sweep. Decrement locally on each successful eviction;
    // concurrent adds may make the snapshot stale low, which only stops the sweep early (safe).
    // Large overshoots converge across later sweeps (piggyback or cleanUp) and idle expiry
    // instead of stalling one caller with an O(N) scan.
    boolean measure = log.isDebugEnabled();
    long startNanos = measure ? System.nanoTime() : 0L;
    long size = queues.estimatedSize();
    int evicted = 0;
    int scanned = 0;
    for (String candidate : queues.asMap().keySet()) {
      if (size <= maximumSize || scanned >= MAX_SWEEP_SCAN) {
        break;
      }
      scanned++;
      if (invalidateIfIdle(candidate)) {
        size--;
        evicted++;
      }
    }
    if (measure) {
      log.debug(
          "Idle sweep scanned {} entries, evicted {} in {}ns",
          scanned,
          evicted,
          System.nanoTime() - startNanos);
    }
    return evicted;
  }

  /** Single owner of the over-bound WARN so the sizing recipe stays in one place. */
  private void warnOverBound(long size, String detail) {
    log.warn(
        "Task queue entries {} exceed soft bound {}; {}"
            + " (interval <= {} / new-keys-per-sec; overshoot needs ceil((size-bound)/{}) cleanups)",
        size,
        maximumSize,
        detail,
        MAX_SWEEP_SCAN,
        MAX_SWEEP_SCAN);
  }

  /** Sampled adaptive sweep for the submit slow path; capped scan, idle entries only. */
  private void maybeAutoSweepOnSubmit() {
    if (ThreadLocalRandom.current().nextInt(SUBMIT_WARN_SAMPLE) != 0) {
      return;
    }
    long size = queues.estimatedSize();
    if (size > maximumSize) {
      int evicted = sweepIdle();
      warnOverBound(size, "auto sweep evicted " + evicted);
    }
  }

  /**
   * Evicts the queue for {@code key} only when it is idle, atomically with respect to the cache
   * map.
   *
   * <p>Best-effort against concurrent submitters: a queue that becomes busy concurrently with the
   * remapping may still be retained, which is the safe direction (no ordering split). Never evicts
   * a queue observed busy inside the remapping.
   *
   * <p>The idleness check inside the remapping is lock-free ({@link PinnedTaskQueue#isPinned}): the
   * pin is maintained under the queue monitor, so reading it here avoids taking that monitor while
   * holding the cache bin lock. The pin deliberately includes an outstanding {@code pinForSubmit}
   * fence for a submission still in flight, while {@link TaskQueue#isIdle()} deliberately excludes
   * it: eviction must retain an entry with an enqueue on the way (no ordering split), whereas the
   * executor-selection idle hint must answer for the queue as of before the current submission
   * (otherwise the inline-sync shortcut could never fire, since every submission pins first).
   *
   * @param key ordering key
   * @return {@code true} if an entry was present and evicted
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws IllegalArgumentException if {@code key} is empty, blank, or too long
   */
  public boolean invalidateIfIdle(String key) {
    Keys.requireKey(key);
    // Atomic eviction decision under the cache bin lock: a concurrently-pinned queue is retained
    // (safe direction, no ordering split). The evicted flag is set only when this remapping
    // actually removes an idle entry. Inferring eviction by identity change (get != before across
    // three lookups) misreports true on concurrent evict+recreate: a new live instance differs
    // from before even though nothing was evicted by this call. This path is cold
    // (maintenance/cleanUp only, never submit), so one small holder allocation is negligible.
    boolean[] evicted = new boolean[1];
    queues
        .asMap()
        .computeIfPresent(
            key,
            (k, queue) -> {
              if (queue.isPinned()) {
                return queue;
              }
              evicted[0] = true;
              return null;
            });
    return evicted[0];
  }

  /**
   * Triggers maintenance such as expiration. Also runs one capped sweep past the soft bound.
   *
   * <p>Explicit synchronous trigger for the soft bound: submit slow paths already sweep adaptively
   * (sampled, same cap), so unmaintained providers still converge; call this explicitly under
   * sustained bursts or when a deterministic sweep is needed (idle expiry itself is additionally
   * driven by Caffeine's system scheduler, so unmaintained providers still converge by expiry).
   * Never evicts a busy queue.
   *
   * <p>Drive-interval sizing: one call evicts at most {@value #MAX_SWEEP_SCAN} idle entries, so
   * with a sustained new-key rate of {@code R} keys/sec drive this with interval {@code T <=
   * MAX_SWEEP_SCAN / R}; an overshoot of {@code D = size - bound} needs at least {@code ceil(D /
   * MAX_SWEEP_SCAN)} calls to converge (more when busy queues consume scan budget). The returned
   * eviction count plus the over-bound WARN tell the caller when to shrink {@code T}.
   *
   * @return actual number of idle entries evicted by this call's sweep ({@code 0} when already
   *     under the bound)
   */
  public int cleanUp() {
    long sizeBefore = queues.estimatedSize();
    int evicted = 0;
    if (sizeBefore > maximumSize) {
      // Cold maintenance path (over bound only): timing measured only for the WARN detail below.
      // sweepIdle already debug-gates its own scan timing; this outer timing stays unconditional
      // so the WARN detail keeps its elapsed time even when debug is off.
      long startNanos = System.nanoTime();
      evicted = sweepIdle();
      long elapsedNanos = System.nanoTime() - startNanos;
      warnOverBound(sizeBefore, "sweep evicted " + evicted + " in " + elapsedNanos + "ns");
    }
    queues.cleanUp();
    return evicted;
  }

  /**
   * Current entry count estimate; intended for monitoring and for sizing {@link #cleanUp()} drive
   * intervals. Pure getter with no logging side effect: call {@link #checkBoundAndWarn()} for the
   * explicit over-bound WARN checkpoint.
   *
   * @return estimated number of cached queues
   */
  public long estimatedSize() {
    return queues.estimatedSize();
  }

  /**
   * Returns the soft maximum cached queues; idle entries past this size are swept.
   *
   * @return soft maximum cached queues
   */
  public long getMaximumSize() {
    return maximumSize;
  }

  /**
   * Explicit over-bound checkpoint: logs a WARN and returns the current estimate when the soft
   * bound is exceeded, otherwise returns the estimate quietly. Call while polling {@link
   * #estimatedSize()} so a sustained overshoot without {@link #cleanUp()} driving stays observable.
   *
   * @return estimated number of cached queues
   */
  public long checkBoundAndWarn() {
    long size = queues.estimatedSize();
    if (size > maximumSize) {
      warnOverBound(size, "drive cleanUp periodically");
    }
    return size;
  }

  private PinnedTaskQueue createQueue(String key) {
    return new PinnedTaskQueue(this, key);
  }

  /** Queue that tracks busyness lock-free for the expiry callback. */
  private static final class PinnedTaskQueue extends TaskQueue {
    private final CaffeineTaskQueueProvider owner;

    private final String key;

    /**
     * Pin counters guarded by the queue monitor via the narrow {@code pinForSubmit}/{@code
     * syncPin}/{@code abortPin} helpers; {@code busy} stays volatile for lock-free maintenance
     * reads.
     */
    private final PinState pin = new PinState();

    private PinnedTaskQueue(CaffeineTaskQueueProvider owner, String key) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.key = key;
    }

    boolean isPinned() {
      return isPinned(pin);
    }

    @Override
    String describeQueue() {
      // Keys are application-controlled and only validated non-blank: neutralize line breaks
      // so one queue's label can never forge log lines (cold error-path only, no hot cost).
      return "PinnedTaskQueue[key='"
          + KeySanitizer.sanitize(key)
          + "']@"
          + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * Pins the entry for an upcoming submission via the narrow queue helper so the pin cannot race
     * with {@link #syncPinned()} clearing the flag for a drain that started before this submission
     * (which would let maintenance evict an instance with an enqueue still in flight). Idempotent
     * with respect to {@code busy}; the count is released in {@link #onEnqueueLocked()} once the
     * submission is actually enqueued.
     *
     * @return {@code true} when this call transitioned the queue from idle to pinned (the caller
     *     must re-arm variable expiry with a cache write); {@code false} when already pinned
     *     (expiry is already pinned, no write needed)
     */
    boolean pinForSubmit() {
      return super.pinForSubmit(pin);
    }

    @Override
    boolean onEnqueueLocked() {
      // Pessimistic pin in the same critical section as the enqueue (one monitor acquisition
      // instead of two): expiry maintenance can never observe an unpinned entry with in-flight
      // work. This also releases the fence taken by {@link #pinForSubmit()}, since the submission
      // has now reached the queue. The remaining get -> execute window is fenced earlier in {@link
      // CaffeineTaskQueueProvider#getForSubmit}, which pins on the fast path and atomically
      // pins plus re-arms expiry on the slow path. Pinning first keeps the safe (retained)
      // direction. Runs under the queue monitor: touches pin counters directly without
      // re-entering the monitor.
      boolean changed = !pin.busy;
      pin.busy = true;
      if (pin.pending > 0) {
        pin.pending--;
      }
      return changed;
    }

    @Override
    void onEnqueued(boolean changed) {
      if (changed) {
        owner.refreshExpiry(key);
      }
    }

    @Override
    void onEnqueueFailed() {
      // Pairs with the pinForSubmit fence taken by getForSubmit: the trigger never reached
      // onEnqueueLocked so the fence is still outstanding. Abort it so the key can idle-evict
      // again instead of staying pinned resident.
      abortSubmit();
    }

    @Override
    void onRunnerFinished() {
      // Reconcile the pessimistic pin after an inline runner death or scheduling failure.
      syncPinned();
    }

    /**
     * Releases one {@link #pinForSubmit()} fence for an abandoned submission. A consumed fence
     * (already paired with an enqueue) is a no-op: the count only drops when a submitter never
     * reached {@link TaskQueue#execute}.
     */
    void abortSubmit() {
      if (abortPin(pin)) {
        owner.refreshExpiry(key);
      }
    }

    @Override
    void run() {
      try {
        super.run();
      } finally {
        syncPinned();
      }
    }

    /**
     * Recomputes idleness and publishes the pin flag atomically via the narrow queue helper, so a
     * concurrent submitter cannot interleave a stale {@code false} over a fresh {@code true} (which
     * would briefly unpin an in-flight queue and allow idle eviction to split per-key ordering). An
     * outstanding {@link #pinForSubmit()} keeps the entry pinned until its enqueue lands. Re-arms
     * expiry only on idle&lt;-&gt;pinned transitions; staying pinned needs no write.
     */
    private void syncPinned() {
      if (syncPin(pin)) {
        owner.refreshExpiry(key);
      }
    }
  }

  private void refreshExpiry(String key) {
    // Atomic version-checked re-arm: get + put can resurrect an evicted entry (get returns
    // A, eviction/recreate installs B, put(A) puts back the stale A while B is already live
    // for the same key -> two live queues split FIFO). replace(K, current, current) only
    // writes when the same instance is still live, so it never resurrects and never splits.
    // A replace is a documented cache write, so expireAfterUpdate re-arms the same way put
    // does. No-op when absent or replaced.
    PinnedTaskQueue current = queues.asMap().get(key);
    if (current != null) {
      queues.asMap().replace(key, current, current);
    }
  }

  private static final class QueueExpiry implements Expiry<String, PinnedTaskQueue> {

    private final long idleNanos;

    private QueueExpiry(Duration expireAfterAccess) {
      // Beyond the nanos range (~292 years) Duration.toNanos throws ArithmeticException: compare
      // first so overflow saturates via an ordinary branch instead of exception control flow.
      // Saturates to the pinned duration (not Long.MAX_VALUE, which Caffeine would add to the
      // current time and overflow to negative), consistent with OrderedTraceExecutor.setTimeout.
      if (expireAfterAccess.compareTo(Duration.ofNanos(Long.MAX_VALUE)) >= 0) {
        this.idleNanos = PINNED_NANOS;
      } else {
        this.idleNanos = expireAfterAccess.toNanos();
      }
    }

    @Override
    public long expireAfterCreate(String key, PinnedTaskQueue queue, long currentTime) {
      return durationFor(queue);
    }

    @Override
    public long expireAfterUpdate(
        String key, PinnedTaskQueue queue, long currentTime, long currentDuration) {
      return durationFor(queue);
    }

    @Override
    public long expireAfterRead(
        String key, PinnedTaskQueue queue, long currentTime, long currentDuration) {
      // Reads (e.g. isIdle probes) must not re-arm the TTL:
      // returning the remaining duration keeps the original idle deadline instead of
      // pinning high-cardinality entries forever under monitoring.
      return currentDuration;
    }

    private long durationFor(PinnedTaskQueue queue) {
      // The cache only ever holds PinnedTaskQueue (see createQueue), so no defensive fallback:
      // unknown shapes would hide a construction bug instead of failing fast at the call site.
      // Both durations stay below Caffeine's MAXIMUM_EXPIRY (Long.MAX_VALUE >> 1, ~150 years):
      // PINNED_NANOS is 100 years and idleNanos saturates to it, so the returned duration never
      // exceeds the Caffeine safe value. now + duration may still wrap past Long.MAX_VALUE when
      // the ticker starts near MAX, but Caffeine compares expiry with (now - variableTime >= 0),
      // which stays correct across the wrap for durations below MAXIMUM_EXPIRY. Never saturate to
      // Long.MAX_VALUE - now here: near MAX that compresses a pinned 100-year TTL into
      // nanoseconds, letting a busy queue expire and splitting one key across two queue instances
      // (FIFO break).
      return queue.isPinned() ? PINNED_NANOS : idleNanos;
    }
  }
}
