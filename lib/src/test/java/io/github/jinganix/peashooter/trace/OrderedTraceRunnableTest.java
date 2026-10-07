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

package io.github.jinganix.peashooter.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OrderedTraceRunnable")
class OrderedTraceRunnableTest {

  @Test
  @DisplayName("should create via factories without swapping traceId and key")
  void shouldCreateViaFactoriesWithoutSwappingTraceIdAndKey() {
    DefaultTracer tracer = new DefaultTracer();
    OrderedTraceRunnable byKey = OrderedTraceRunnable.forKey(tracer, "key", true, () -> {});
    OrderedTraceRunnable byTrace =
        OrderedTraceRunnable.forTraceId(
            tracer, "cccccccccccccccccccccccccccccccc", "key", true, () -> {});

    assertThat(((OrderedSpan) byKey.createSpan()).getKey()).isEqualTo("key");
    assertThat(byTrace.createSpan().getTraceId()).isEqualTo("cccccccccccccccccccccccccccccccc");
  }

  @Test
  @DisplayName("should force an explicit trace id even when parent is set")
  void shouldForceAnExplicitTraceIdEvenWhenParentIsSet() {
    // Given
    DefaultTracer tracer = new DefaultTracer();
    Span parent = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    tracer.setSpan(parent);
    AtomicReference<String> observed = new AtomicReference<>();

    // When
    OrderedTraceRunnable.forTraceId(
            tracer,
            "cccccccccccccccccccccccccccccccc",
            "key",
            true,
            () -> observed.set(tracer.getSpan().getTraceId()))
        .run();

    // Then
    assertThat(observed.get()).isEqualTo("cccccccccccccccccccccccccccccccc");
    assertThat(tracer.getSpan()).isEqualTo(parent);
  }

  @Test
  @DisplayName("should reject resubmission of the same single-use instance")
  void shouldRejectResubmissionOfTheSameSingleUseInstance() {
    DefaultTracer tracer = new DefaultTracer();
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", true, () -> {});
    runnable.run();

    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
  }

  @Test
  @DisplayName("should not notify delegate on sequential resubmission after a completed run")
  void shouldNotifyDelegateOnResubmissionSoWaitersFailFast() {
    // Given a completed instance whose delegate already ran (e.g. a future callback)
    DefaultTracer tracer = new DefaultTracer();
    AtomicReference<Throwable> rejected = new AtomicReference<>();
    class Probe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        rejected.set(cause);
      }
    }
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", true, new Probe());
    runnable.run();

    // When the same instance is resubmitted sequentially Then it still fails explicitly but
    // without a second dispatch: the delegate already ran, so notifying again would
    // double-notify non-idempotent delegates. Concurrent losers still dispatch (see
    // SequentialReuseNoDoubleNotifyTest); sequential reuse never does.
    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThat(rejected.get()).as("sequential reuse must not dispatch again").isNull();
  }

  @Test
  @DisplayName("should still fail on reuse when the discard callback throws")
  void shouldStillFailOnReuseWhenTheDiscardCallbackThrows() throws Exception {
    // Given an in-flight instance whose discard callback itself fails: the winner blocks in
    // its delegate while the loser races it, so the loser dispatches once for fail-fast.
    DefaultTracer tracer = new DefaultTracer();
    RuntimeException callbackFailure = new RuntimeException("callback boom");
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    class ThrowingProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {
        entered.countDown();
        try {
          if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("delegate not released");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }

      @Override
      public void rejected(Throwable cause) {
        throw callbackFailure;
      }
    }
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(tracer, "key", true, new ThrowingProbe());
    Thread winner =
        new Thread(
            () -> {
              try {
                runnable.run();
              } catch (Throwable ignored) {
                // Winner outcome is not under test; the loser assertion below carries the case.
              }
            });
    winner.start();
    assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    try {
      // When the loser races the in-flight winner Then reuse still throws, with the callback
      // failure suppressed instead of masking it
      assertThatThrownBy(runnable::run)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("single-use")
          .hasSuppressedException(callbackFailure);
    } finally {
      release.countDown();
      winner.join(10_000);
    }
  }

  @Test
  @DisplayName("should not self-suppress when the callback rethrows the reuse failure")
  void shouldNotSelfSuppressWhenTheCallbackRethrowsTheReuseFailure() throws Exception {
    // Given an in-flight instance whose callback rethrows the received failure itself: the
    // winner blocks in its delegate while the loser races it, so the loser dispatches once.
    DefaultTracer tracer = new DefaultTracer();
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    class RethrowingProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {
        entered.countDown();
        try {
          if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("delegate not released");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }

      @Override
      public void rejected(Throwable cause) {
        sneakyThrow(cause);
      }

      @SuppressWarnings("unchecked")
      private <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
      }
    }
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(tracer, "key", true, new RethrowingProbe());
    Thread winner =
        new Thread(
            () -> {
              try {
                runnable.run();
              } catch (Throwable ignored) {
                // Winner outcome is not under test; the loser assertion below carries the case.
              }
            });
    winner.start();
    assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    try {
      // When the loser races the in-flight winner Then reuse throws without self-suppression
      assertThatThrownBy(runnable::run)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("single-use")
          .hasNoSuppressedExceptions();
    } finally {
      release.countDown();
      winner.join(10_000);
    }
  }

  @Test
  @DisplayName("should propagate setup Error from runValue without wrapping")
  void shouldPropagateSetupErrorFromRunValueWithoutWrapping() {
    // Given a tracer failing span setup with an Error
    AssertionError setupFailure = new AssertionError("id boom");
    DefaultTracer failingTracer =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw setupFailure;
          }
        };

    // When / Then the Error propagates as-is, never wrapped
    assertThatThrownBy(() -> OrderedTraceRunnable.runValue(failingTracer, "key", () -> "x"))
        .isSameAs(setupFailure);
  }

  @Test
  @DisplayName("should wrap sneaky-checked setup failure from runValue")
  void shouldWrapSneakyCheckedSetupFailureFromRunValue() {
    // Given a tracer smuggling a checked failure past span creation
    java.io.IOException setupFailure = new java.io.IOException("id boom");
    DefaultTracer failingTracer =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            return sneakyThrowSetup(setupFailure);
          }

          @SuppressWarnings("unchecked")
          private <E extends Throwable, R> R sneakyThrowSetup(Throwable throwable) throws E {
            throw (E) throwable;
          }
        };

    // When / Then the checked setup failure wraps in CompletionException
    assertThatThrownBy(() -> OrderedTraceRunnable.runValue(failingTracer, "key", () -> "x"))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(setupFailure);
  }

  @Test
  @DisplayName("should build the span only once under concurrent createSpan")
  void shouldBuildTheSpanOnlyOnceUnderConcurrentCreateSpan() throws Exception {
    // Given a tracer whose id generation rendezvous inside the build. createSpan builds before
    // claiming (no observable READY(null)), so a concurrent racer may also build; exactly one
    // claim wins and the loser is rejected without publishing a half-built slot.
    java.util.concurrent.atomic.AtomicInteger builds =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            builds.incrementAndGet();
            entered.countDown();
            try {
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("build not released");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
            return super.nextSpanId();
          }
        };
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", true, () -> {});

    // When two threads race createSpan while both are parked inside the build
    java.util.concurrent.atomic.AtomicReference<Span> winner =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CopyOnWriteArrayList<Throwable> failures =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    Runnable attempt =
        () -> {
          try {
            winner.compareAndSet(null, runnable.createSpan());
          } catch (Throwable failure) {
            failures.add(failure);
          }
        };
    Thread first = new Thread(attempt);
    Thread second = new Thread(attempt);
    first.start();
    second.start();
    assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    release.countDown();
    first.join(10_000);
    second.join(10_000);

    // Then exactly one span wins and the loser is rejected as a duplicate submission
    assertThat(winner.get()).isNotNull();
    assertThat(failures)
        .hasSize(1)
        .first()
        .matches(
            failure ->
                failure instanceof IllegalStateException
                    && failure.getMessage().contains("single-use"));
    assertThat(builds.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("should consume a prebuilt span on rejection without double notification")
  void shouldConsumeAPrebuiltSpanOnRejectionWithoutDoubleNotification() {
    // Given an instance with a prebuilt span whose delegate counts notifications
    DefaultTracer tracer = new DefaultTracer();
    java.util.concurrent.atomic.AtomicInteger notifications =
        new java.util.concurrent.atomic.AtomicInteger();
    class CountingProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        notifications.incrementAndGet();
      }
    }
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(tracer, "key", false, new CountingProbe());
    runnable.createSpan();
    RuntimeException cause = new RuntimeException("discarded");

    // When rejected before run Then the prebuilt span is consumed with a single notification
    runnable.rejected(cause);
    runnable.rejected(cause);

    // And neither run nor createSpan can resurrect the consumed span id
    assertThat(notifications.get()).isEqualTo(1);
    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThatThrownBy(runnable::createSpan)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThat(notifications.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("should leave a failed createSpan retryable instead of wedging the instance")
  void shouldLeaveAFailedCreateSpanRetryableInsteadOfWedgingTheInstance() {
    // Given a tracer whose id generation always fails
    DefaultTracer brokenIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw new IllegalStateException("id boom");
          }
        };
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(brokenIds, "key", true, () -> {});

    // When createSpan fails Then the claim is released rather than stuck half-built
    assertThatThrownBy(runnable::createSpan)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("id boom");

    // And run still reaches setup (failing there) instead of misreporting a duplicate
    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("id boom");
  }

  @Test
  @DisplayName("should forbid repeated createSpan to avoid spanId reuse")
  void shouldForbidRepeatedCreateSpanToAvoidSpanIdReuse() {
    DefaultTracer tracer = new DefaultTracer();
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", true, () -> {});
    runnable.createSpan();

    assertThatThrownBy(runnable::createSpan)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
  }

  @Test
  @DisplayName("should reuse createSpan span on run without duplicate rejection")
  void shouldReuseCreateSpanSpanOnRunWithoutDuplicateRejection() {
    DefaultTracer tracer = new DefaultTracer();
    AtomicReference<Span> observed = new AtomicReference<>();
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(tracer, "key", true, () -> observed.set(tracer.getSpan()));
    Span created = runnable.createSpan();

    runnable.run();

    assertThat(observed.get()).isSameAs(created);
  }

  @Test
  @DisplayName("should forbid createSpan after run to avoid spanId reuse")
  void shouldForbidCreateSpanAfterRunToAvoidSpanIdReuse() {
    DefaultTracer tracer = new DefaultTracer();
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", true, () -> {});
    runnable.run();

    assertThatThrownBy(runnable::createSpan)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
  }

  @Test
  @DisplayName("should run checked supplier with typed failure propagation")
  void shouldRunCheckedSupplierWithTypedFailurePropagation() throws Exception {
    // Given
    DefaultTracer tracer = new DefaultTracer();

    // When / Then a result passes through with the span installed and restored
    String result =
        OrderedTraceRunnable.runChecked(tracer, "key", java.io.IOException.class, () -> "ok");
    assertThat(result).isEqualTo("ok");
    assertThat(tracer.getSpan()).isNull();

    // And a declared checked failure propagates with its static type (no CompletionException wrap)
    java.io.IOException failure = new java.io.IOException("checked boom");
    assertThatThrownBy(
            () ->
                OrderedTraceRunnable.runChecked(
                    tracer,
                    "key",
                    java.io.IOException.class,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(tracer.getSpan()).isNull();
  }

  @Test
  @DisplayName("should wrap foreign checked when runChecked smuggles undeclared type")
  void shouldWrapForeignCheckedWhenRunCheckedSmugglesUndeclaredType() {
    // Given a checked supplier declaring IOException but smuggling a foreign checked type
    DefaultTracer tracer = new DefaultTracer();
    class ForeignChecked extends Exception {
      ForeignChecked(String message) {
        super(message);
      }
    }
    ForeignChecked foreign = new ForeignChecked("foreign boom");

    // When the supplier smuggles a checked type unrelated to E
    // Then it surfaces wrapped as unchecked, never masquerading under E's static type
    assertThatThrownBy(
            () ->
                OrderedTraceRunnable.runChecked(
                    tracer, "key", java.io.IOException.class, () -> sneakyChecked(foreign)))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(foreign);
    assertThat(tracer.getSpan()).isNull();
  }

  @Test
  @DisplayName("should run unchecked supplier value with span installed and restored")
  void shouldRunUncheckedSupplierValueWithSpanInstalledAndRestored() {
    // Given a previous span on the calling thread
    DefaultTracer tracer = new DefaultTracer();
    Span previous = Span.child(tracer, null);
    tracer.setSpan(previous);

    // When / Then the value passes through with the child span installed and previous restored
    java.util.concurrent.atomic.AtomicReference<Span> observed =
        new java.util.concurrent.atomic.AtomicReference<>();
    String result =
        OrderedTraceRunnable.runValue(
            tracer,
            "key",
            () -> {
              observed.set(tracer.getSpan());
              return "ok";
            });
    assertThat(result).isEqualTo("ok");
    assertThat(observed.get()).isInstanceOf(OrderedSpan.class);
    assertThat(tracer.getSpan()).isSameAs(previous);

    // And unchecked failures propagate as-is
    RuntimeException failure = new RuntimeException("value boom");
    assertThatThrownBy(
            () ->
                OrderedTraceRunnable.runValue(
                    tracer,
                    "key",
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(tracer.getSpan()).isSameAs(previous);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should reject null arguments on runChecked")
  void shouldRejectNullArgumentsOnRunChecked() {
    DefaultTracer tracer = new DefaultTracer();
    assertThatThrownBy(
            () ->
                OrderedTraceRunnable.runChecked(null, "key", java.io.IOException.class, () -> null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                OrderedTraceRunnable.runChecked(
                    tracer, null, java.io.IOException.class, () -> null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> OrderedTraceRunnable.runChecked(tracer, "key", null, () -> null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> OrderedTraceRunnable.runChecked(tracer, "key", java.io.IOException.class, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                OrderedTraceRunnable.runChecked(tracer, " ", java.io.IOException.class, () -> null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should preserve interrupt status when runChecked smuggles InterruptedException")
  void shouldPreserveInterruptStatusWhenRunCheckedSmugglesInterruptedException() {
    // Given a clean interrupt flag
    Thread.interrupted();
    DefaultTracer tracer = new DefaultTracer();
    InterruptedException interrupted = new InterruptedException("boom");

    // When a checked supplier throws its declared InterruptedException Then it propagates as-is
    assertThatThrownBy(
            () ->
                OrderedTraceRunnable.runChecked(
                    tracer,
                    "key",
                    InterruptedException.class,
                    () -> {
                      throw interrupted;
                    }))
        .isSameAs(interrupted);

    // And the interrupt status is preserved for callers relying on Thread.interrupted()
    try {
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    assertThat(tracer.getSpan()).isNull();
  }

  @SuppressWarnings("unchecked")
  private static <R, E extends Throwable> R sneakyChecked(Throwable throwable) throws E {
    throw (E) throwable;
  }

  @Test
  @DisplayName("should forbid run after rejection to avoid spanId reuse")
  void shouldForbidRunAfterRejectionToAvoidSpanIdReuse() {
    DefaultTracer tracer = new DefaultTracer();
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", false, () -> {});
    runnable.rejected(new RuntimeException("discarded"));

    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThatThrownBy(runnable::createSpan)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
  }

  @Test
  @DisplayName("should notify delegate only once on repeated rejection")
  void shouldNotifyDelegateOnlyOnceOnRepeatedRejection() {
    // Given a runnable whose delegate counts rejection notifications
    DefaultTracer tracer = new DefaultTracer();
    java.util.concurrent.atomic.AtomicInteger notifications =
        new java.util.concurrent.atomic.AtomicInteger();
    class CountingProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        notifications.incrementAndGet();
      }
    }
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(tracer, "key", false, new CountingProbe());
    RuntimeException cause = new RuntimeException("discarded");

    // When rejected twice (e.g. a caller bug) Then only the first notifies
    runnable.rejected(cause);
    runnable.rejected(cause);

    // And the span id stays consumed exactly once
    assertThat(notifications.get()).isEqualTo(1);
    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
  }

  @Test
  @DisplayName("should not notify delegate again when run after rejection")
  void shouldNotNotifyDelegateAgainWhenRunAfterRejection() {
    // Given a rejected instance (delegate already notified once)
    DefaultTracer tracer = new DefaultTracer();
    java.util.concurrent.atomic.AtomicInteger notifications =
        new java.util.concurrent.atomic.AtomicInteger();
    class CountingProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        notifications.incrementAndGet();
      }
    }
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(tracer, "key", false, new CountingProbe());
    runnable.rejected(new RuntimeException("discarded"));

    // When run after rejection Then still fails explicitly but without a second notification
    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThat(notifications.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("should not self-suppress when discard callback rethrows setup failure")
  void shouldNotSelfSuppressWhenDiscardCallbackRethrowsSetupFailure() {
    // Given a failing span setup whose discard callback rethrows the received cause itself
    DefaultTracer brokenIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw new IllegalStateException("id boom");
          }
        };
    class RethrowingProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        sneakyRethrow(cause);
      }

      @SuppressWarnings("unchecked")
      private <E extends Throwable> void sneakyRethrow(Throwable throwable) throws E {
        throw (E) throwable;
      }
    }
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(brokenIds, "key", true, new RethrowingProbe());

    // When / Then the setup failure propagates without self-suppression
    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("id boom")
        .hasNoSuppressedExceptions();
  }

  @Test
  @DisplayName("should propagate setup Error unwrapped")
  void shouldPropagateSetupErrorUnwrapped() {
    // Given a tracer whose span ids fail with an Error
    DefaultTracer brokenIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw new AssertionError("id boom");
          }
        };
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(brokenIds, "key", true, () -> {});

    // When / Then the original Error surfaces, not a wrapper
    assertThatThrownBy(runnable::run).isInstanceOf(AssertionError.class).hasMessage("id boom");
  }

  @Test
  @DisplayName("should wrap sneaky setup failure in CompletionException")
  void shouldWrapSneakySetupFailureInCompletionException() {
    // Given a tracer smuggling a checked failure past its signature
    DefaultTracer brokenIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            return sneakyChecked(new java.io.IOException("sneaky id boom"));
          }
        };
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(brokenIds, "key", true, () -> {});

    // When / Then the smuggled failure surfaces wrapped (never masquerading as a task failure)
    assertThatThrownBy(runnable::run)
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCauseInstanceOf(java.io.IOException.class);
  }

  @Test
  @DisplayName("should suppress throwing discard callback onto setup failure")
  void shouldSuppressThrowingDiscardCallbackOntoSetupFailure() {
    // Given a failing span setup and a delegate whose discard callback itself fails
    DefaultTracer brokenIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw new IllegalStateException("id boom");
          }
        };
    RuntimeException callbackFailure = new RuntimeException("callback boom");
    class ThrowingProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        throw callbackFailure;
      }
    }
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(brokenIds, "key", true, new ThrowingProbe());

    // When / Then the setup failure wins with the callback failure suppressed, never masking it
    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("id boom")
        .hasSuppressedException(callbackFailure);
  }

  @Test
  @DisplayName("should notify once when span setup fails and run repeats")
  void shouldNotifyOnceWhenSpanSetupFailsAndRunRepeats() {
    // Given a tracer whose span setup always fails and a counting delegate
    DefaultTracer brokenIds =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw new IllegalStateException("id boom");
          }
        };
    java.util.concurrent.atomic.AtomicInteger notifications =
        new java.util.concurrent.atomic.AtomicInteger();
    class CountingProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        notifications.incrementAndGet();
      }
    }
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(brokenIds, "key", true, new CountingProbe());

    // When span setup fails Then the delegate is notified once with the setup cause
    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("id boom");
    assertThat(notifications.get()).isEqualTo(1);

    // And a second run still fails explicitly without a second notification
    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThat(notifications.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("should propagate Error from checked supplier without wrapping")
  void shouldPropagateErrorFromCheckedSupplierWithoutWrapping() {
    // Given a supplier failing fatally with an Error
    DefaultTracer tracer = new DefaultTracer();
    AssertionError failure = new AssertionError("checked error boom");

    // When / Then the original Error propagates unwrapped and the span is restored
    assertThatThrownBy(
            () ->
                OrderedTraceRunnable.runChecked(
                    tracer,
                    "key",
                    RuntimeException.class,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(tracer.getSpan()).isNull();
  }
}
