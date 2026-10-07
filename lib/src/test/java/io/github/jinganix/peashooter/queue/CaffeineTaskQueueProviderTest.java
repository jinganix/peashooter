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
import static io.github.jinganix.peashooter.utils.TestUtils.sleep;
import static io.github.jinganix.peashooter.utils.TestUtils.uncheckedRun;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.jinganix.peashooter.TaskQueueProvider;
import io.github.jinganix.peashooter.executor.DirectExecutor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CaffeineTaskQueueProvider")
class CaffeineTaskQueueProviderTest {

  @Test
  @DisplayName("should keep submit fence working under sweep contention")
  void shouldKeepSubmitFenceWorkingUnderSweepContention() throws Exception {
    // Given a size-bounded provider plus racing submitters past the bound
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5), 1);
    int threads = 16;
    int perThread = 500;
    AtomicInteger submitted = new AtomicInteger();
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final int slot = t;
      workers.add(
          new Thread(
              () -> {
                for (int i = 0; i < perThread; i++) {
                  provider.getForSubmit("contended-" + slot + "-" + i);
                  submitted.incrementAndGet();
                }
              }));
    }
    Thread sweeper =
        new Thread(
            () -> {
              for (int i = 0; i < 200; i++) {
                provider.cleanUp();
                sleep(5);
              }
            });

    // When submissions race concurrent cleanUp sweeps (plus the submit slow-path piggyback)
    sweeper.start();
    for (Thread worker : workers) {
      worker.start();
    }
    for (Thread worker : workers) {
      worker.join(30_000);
    }
    sweeper.join(30_000);

    // Then every submission got a queue: the fence never drops work under contention
    assertThat(submitted.get()).isEqualTo(threads * perThread);
  }

  /** Manual nanosecond clock driving {@link CaffeineTaskQueueProvider} expiry deterministically. */
  static final class ManualTicker implements com.github.benmanes.caffeine.cache.Ticker {

    private final java.util.concurrent.atomic.AtomicLong nanos =
        new java.util.concurrent.atomic.AtomicLong();

    @Override
    public long read() {
      return nanos.get();
    }

    void advance(Duration duration) {
      nanos.addAndGet(duration.toNanos());
    }
  }

  @Test
  @DisplayName("should reject null key on getForSubmit")
  void shouldRejectNullKeyOnGet() {
    // Given
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();

    // When / Then
    assertThatThrownBy(() -> provider.getForSubmit(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("key");
  }

  @Test
  @DisplayName("should reject blank key on getForSubmit")
  void shouldRejectBlankKeyOnGet() {
    // Given whitespace-only keys collapse routing and hide caller bugs
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();

    // When / Then
    assertThatThrownBy(() -> provider.getForSubmit(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
    assertThatThrownBy(() -> provider.getForSubmit("\t"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
  }

  @Test
  @DisplayName("should reject empty key on getForSubmit and invalidateIfIdle")
  void shouldRejectEmptyKeyOnGetAndInvalidateIfIdle() {
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();

    assertThatThrownBy(() -> provider.getForSubmit(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
    assertThatThrownBy(() -> provider.invalidateIfIdle(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should reject non-positive expiry")
  void shouldRejectNonPositiveExpiry() {
    assertThatThrownBy(() -> new CaffeineTaskQueueProvider(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CaffeineTaskQueueProvider(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CaffeineTaskQueueProvider(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CaffeineTaskQueueProvider(Duration.ZERO, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CaffeineTaskQueueProvider(null, 10))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CaffeineTaskQueueProvider(Duration.ofMinutes(5), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CaffeineTaskQueueProvider(
                    Duration.ofMinutes(5),
                    0,
                    com.github.benmanes.caffeine.cache.Ticker.systemTicker()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CaffeineTaskQueueProvider(
                    Duration.ofMinutes(5),
                    -1,
                    com.github.benmanes.caffeine.cache.Ticker.systemTicker()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should apply bounded default to every constructor without explicit bound")
  void shouldApplyBoundedDefaultToEveryConstructorWithoutExplicitBound() {
    // Given every constructor that does not take an explicit bound
    // When / Then each carries the safe bounded default instead of unbounded (0)
    assertThat(new CaffeineTaskQueueProvider().getMaximumSize())
        .isEqualTo(CaffeineTaskQueueProvider.DEFAULT_MAXIMUM_SIZE)
        .isGreaterThan(0);
    assertThat(new CaffeineTaskQueueProvider(Duration.ofMinutes(5)).getMaximumSize())
        .isEqualTo(CaffeineTaskQueueProvider.DEFAULT_MAXIMUM_SIZE)
        .isGreaterThan(0);
    assertThat(
            new CaffeineTaskQueueProvider(Duration.ofMinutes(5), new ManualTicker())
                .getMaximumSize())
        .isEqualTo(CaffeineTaskQueueProvider.DEFAULT_MAXIMUM_SIZE)
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("should reclaim default provider past the bound via cleanUp")
  void shouldReclaimDefaultProviderPastTheBoundViaCleanUp() {
    // Given a default-bounded provider driven over the default bound with drained idle keys
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    long bound = provider.getMaximumSize();
    assertThat(bound).isGreaterThan(0);
    int over = (int) bound + 1_000;
    for (int i = 0; i < over; i++) {
      TaskQueue idle = provider.getForSubmit("p0-5-" + i);
      idle.execute(DirectExecutor.INSTANCE, () -> {});
    }
    assertThat(provider.estimatedSize()).isGreaterThan(bound);

    // When maintenance sweeps repeatedly Then it converges back to the bound
    // (an unbounded default would return 0 and never reclaim)
    int evicted = 0;
    for (int i = 0; i < 50 && provider.estimatedSize() > bound; i++) {
      evicted += provider.cleanUp();
    }
    assertThat(evicted).isGreaterThan(0);
    assertThat(provider.estimatedSize()).isLessThanOrEqualTo(bound);
  }

  @Test
  @DisplayName("should bound cardinality after cleanUp past the soft bound")
  void shouldBoundCardinalityAfterCleanUpPastTheSoftBound() {
    // Given a soft bound of 10 with 30 drained (idle) keys over it: pin all entries first
    // (pinned entries are immune to sweeps, so the overshoot builds deterministically),
    // then drain each without further submits (execute never sweeps, so all 30 stay idle)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5), 10);
    List<TaskQueue> pinned = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      pinned.add(provider.getForSubmit("idle-" + i));
    }
    assertThat(provider.estimatedSize()).isEqualTo(30);
    for (TaskQueue idle : pinned) {
      idle.execute(DirectExecutor.INSTANCE, () -> {});
    }
    assertThat(provider.estimatedSize()).isGreaterThan(10);

    // When maintenance sweeps once
    provider.cleanUp();

    // Then the idle entries were reclaimed down to the soft bound
    assertThat(provider.estimatedSize()).isLessThanOrEqualTo(10);
  }

  @Test
  @DisplayName("should sweep only idle queues past the soft bound")
  void shouldSweepOnlyIdleQueuesPastTheSoftBound() {
    // Given a bound provider with one permanently busy key (executor never runs the command)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5), 4);
    TaskQueue busy = provider.getForSubmit("busy");
    busy.execute(r -> {}, () -> {});
    for (int i = 0; i < 50; i++) {
      // Drain to truly idle so the fence pin is released; bare getForSubmit stays fenced.
      TaskQueue idle = provider.getForSubmit("idle-" + i);
      idle.execute(DirectExecutor.INSTANCE, () -> {});
    }

    // When maintenance sweeps past the bound
    provider.cleanUp();

    // Then the busy queue is retained (no ordering split) and idle entries are reclaimed
    assertThat(provider.getForSubmit("busy")).isSameAs(busy);
    assertThat(provider.estimatedSize()).isLessThanOrEqualTo(4);
  }

  @Test
  @DisplayName("should converge toward the soft bound through submits alone")
  void shouldConvergeTowardSoftBoundThroughSubmitsAlone() {
    // Given a bound provider driven far over the soft bound with no maintenance
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5), 4);
    for (int i = 0; i < 4000; i++) {
      TaskQueue idle = provider.getForSubmit("idle-" + i);
      idle.execute(DirectExecutor.INSTANCE, () -> {});
    }

    // When no cleanUp ever runs Then the slow-path piggyback still reclaims idle entries, so
    // the size stays strictly below the submission count (at least one capped sweep is
    // sampled with overwhelming probability over 4000 submits)
    assertThat(provider.estimatedSize()).isLessThan(4000);

    // And one explicit cleanUp finishes the remainder down to the bound
    for (int i = 0; i < 40 && provider.estimatedSize() > 4; i++) {
      provider.cleanUp();
    }
    assertThat(provider.estimatedSize()).isLessThanOrEqualTo(4);
  }

  @Test
  @DisplayName("should cap reclaimed entries per explicit cleanUp")
  void shouldCapReclaimedEntriesPerExplicitCleanUp() {
    // Given a bound provider driven far over the soft bound with idle entries
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5), 4);
    for (int i = 0; i < 4000; i++) {
      TaskQueue idle = provider.getForSubmit("idle-" + i);
      idle.execute(DirectExecutor.INSTANCE, () -> {});
    }
    long sizeBefore = provider.estimatedSize();

    // When maintenance sweeps once Then it never pays more than one capped scan
    // (MAX_SWEEP_SCAN idle probes), no matter how far over the bound the map is
    int evicted = provider.cleanUp();
    assertThat(evicted).isLessThanOrEqualTo(256);
    assertThat(provider.estimatedSize()).isLessThanOrEqualTo(sizeBefore);
  }

  @Test
  @DisplayName("should warn and converge while sustained over bound")
  void shouldWarnAndConvergeWhileSustainedOverBound() {
    // Given a bounded provider driven far over the bound with no maintenance
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5), 4);
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(CaffeineTaskQueueProvider.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try {
      for (int i = 0; i < 4000; i++) {
        TaskQueue idle = provider.getForSubmit("over-" + i);
        idle.execute(DirectExecutor.INSTANCE, () -> {});
      }

      // When sustained over bound with no cleanUp: the piggyback converges instead of growing
      // without reclaim
      assertThat(provider.estimatedSize()).isLessThan(4000);

      // Then the over-bound piggyback warns so the overshoot stays observable
      assertThat(appender.list)
          .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
          .isNotEmpty();

      // And explicit cleanUps converge the remainder back to the bound
      for (int i = 0; i < 20 && provider.estimatedSize() > 4; i++) {
        provider.cleanUp();
      }
      assertThat(provider.estimatedSize()).isLessThanOrEqualTo(4);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("should keep estimatedSize free of logging side effects")
  void shouldKeepEstimatedSizeFreeOfLoggingSideEffects() {
    // Given a bounded provider driven over the bound with pinned (hence sweep-immune)
    // entries, so the overshoot builds deterministically without sampled reclaims
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5), 4);
    for (int i = 0; i < 10; i++) {
      provider.getForSubmit("idle-" + i);
    }
    assertThat(provider.estimatedSize()).isEqualTo(10);
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(CaffeineTaskQueueProvider.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try {
      // When polling the pure getter Then no WARN is emitted
      assertThat(provider.estimatedSize()).isGreaterThan(4);
      assertThat(
              appender.list.stream()
                  .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                  .count())
          .isEqualTo(0);

      // And the explicit checkpoint warns instead
      assertThat(provider.checkBoundAndWarn()).isGreaterThan(4);
      assertThat(appender.list)
          .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
          .isNotEmpty();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("should serve repeated busy submits from the fast path without a split")
  void shouldServeRepeatedBusySubmitsFromTheFastPathWithoutASplit() {
    // Given a fenced submission with the entry still pinned (hot key)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue first = provider.getForSubmit("hot");

    // When the same key submits again while busy Then the live instance is returned
    // (lock-free get plus pin, no bin lock, no expiry write) and stays fenced
    assertThat(provider.getForSubmit("hot")).isSameAs(first);
    assertThat(provider.invalidateIfIdle("hot")).isFalse();

    // And releasing both fences makes it evictable again
    provider.abortSubmit("hot", first);
    provider.abortSubmit("hot", first);
    assertThat(provider.invalidateIfIdle("hot")).isTrue();
  }

  @Test
  @DisplayName("should re-pin an idle entry through the slow path without a split")
  void shouldRePinAnIdleEntryThroughTheSlowPathWithoutASplit() {
    // Given a drained idle entry (fence released after inline execution)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue idle = provider.getForSubmit("key");
    idle.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(provider.isIdle("key")).isTrue();

    // When the same key submits again Then the slow path re-pins the live instance
    // (single bin-locked write that also re-arms expiry) instead of splitting
    assertThat(provider.getForSubmit("key")).isSameAs(idle);
    assertThat(provider.invalidateIfIdle("key")).isFalse();
    provider.abortSubmit("key", idle);
  }

  @Test
  @DisplayName("should re-arm expiry when an idle entry is resubmitted")
  void shouldReArmExpiryWhenAnIdleEntryIsResubmitted() {
    // Given an idle entry with a manual clock, drained to truly idle
    ManualTicker ticker = new ManualTicker();
    CaffeineTaskQueueProvider provider =
        new CaffeineTaskQueueProvider(Duration.ofMillis(300), ticker);
    TaskQueue queue = provider.getForSubmit("key");
    queue.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(provider.isIdle("key")).isTrue();

    // When the key resubmits near the old idle deadline Then expiry is re-armed to pinned
    ticker.advance(Duration.ofMillis(200));
    TaskQueue resubmitted = provider.getForSubmit("key");
    assertThat(resubmitted).isSameAs(queue);
    ticker.advance(Duration.ofMillis(200));
    provider.cleanUp();

    // Then the pinned entry survives past the original idle deadline (no ordering split)
    assertThat(provider.invalidateIfIdle("key")).isFalse();

    // And releasing the fence lets it expire again instead of staying pinned forever
    provider.abortSubmit("key", queue);
    ticker.advance(Duration.ofMillis(400));
    provider.cleanUp();
    assertThat(provider.isIdle("key")).isTrue();
    assertThat(provider.getForSubmit("key")).isNotSameAs(queue);
  }

  @Test
  @DisplayName("should saturate huge expiry instead of throwing ArithmeticException")
  void shouldSaturateHugeExpiryInsteadOfThrowingArithmeticException() {
    // Given a duration beyond nanos range (~292 years); toNanos() overflows
    // When / Then construction must saturate (like OrderedTraceExecutor.setTimeout)
    // instead of leaking a bare ArithmeticException
    assertThatCode(() -> new CaffeineTaskQueueProvider(Duration.ofDays(365_000)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("should retain busy queue beyond default size bound")
  void shouldRetainBusyQueueBeyondDefaultSizeBound() {
    // Given the default provider must not size-evict in-flight work
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue first = provider.getForSubmit("pinned");
    first.execute(r -> {}, () -> {});

    // When over 10k other idle keys are added (drained to truly idle)
    for (int i = 0; i < 11_000; i++) {
      TaskQueue other = provider.getForSubmit("other-" + i);
      other.execute(DirectExecutor.INSTANCE, () -> {});
    }
    provider.cleanUp();

    // Then the first queue instance is retained (no split-brain runner)
    assertThat(provider.getForSubmit("pinned")).isSameAs(first);
  }

  @Test
  @DisplayName("should never split a busy queue under concurrent submit and drain")
  void shouldNeverSplitABusyQueueUnderConcurrentSubmitAndDrain() throws InterruptedException {
    // Given a provider with a short idle timeout and a queue held busy by a blocked runner.
    // (The old shape used inline no-ops: the queue went fully idle between iterations, so a
    // 20ms expiry plus in-loop cleanUp could legitimately evict it and flake. A blocked runner
    // keeps the entry pinned, which is the actual no-split guarantee under test.)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMillis(20));
    TaskQueue first = provider.getForSubmit("hot");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch taskRunning = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    first.execute(
        pool,
        () -> {
          taskRunning.countDown();
          awaitCountDown(releaseTask);
        });
    assertThat(taskRunning.await(10, TimeUnit.SECONDS)).isTrue();

    // When concurrent submitters hammer get/execute/cleanUp while the runner stays pinned
    AtomicInteger splits = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(3);
    for (int i = 0; i < 3; i++) {
      pool.execute(
          () -> {
            try {
              for (int j = 0; j < 500; j++) {
                TaskQueue q = provider.getForSubmit("hot");
                if (q != first) {
                  splits.incrementAndGet();
                }
                q.execute(pool, () -> {});
                provider.cleanUp();
              }
            } finally {
              done.countDown();
            }
          });
    }
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

    // Then every lookup observed the pinned instance: no ordering split while work is in flight.
    // (No identity assertion after shutdown: idle expiry may legitimately evict.)
    assertThat(splits.get()).isEqualTo(0);
    releaseTask.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("should not extend idle expiry on read-only probes")
  void shouldNotExtendIdleExpiryOnReadOnlyProbes() {
    // Given an idle queue with a manual clock (wall-clock sleeps flake under load)
    ManualTicker ticker = new ManualTicker();
    CaffeineTaskQueueProvider provider =
        new CaffeineTaskQueueProvider(Duration.ofMillis(300), ticker);
    TaskQueue queue = provider.getForSubmit("key");
    queue.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(queue.isIdle()).isTrue();

    // When read-only probes interleave (isIdle health checks must not re-arm TTL)
    ticker.advance(Duration.ofMillis(100));
    assertThat(provider.isIdle("key")).isTrue();
    assertThat(provider.estimatedSize()).isEqualTo(1);
    ticker.advance(Duration.ofMillis(100));
    assertThat(provider.isIdle("key")).isTrue();
    assertThat(provider.estimatedSize()).isEqualTo(1);
    ticker.advance(Duration.ofMillis(200));
    provider.cleanUp();

    // Then the entry still expires on its original idle deadline instead of living forever:
    // an expired entry is already gone, so there is nothing left to invalidate
    assertThat(provider.invalidateIfIdle("key")).isFalse();
    assertThat(provider.isIdle("key")).isTrue();
  }

  @Test
  @DisplayName("should evict explicitly invalidated idle queue")
  void shouldEvictExplicitlyInvalidatedIdleQueue() { // Given a drained idle queue (fence released
    // after inline execution)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue first = provider.getForSubmit("key");
    first.execute(DirectExecutor.INSTANCE, () -> {});

    // When
    assertThat(provider.invalidateIfIdle("key")).isTrue();

    // Then a fresh queue instance is created
    assertThat(provider.isIdle("key")).isTrue();
    assertThat(provider.getForSubmit("key")).isNotSameAs(first);
  }

  @Test
  @DisplayName("should evict idle queue via invalidateIfIdle")
  void shouldEvictIdleQueueViaInvalidateIfIdle() {
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue first = provider.getForSubmit("key");
    first.execute(DirectExecutor.INSTANCE, () -> {});

    assertThat(provider.invalidateIfIdle("key")).isTrue();
    assertThat(provider.getForSubmit("key")).isNotSameAs(first);
  }

  @Test
  @DisplayName("should keep the submit fence when a runner drains while a submit is in flight")
  void shouldKeepTheSubmitFenceWhenARunnerDrainsWhileASubmitIsInFlight() {
    // Given a fenced submission whose runner drains while a second get->execute window is open
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue queue = provider.getForSubmit("key");
    queue.execute(DirectExecutor.INSTANCE, () -> provider.getForSubmit("key"));

    // When maintenance probes the entry after the runner drained
    // Then the open submit window still fences it against eviction (no ordering split)
    assertThat(provider.invalidateIfIdle("key")).isFalse();
    assertThat(provider.getForSubmit("key")).isSameAs(queue);
  }

  @Test
  @DisplayName("should fence getForSubmit instance against eviction before execute")
  void shouldFenceGetForSubmitInstanceAgainstEvictionBeforeExecute() {
    // Given a queue acquired for submission with no work enqueued yet: this is the
    // get -> execute window where an eviction used to detach the instance and split
    // per-key ordering across two live queues.
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue submitted = provider.getForSubmit("key");

    // When maintenance runs before the submitter enqueues
    // Then the fenced instance is retained and stays the live one
    assertThat(provider.invalidateIfIdle("key")).isFalse();
    assertThat(provider.getForSubmit("key")).isSameAs(submitted);
  }

  @Test
  @DisplayName("should return idle without creating on isIdle for absent key")
  void shouldReturnNullWithoutCreatingOnGetIfPresentForAbsentKey() {
    // Given a provider that never saw the key
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));

    // When / Then a read-only lookup creates nothing
    assertThat(provider.isIdle("absent")).isTrue();
    assertThat(provider.estimatedSize()).isEqualTo(0);
    assertThat(provider.isIdle("absent")).isTrue();
  }

  @Test
  @DisplayName("should not materialize entries on isIdle probes")
  void shouldNotMaterializeEntriesOnGetIfPresentProbes() {
    // Given a provider that never saw the key: read-only probes create nothing
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));

    // When / Then probes stay idle with no size pollution and no pin to reclaim
    assertThat(provider.isIdle("key")).isTrue();
    assertThat(provider.estimatedSize()).isEqualTo(0);
    assertThat(provider.invalidateIfIdle("key")).isFalse();
  }

  @Test
  @DisplayName("should retain busy queue on invalidateIfIdle")
  void shouldRetainBusyQueueOnInvalidateIfIdle() {
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue queue = provider.getForSubmit("busy");
    queue.execute(r -> {}, () -> {});

    assertThat(provider.invalidateIfIdle("busy")).isFalse();
    assertThat(provider.getForSubmit("busy")).isSameAs(queue);
  }

  @Test
  @DisplayName("should return false on invalidateIfIdle for missing key")
  void shouldReturnFalseOnInvalidateIfIdleForMissingKey() {
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));

    assertThat(provider.invalidateIfIdle("missing")).isFalse();
  }

  @Test
  @DisplayName("should reconcile pin across an executor handoff and release when drained")
  void shouldReconcilePinAcrossAnExecutorHandoffAndReleaseWhenDrained() {
    // Given a queue whose first task blocks while a second task lands on another executor: drain
    // then hands the runner off mid-queue, so the runner's finally observes non-empty tasks with a
    // live runner claim before the final drain clears it.
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    ExecutorService first = Executors.newSingleThreadExecutor();
    ExecutorService second = Executors.newSingleThreadExecutor();
    try {
      TaskQueue queue = provider.getForSubmit("handoff");
      CountDownLatch firstStarted = new CountDownLatch(1);
      CountDownLatch releaseFirst = new CountDownLatch(1);
      CountDownLatch secondDone = new CountDownLatch(1);
      queue.execute(
          first,
          () -> {
            firstStarted.countDown();
            uncheckedRun(releaseFirst::await);
          });
      awaitCountDown(firstStarted);
      queue.execute(second, secondDone::countDown);
      releaseFirst.countDown();
      awaitCountDown(secondDone);

      // Then the queue eventually drains and becomes reclaimable without an ordering split
      await().atMost(Duration.ofSeconds(5)).until(() -> provider.invalidateIfIdle("handoff"));
      assertThat(provider.isIdle("handoff")).isTrue();
    } finally {
      first.shutdownNow();
      second.shutdownNow();
    }
  }

  @Test
  @DisplayName("should retain same queue instance while a task is still running")
  void shouldRetainSameQueueInstanceWhileTaskIsStillRunning() {
    // Given a busy queue with idle expiry shorter than the running task
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMillis(50));
    String key = "pinned-identity";
    ExecutorService executor = Executors.newCachedThreadPool();
    try {
      TaskQueue first = provider.getForSubmit(key);
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      first.execute(
          executor,
          () -> {
            started.countDown();
            uncheckedRun(release::await);
          });
      awaitCountDown(started);

      // When idle expiry passes with maintenance running
      sleep(200);
      provider.cleanUp();

      // Then the same instance is retained (no ordering split)
      assertThat(provider.getForSubmit(key)).isSameAs(first);
      release.countDown();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("should not evict queue while a task is still running")
  void shouldNotEvictQueueWhileTaskIsStillRunning() {
    // Given
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMillis(50));
    String key = "key";
    Executor executor = newCachedThreadPool();
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);

    // When: one long-running task, then wait past idle expiry with no new lookups
    provider
        .getForSubmit(key)
        .execute(
            executor,
            () -> {
              firstStarted.countDown();
              int active = concurrent.incrementAndGet();
              maxConcurrent.updateAndGet(max -> Math.max(max, active));
              uncheckedRun(releaseFirst::await);
              concurrent.decrementAndGet();
            });
    awaitCountDown(firstStarted);
    sleep(200);
    provider.cleanUp();

    provider
        .getForSubmit(key)
        .execute(
            executor,
            () -> {
              int active = concurrent.incrementAndGet();
              maxConcurrent.updateAndGet(max -> Math.max(max, active));
              concurrent.decrementAndGet();
              secondDone.countDown();
            });
    releaseFirst.countDown();
    awaitCountDown(secondDone);

    // Then: same-key tasks must not overlap (two queues would run concurrently)
    assertThat(maxConcurrent).hasValue(1);
  }

  @Test
  @DisplayName("should release submit pin when submission is aborted")
  void shouldReleaseSubmitPinWhenSubmissionIsAborted() {
    // Given a submit fence with no subsequent execute (a caller that abandons the submission
    // after getForSubmit)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    TaskQueue queue = provider.getForSubmit("key");
    assertThat(provider.invalidateIfIdle("key")).isFalse();

    // When the abandoned submission is aborted
    provider.abortSubmit("key", queue);

    // Then the entry is idle-evictable again instead of pinned forever
    assertThat(provider.invalidateIfIdle("key")).isTrue();
  }

  @Test
  @DisplayName("should keep an abandoned submit fence pinned until an explicit abort")
  void shouldKeepAbandonedSubmitFencePinnedUntilExplicitAbort() {
    // Given a fence whose submission is abandoned (no execute and no abortSubmit)
    ManualTicker ticker = new ManualTicker();
    CaffeineTaskQueueProvider provider =
        new CaffeineTaskQueueProvider(Duration.ofMillis(300), ticker);
    TaskQueue abandoned = provider.getForSubmit("key");

    // When a later submission for the same key drains normally and time passes far beyond the
    // idle expiry, then maintenance runs
    TaskQueue later = provider.getForSubmit("key");
    later.execute(DirectExecutor.INSTANCE, () -> {});
    ticker.advance(Duration.ofMillis(10_000));
    provider.cleanUp();

    // Then the abandoned fence is not released implicitly by the later submission: the entry is
    // still present and still pinned, so it never idles out for that key
    assertThat(provider.estimatedSize()).isEqualTo(1);
    assertThat(provider.invalidateIfIdle("key")).isFalse();

    // And only an explicit abort releases it for idle eviction
    provider.abortSubmit("key", abandoned);
    assertThat(provider.invalidateIfIdle("key")).isTrue();
  }

  @Test
  @DisplayName("should sanitize line breaks in the queue log label")
  void shouldSanitizeLineBreaksInTheQueueLogLabel() {
    // Given an application-controlled key containing line breaks
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    TaskQueue queue = provider.getForSubmit("a\nforged\rline\u2028\u2029\u0085");
    try {
      // When / Then the log label keeps it on one line instead of forging log lines
      assertThat(queue.describeQueue()).doesNotContain("\n", "\r", "\u2028", "\u2029", "\u0085");
    } finally {
      provider.abortSubmit("a\nforged\rline\u2028\u2029\u0085", queue);
    }
  }

  @Test
  @DisplayName("should neutralize control characters and ANSI escapes in the queue log label")
  void shouldNeutralizeControlCharactersInTheQueueLogLabel() {
    // Given an application-controlled key with C0 controls, DEL, and an ANSI escape
    // (terminal injection: ESC sequences can clear screens or set titles via logs)
    String key = "a\u0000\u0007\u001B[2J\t\u007F";
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    TaskQueue queue = provider.getForSubmit(key);
    try {
      // When / Then no raw control character survives in the log label
      String label = queue.describeQueue();
      assertThat(label).doesNotContain("\u0000", "\u0007", "\u001B", "\t", "\u007F");
      for (int i = 0; i < label.length(); i++) {
        char c = label.charAt(i);
        assertThat(c < 0x20 && c != ' ').as("raw C0 control U+%04X", (int) c).isFalse();
      }
    } finally {
      provider.abortSubmit(key, queue);
    }
  }

  @Test
  @DisplayName("should ignore abort for a detached queue instance")
  void shouldIgnoreAbortForADetachedQueueInstance() {
    // Given a live entry and a foreign instance for the same key
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    provider.getForSubmit("key");
    TaskQueue foreign = new TaskQueue();

    // When / Then aborting with the detached instance is a no-op (live pin untouched)
    assertThatCode(() -> provider.abortSubmit("key", foreign)).doesNotThrowAnyException();
    assertThat(provider.invalidateIfIdle("key")).isFalse();
  }

  @Test
  @DisplayName("should ignore abort for a superseded pinned instance of the same key")
  void shouldIgnoreAbortForASupersededPinnedInstanceOfTheSameKey() {
    // Given a key whose pinned entry was evicted and recreated (old instance detached)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    TaskQueue old = provider.getForSubmit("key");
    provider.abortSubmit("key", old);
    assertThat(provider.invalidateIfIdle("key")).isTrue();
    TaskQueue live = provider.getForSubmit("key");

    // When aborting with the superseded instance (right type, wrong identity)
    // Then the live pin is untouched
    assertThatCode(() -> provider.abortSubmit("key", old)).doesNotThrowAnyException();
    assertThat(provider.invalidateIfIdle("key")).isFalse();
    assertThat(provider.getForSubmit("key")).isSameAs(live);
    provider.abortSubmit("key", live);
  }

  @Test
  @DisplayName("should tolerate abort with no outstanding pin and sweep of an empty map")
  void shouldTolerateAbortWithNoOutstandingPinAndSweepOfAnEmptyMap() {
    // Given a submission that was already aborted (no outstanding pin)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    TaskQueue queue = provider.getForSubmit("key");
    provider.abortSubmit("key", queue);

    // When aborting again Then it is a no-op and the entry stays evictable
    assertThatCode(() -> provider.abortSubmit("key", queue)).doesNotThrowAnyException();
    assertThat(provider.invalidateIfIdle("key")).isTrue();

    // And sweeping an empty bounded map is a no-op covering the zero-iteration path
    CaffeineTaskQueueProvider bounded = new CaffeineTaskQueueProvider(Duration.ofMinutes(5), 4);
    assertThatCode(bounded::cleanUp).doesNotThrowAnyException();
    assertThat(bounded.estimatedSize()).isZero();
  }

  @Test
  @DisplayName("should keep entry pinned while one of two submit fences is outstanding")
  void shouldKeepEntryPinnedWhileOneOfTwoSubmitFencesIsOutstanding() {
    // Given two outstanding submit fences for the same key
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    TaskQueue queue = provider.getForSubmit("key");
    provider.getForSubmit("key");

    // When only one fence is released Then the entry stays pinned (count, not boolean)
    provider.abortSubmit("key", queue);
    assertThat(provider.invalidateIfIdle("key")).isFalse();

    // And releasing the second fence makes it evictable again
    provider.abortSubmit("key", queue);
    assertThat(provider.invalidateIfIdle("key")).isTrue();
  }

  @Test
  @DisplayName("should keep entry pinned when aborting with queued backlog")
  void shouldKeepEntryPinnedWhenAbortingWithQueuedBacklog() {
    // Given a queue with backlog on an executor that never runs (fence consumed by the enqueue)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    TaskQueue queue = provider.getForSubmit("key");
    queue.execute(r -> {}, () -> {});
    assertThat(provider.invalidateIfIdle("key")).isFalse();

    // When aborting with no outstanding fence but backlog present
    // Then the pin computation still sees the non-empty queue (stays pinned, no expiry re-arm
    // crash) and aborting is a harmless no-op
    assertThatCode(() -> provider.abortSubmit("key", queue)).doesNotThrowAnyException();
    assertThat(provider.invalidateIfIdle("key")).isFalse();
  }

  @Test
  @DisplayName("should keep entry pinned when aborting while runner holds an empty queue")
  void shouldKeepEntryPinnedWhenAbortingWhileRunnerHoldsAnEmptyQueue() {
    // Given a runner that dequeued the only task and is still running it (queue empty,
    // claim held): the pin computation must observe the live claim, not just the deque
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider();
    TaskQueue queue = provider.getForSubmit("key");
    CountDownLatch taskEntered = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    CountDownLatch taskDone = new CountDownLatch(1);
    queue.execute(
        command -> {
          Thread thread = new Thread(command);
          thread.setDaemon(true);
          thread.start();
        },
        () -> {
          taskEntered.countDown();
          uncheckedRun(releaseTask::await);
          taskDone.countDown();
        });
    awaitCountDown(taskEntered);

    // When aborting with no outstanding fence while the claim is live
    // Then the entry stays pinned (empty deque alone must not read as idle)
    assertThatCode(() -> provider.abortSubmit("key", queue)).doesNotThrowAnyException();
    assertThat(provider.invalidateIfIdle("key")).isFalse();

    // And after the task finishes the entry becomes evictable again
    releaseTask.countDown();
    awaitCountDown(taskDone);
    await().atMost(Duration.ofSeconds(10)).until(() -> provider.invalidateIfIdle("key"));
  }

  @Test
  @DisplayName("should report busy queue as not idle through the fenced probe only")
  void shouldReportBusyQueueAsNotIdle() {
    // Given a busy queue with backlog on an executor that never runs
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue queue = provider.getForSubmit("busy");
    queue.execute(r -> {}, () -> {});

    // When / Then the read-only probe reports busy without handing out the queue
    assertThat(provider.isIdle("busy")).isFalse();
    provider.abortSubmit("busy", queue);

    // And a drained idle queue reports idle
    TaskQueue idle = provider.getForSubmit("idle");
    idle.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(provider.isIdle("idle")).isTrue();
  }

  @Test
  @DisplayName("should reject null and blank keys on isIdle")
  void shouldRejectNullAndBlankKeysOnIsIdle() {
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));

    assertThatThrownBy(() -> provider.isIdle(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> provider.isIdle("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.isIdle("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should never expose an unfenced queue through the idle probe")
  void shouldNeverExposeAnUnfencedQueueThroughTheIdleProbe() throws Exception {
    // Given the S1 bypass: a read-only probe used to leak the mutable queue, so a caller
    // could execute on a stale instance after idle eviction and split per-key ordering
    // across two live queues. The probe now returns only a boolean, so no unfenced
    // execute path exists.
    // When / Then no getIfPresent method remains on the provider interface or impl
    assertThat(
            Arrays.stream(TaskQueueProvider.class.getMethods())
                .noneMatch(m -> m.getName().equals("getIfPresent")))
        .isTrue();
    assertThat(
            Arrays.stream(CaffeineTaskQueueProvider.class.getMethods())
                .noneMatch(m -> m.getName().equals("getIfPresent")))
        .isTrue();

    // And eviction still replaces the live entry, reachable only via the fenced submit path
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue first = provider.getForSubmit("key");
    first.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(provider.isIdle("key")).isTrue();
    assertThat(provider.invalidateIfIdle("key")).isTrue();
    assertThat(provider.isIdle("key")).isTrue();
    assertThat(provider.getForSubmit("key")).isNotSameAs(first);
  }

  @Test
  @DisplayName("should expose maintenance and reclaim idle entries without external cleanUp")
  void shouldExposeMaintenanceAndReclaimIdleEntriesWithoutExternalCleanUp() throws Exception {
    // Given a default-shaped provider with short idle expiry, drained to truly idle
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMillis(50));
    TaskQueue queue = provider.getForSubmit("key");
    queue.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(provider.isIdle("key")).isTrue();

    // When viewed from outside the queue package: maintenance must be reachable
    // Then cleanUp/estimatedSize are public API (no external driver otherwise)
    CaffeineTaskQueueProvider.class.getMethod("cleanUp");
    CaffeineTaskQueueProvider.class.getMethod("estimatedSize");

    // And when writes stop with no caller driving maintenance
    // Then idle expiry still reclaims the entry via Caffeine's scheduler
    await().atMost(Duration.ofSeconds(10)).until(() -> provider.estimatedSize() == 0);
  }

  @Test
  @DisplayName("should re-arm expiry when an idle entry is enqueued directly")
  void shouldReArmExpiryWhenAnIdleEntryIsEnqueuedDirectly() {
    // Given an idle entry with a manual clock, drained to truly idle
    ManualTicker ticker = new ManualTicker();
    CaffeineTaskQueueProvider provider =
        new CaffeineTaskQueueProvider(Duration.ofMillis(300), ticker);
    TaskQueue queue = provider.getForSubmit("key");
    queue.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(provider.isIdle("key")).isTrue();
    ticker.advance(Duration.ofMillis(200));

    // When the idle entry is enqueued directly (no fresh getForSubmit) Then the enqueue re-arms
    // its variable expiry from the idle deadline to the pinned duration
    queue.execute(runnable -> {}, () -> {});

    // Then the entry survives past the original idle deadline instead of being reclaimed and
    // recreated for the same key (which would split per-key ordering)
    ticker.advance(Duration.ofMillis(800));
    provider.cleanUp();
    assertThat(provider.getForSubmit("key")).isSameAs(queue);
  }

  @Test
  @DisplayName("should roll back pinned fence when a fenced submit is aborted")
  void shouldRollBackPinnedFenceWhenAFencedSubmitIsAborted() {
    // Given a fenced submission with no enqueue yet (get -> execute window open)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue queue = provider.getForSubmit("hook-key");
    assertThat(provider.invalidateIfIdle("hook-key")).isFalse();
    assertThat(provider.estimatedSize()).isEqualTo(1);

    // When the fenced submit is aborted before reaching the queue
    // Then the entry is idle-evictable again instead of pinned forever
    provider.abortSubmit("hook-key", queue);
    assertThat(provider.isIdle("hook-key")).isTrue();
    assertThat(provider.invalidateIfIdle("hook-key")).isTrue();
    assertThat(provider.estimatedSize()).isEqualTo(0);
  }

  @Test
  @DisplayName("should keep pinned duration intact when nanoTime near max value")
  void shouldKeepPinnedDurationIntactWhenNanoTimeNearMaxValue() throws Exception {
    // Given a ticker near Long.MAX_VALUE (nanoTime origin is arbitrary, may start near MAX)
    java.util.concurrent.atomic.AtomicLong now =
        new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE - 1_000_000_000L);
    com.github.benmanes.caffeine.cache.Ticker ticker = now::get;
    CaffeineTaskQueueProvider provider =
        new CaffeineTaskQueueProvider(Duration.ofMinutes(5), ticker);
    TaskQueue busy = provider.getForSubmit("pinned-near-max");
    busy.execute(r -> {}, () -> {});
    long currentTime = ticker.read();

    // When the variable expiry is evaluated at that time
    Class<?> expiryClass =
        Class.forName("io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider$QueueExpiry");
    java.lang.reflect.Constructor<?> ctor = expiryClass.getDeclaredConstructor(Duration.class);
    ctor.setAccessible(true);
    Object expiry = ctor.newInstance(Duration.ofMinutes(5));
    Class<?> pinnedClass =
        Class.forName(
            "io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider$PinnedTaskQueue");
    java.lang.reflect.Method m =
        expiryClass.getMethod("expireAfterCreate", String.class, pinnedClass, long.class);
    m.setAccessible(true);
    long duration = (long) m.invoke(expiry, "pinned-near-max", busy, currentTime);

    // Then the pinned duration is returned unchanged (100 years, below Caffeine's
    // MAXIMUM_EXPIRY): saturating to Long.MAX_VALUE - currentTime would compress it into
    // ~1s and let the busy queue expire. The wrapped expiry time stays correct because
    // Caffeine compares with (now - variableTime >= 0).
    long pinnedNanos = Duration.ofDays(365 * 100).toNanos();
    assertThat(duration).isEqualTo(pinnedNanos);
    assertThat(duration).isLessThan(Long.MAX_VALUE >> 1);
    assertThat(currentTime - (currentTime + duration)).isEqualTo(-duration);
  }

  @Test
  @DisplayName("should keep busy queue on one instance when ticker near max value")
  void shouldKeepBusyQueueOnOneInstanceWhenTickerNearMaxValue() {
    // Given a busy queue with a ticker near Long.MAX_VALUE
    java.util.concurrent.atomic.AtomicLong now =
        new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE - 1_000_000_000L);
    com.github.benmanes.caffeine.cache.Ticker ticker = now::get;
    CaffeineTaskQueueProvider provider =
        new CaffeineTaskQueueProvider(Duration.ofMinutes(5), ticker);
    TaskQueue busy = provider.getForSubmit("pinned-near-max");
    busy.execute(r -> {}, () -> {});

    // When time advances past the idle timeout while work is still pinned
    now.addAndGet(Duration.ofMinutes(10).toNanos());
    provider.cleanUp();

    // Then the same instance serves the key: no idle eviction splits per-key FIFO
    assertThat(provider.getForSubmit("pinned-near-max")).isSameAs(busy);
    provider.abortSubmit("pinned-near-max", busy);
  }

  @Test
  @DisplayName("should keep live entry when expiry re-arm races eviction and recreation")
  void shouldKeepLiveEntryWhenExpiryReArmRacesEvictionAndRecreation() throws Exception {
    // Given a drained entry replaced by a new live instance for the same key
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue stale = provider.getForSubmit("race-key");
    stale.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(provider.isIdle("race-key")).isTrue();
    assertThat(provider.invalidateIfIdle("race-key")).isTrue();
    TaskQueue live = provider.getForSubmit("race-key");
    assertThat(live).isNotSameAs(stale);

    // When the stale re-arm observes the stale instance on get but the map holds live
    Field queuesField = CaffeineTaskQueueProvider.class.getDeclaredField("queues");
    queuesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Cache<String, Object> original = (Cache<String, Object>) queuesField.get(provider);
    Object realLive = original.asMap().get("race-key");
    assertThat(realLive).isSameAs(live);
    Object staleView = stale;
    @SuppressWarnings("unchecked")
    ConcurrentMap<String, Object> staleGetMap =
        (ConcurrentMap<String, Object>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ConcurrentMap.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("get") && args[0].equals("race-key")) {
                    return staleView;
                  }
                  return method.invoke(original.asMap(), args);
                });
    @SuppressWarnings("unchecked")
    Cache<String, Object> staleGetCache =
        (Cache<String, Object>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Cache.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("asMap")) {
                    return staleGetMap;
                  }
                  return method.invoke(original, args);
                });
    queuesField.set(provider, staleGetCache);
    try {
      java.lang.reflect.Method refresh =
          CaffeineTaskQueueProvider.class.getDeclaredMethod("refreshExpiry", String.class);
      refresh.setAccessible(true);
      refresh.invoke(provider, "race-key");

      // Then the live instance wins: no stale resurrection splits per-key FIFO
      assertThat(original.asMap().get("race-key")).isSameAs(live);
    } finally {
      queuesField.set(provider, original);
      provider.abortSubmit("race-key", live);
    }
  }

  @Test
  @DisplayName("should re-arm idle deadline through an explicit write on this caffeine version")
  void shouldReArmIdleDeadlineThroughExplicitWriteOnThisCaffeineVersion() {
    // Contract pinned to Caffeine 3.2.4: refreshExpiry must re-arm variable expiry with an
    // explicit same-instance replace (a documented write that triggers expireAfterUpdate).
    // Given a drained idle entry, When its deadline passes, Then it must expire (proof the
    // drain re-armed from the pinned duration back to the idle duration instead of sticking
    // at 100 years).
    ManualTicker ticker = new ManualTicker();
    CaffeineTaskQueueProvider provider =
        new CaffeineTaskQueueProvider(Duration.ofMillis(300), ticker);
    TaskQueue queue = provider.getForSubmit("contract-key");
    queue.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(provider.isIdle("contract-key")).isTrue();

    // Still alive before the idle deadline, gone after it.
    ticker.advance(Duration.ofMillis(200));
    provider.cleanUp();
    assertThat(provider.getForSubmit("contract-key")).isSameAs(queue);
    provider.abortSubmit("contract-key", queue);
    queue.execute(DirectExecutor.INSTANCE, () -> {});
    ticker.advance(Duration.ofMillis(400));
    provider.cleanUp();
    assertThat(provider.getForSubmit("contract-key")).isNotSameAs(queue);
  }

  @Test
  @DisplayName("should re-pin idle entry with a single cache write")
  void shouldRePinIdleEntryWithSingleCacheWrite() throws Exception {
    // Given a drained idle entry (fence released after inline execution)
    CaffeineTaskQueueProvider provider = new CaffeineTaskQueueProvider(Duration.ofMinutes(5));
    TaskQueue idle = provider.getForSubmit("key");
    idle.execute(DirectExecutor.INSTANCE, () -> {});
    assertThat(provider.isIdle("key")).isTrue();

    // Install a counting wrapper around the internal cache: the slow path uses compute;
    // refreshExpiry uses an explicit replace (never computeIfPresent), so no submit-path write
    // may go through computeIfPresent here.
    Field queuesField = CaffeineTaskQueueProvider.class.getDeclaredField("queues");
    queuesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Cache<String, Object> original = (Cache<String, Object>) queuesField.get(provider);
    AtomicInteger computeCount = new AtomicInteger();
    AtomicInteger computeIfPresentCount = new AtomicInteger();
    @SuppressWarnings("unchecked")
    ConcurrentMap<String, Object> countingMap =
        (ConcurrentMap<String, Object>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ConcurrentMap.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("compute")) {
                    computeCount.incrementAndGet();
                  }
                  if (method.getName().equals("computeIfPresent")) {
                    computeIfPresentCount.incrementAndGet();
                  }
                  return method.invoke(original.asMap(), args);
                });
    @SuppressWarnings("unchecked")
    Cache<String, Object> countingCache =
        (Cache<String, Object>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Cache.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("asMap")) {
                    return countingMap;
                  }
                  return method.invoke(original, args);
                });
    queuesField.set(provider, countingCache);
    try {
      // When the idle key resubmits (fast-path hit on an idle entry)
      TaskQueue resubmitted = provider.getForSubmit("key");

      // Then the live instance is re-pinned with a single bin-locked write that also
      // re-arms expiry: no optimistic pin + abort + extra refreshExpiry write.
      assertThat(resubmitted).isSameAs(idle);
      assertThat(computeCount.get()).isEqualTo(1);
      assertThat(computeIfPresentCount.get()).isEqualTo(0);
      assertThat(provider.invalidateIfIdle("key")).isFalse();
      provider.abortSubmit("key", idle);
    } finally {
      queuesField.set(provider, original);
    }
  }
}
