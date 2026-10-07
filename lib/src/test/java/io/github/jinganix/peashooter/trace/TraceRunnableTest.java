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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceRunnable")
class TraceRunnableTest {

  @Test
  @DisplayName("should restore parent span after child task completes")
  void shouldRestoreParentSpanAfterChildTaskCompletes() {
    // Given
    DefaultTracer tracer = new DefaultTracer();
    Span parent = Span.child(tracer, null);
    tracer.setSpan(parent);
    TraceRunnable traceRunnable = new TraceRunnable(tracer, () -> {});

    // When
    traceRunnable.run();

    // Then
    assertThat(tracer.getSpan()).isEqualTo(parent);
  }

  @Test
  @DisplayName("should restore worker thread span instead of submitter parent")
  void shouldRestoreWorkerThreadSpanInsteadOfSubmitterParent() {
    // Given a runnable created on one span but executed on a thread with another span
    DefaultTracer tracer = new DefaultTracer();
    Span submitterParent = Span.child(tracer, null);
    tracer.setSpan(submitterParent);
    TraceRunnable runnable = new TraceRunnable(tracer, () -> {});
    Span workerPrevious = Span.child(tracer, null);
    tracer.setSpan(workerPrevious);

    // When
    runnable.run();

    // Then the worker thread must keep its own span, not the submitter parent
    assertThat(tracer.getSpan()).isEqualTo(workerPrevious);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should isolate beforeCall failure and still run delegate")
  void shouldRestoreSpanAndInvokeAfterCallWhenBeforeCallThrows() {
    // Given a tracer whose beforeCall always fails
    DefaultTracer tracer = new DefaultTracer();
    Span previous = Span.child(tracer, null);
    tracer.setSpan(previous);
    RuntimeException boom = new RuntimeException("beforeCall boom");
    java.util.concurrent.atomic.AtomicReference<Span> afterSpan =
        new java.util.concurrent.atomic.AtomicReference<>();
    io.github.jinganix.peashooter.Tracer recording =
        new io.github.jinganix.peashooter.Tracer() {
          @Override
          public Span getSpan() {
            return tracer.getSpan();
          }

          @Override
          public void setSpan(Span span) {
            tracer.setSpan(span);
          }

          @Override
          public void clearSpan() {
            tracer.clearSpan();
          }

          @Override
          public String nextTraceId() {
            return tracer.nextTraceId();
          }

          @Override
          public void beforeCall(Span span) {
            throw boom;
          }

          @Override
          public void afterCall(Span span, Throwable e) {
            afterSpan.set(span);
          }

          @Override
          public String nextSpanId() {
            return tracer.nextSpanId();
          }
        };
    java.util.concurrent.atomic.AtomicBoolean delegateRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    TraceRunnable runnable =
        new TraceRunnable(
            recording,
            () -> {
              delegateRan.set(true);
            });

    // When tracer fails, delegate still runs, span restored, afterCall invoked
    runnable.run();

    assertThat(delegateRan.get()).isTrue();
    assertThat(afterSpan.get()).isNotNull();
    assertThat(tracer.getSpan()).isEqualTo(previous);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should complete without error when delegate succeeds")
  void shouldCompleteWithoutErrorWhenDelegateSucceeds() {
    // When
    TraceRunnable traceRunnable = new TraceRunnable(new DefaultTracer(), () -> {});

    // Then
    assertThatCode(traceRunnable::run).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("should restore parent span when delegate throws error")
  void shouldRestoreParentSpanWhenDelegateThrowsError() {
    // Given
    DefaultTracer tracer = new DefaultTracer();
    Span parent = Span.child(tracer, null);
    tracer.setSpan(parent);
    TraceRunnable traceRunnable =
        new TraceRunnable(
            tracer,
            () -> {
              throw new OutOfMemoryError();
            });

    // When / Then
    assertThatThrownBy(traceRunnable::run).isInstanceOf(OutOfMemoryError.class);
    assertThat(tracer.getSpan()).isEqualTo(parent);
  }

  @Test
  @DisplayName("should suppress afterCall failure when delegate already failed")
  void shouldSuppressAfterCallFailureWhenDelegateAlreadyFailed() {
    // Given a delegate that fails and an afterCall that also fails
    DefaultTracer tracer = new DefaultTracer();
    RuntimeException delegateFailure = new RuntimeException("delegate boom");
    RuntimeException afterFailure = new RuntimeException("afterCall boom");
    io.github.jinganix.peashooter.Tracer recording =
        new io.github.jinganix.peashooter.Tracer() {
          @Override
          public Span getSpan() {
            return tracer.getSpan();
          }

          @Override
          public void setSpan(Span span) {
            tracer.setSpan(span);
          }

          @Override
          public void clearSpan() {
            tracer.clearSpan();
          }

          @Override
          public String nextTraceId() {
            return tracer.nextTraceId();
          }

          @Override
          public void beforeCall(Span span) {}

          @Override
          public void afterCall(Span span, Throwable e) {
            throw afterFailure;
          }

          @Override
          public String nextSpanId() {
            return tracer.nextSpanId();
          }
        };
    TraceRunnable runnable =
        new TraceRunnable(
            recording,
            () -> {
              throw delegateFailure;
            });

    // When / Then delegate failure wins, afterCall failure is suppressed
    try {
      runnable.run();
      assertThat(false).as("expected delegate failure").isTrue();
    } catch (RuntimeException e) {
      assertThat(e).isEqualTo(delegateFailure);
      assertThat(e.getSuppressed()).containsExactly(afterFailure);
    }
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should propagate delegate error when delegate fails")
  void shouldPropagateDelegateErrorWhenDelegateFails() { // Given
    RuntimeException exception = new RuntimeException();
    TraceRunnable traceRunnable =
        new TraceRunnable(
            new DefaultTracer(),
            () -> {
              throw exception;
            });

    // When / Then
    assertThatThrownBy(traceRunnable::run).isEqualTo(exception);
  }

  @Test
  @DisplayName("should rethrow afterCall failure when delegate succeeds")
  void shouldRethrowAfterCallFailureWhenDelegateSucceeds() {
    DefaultTracer tracer = new DefaultTracer();
    RuntimeException afterFailure = new RuntimeException("afterCall boom");
    io.github.jinganix.peashooter.Tracer recording =
        new io.github.jinganix.peashooter.Tracer() {
          @Override
          public Span getSpan() {
            return tracer.getSpan();
          }

          @Override
          public void setSpan(Span span) {
            tracer.setSpan(span);
          }

          @Override
          public void clearSpan() {
            tracer.clearSpan();
          }

          @Override
          public String nextTraceId() {
            return tracer.nextTraceId();
          }

          @Override
          public void beforeCall(Span span) {}

          @Override
          public void afterCall(Span span, Throwable e) {
            throw afterFailure;
          }

          @Override
          public String nextSpanId() {
            return tracer.nextSpanId();
          }
        };

    assertThatThrownBy(new TraceRunnable(recording, () -> {})::run).isEqualTo(afterFailure);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should wrap smuggled checked delegate failure but still report it to afterCall")
  void shouldReportSneakyCheckedDelegateFailureToAfterCall() {
    // Given a delegate smuggling a checked failure past the Runnable signature
    java.util.concurrent.atomic.AtomicReference<Throwable> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void afterCall(Span span, Throwable e) {
            seen.set(e);
            super.afterCall(span, e);
          }
        };
    java.io.IOException failure = new java.io.IOException("sneaky");
    TraceRunnable runnable = new TraceRunnable(tracer, () -> sneakyThrow(failure));

    // When / Then the failure surfaces wrapped (never masquerading as unchecked) while
    // afterCall still observes the original failure (not success)
    assertThatThrownBy(runnable::run)
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(failure);
    assertThat(seen.get()).isSameAs(failure);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should still run delegate when beforeCall sneaky-throws checked")
  void shouldStillRunDelegateWhenBeforeCallSneakyThrowsChecked() {
    // Given a tracer whose beforeCall smuggles a checked failure
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void beforeCall(Span span) {
            sneakyThrow(new java.io.IOException("beforeCall sneaky"));
          }
        };
    java.util.concurrent.atomic.AtomicBoolean delegateRan =
        new java.util.concurrent.atomic.AtomicBoolean();

    // When / Then the failure is isolated (logged) and the delegate still runs
    new TraceRunnable(tracer, () -> delegateRan.set(true)).run();
    assertThat(delegateRan.get()).isTrue();
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should restore previous span when span creation fails and delegate pollutes")
  void shouldRestorePreviousSpanWhenSpanCreationFailsAndDelegatePollutes() {
    // Given a previous span and a runnable whose span creation fails
    DefaultTracer tracer = new DefaultTracer();
    Span previous = Span.child(tracer, null);
    tracer.setSpan(previous);
    Span leak = Span.child(tracer, null);
    RuntimeException setupBoom = new RuntimeException("span boom");
    TraceRunnable runnable =
        new TraceRunnable(tracer, () -> tracer.setSpan(leak)) {
          @Override
          protected Span createSpan() {
            throw setupBoom;
          }
        };

    // When the untraced delegate pollutes the thread
    runnable.run();

    // Then the previous span is restored instead of leaking
    assertThat(tracer.getSpan()).isSameAs(previous);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should still run delegate when untraced snapshot read fails")
  void shouldStillRunDelegateWhenUntracedSnapshotReadFails() {
    // Given span creation failing plus a snapshot read failing with an ordinary failure
    java.util.concurrent.atomic.AtomicBoolean failSnapshot =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public Span getSpan() {
            if (failSnapshot.get()) {
              throw new RuntimeException("snapshot boom");
            }
            return super.getSpan();
          }
        };
    RuntimeException setupBoom = new RuntimeException("span boom");
    java.util.concurrent.atomic.AtomicBoolean delegateRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    TraceRunnable runnable =
        new TraceRunnable(tracer, () -> delegateRan.set(true)) {
          @Override
          protected Span createSpan() {
            throw setupBoom;
          }
        };
    failSnapshot.set(true);

    // When / Then the delegate still runs without a snapshot to restore
    runnable.run();
    assertThat(delegateRan.get()).isTrue();
  }

  @Test
  @DisplayName("should propagate delegate failure from untraced run when restore succeeds")
  void shouldPropagateDelegateFailureFromUntracedRunWhenRestoreSucceeds() {
    // Given span creation failing plus a delegate failing with the restore succeeding
    RuntimeException taskFailure = new RuntimeException("task boom");
    TraceRunnable runnable =
        new TraceRunnable(
            new DefaultTracer(),
            () -> {
              throw taskFailure;
            }) {
          @Override
          protected Span createSpan() {
            throw new RuntimeException("span boom");
          }
        };

    // When / Then the delegate failure propagates as-is
    assertThatThrownBy(runnable::run).isSameAs(taskFailure);
  }

  @Test
  @DisplayName("should suppress failing restore onto delegate failure in untraced run")
  void shouldSuppressFailingRestoreOntoDelegateFailureInUntracedRun() {
    // Given span creation failing, a failing delegate, and a restore failing too
    RuntimeException taskFailure = new RuntimeException("task boom");
    RuntimeException restoreFailure = new RuntimeException("restore boom");
    java.util.concurrent.atomic.AtomicBoolean failRestore =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (failRestore.get()) {
              throw restoreFailure;
            }
            super.setSpan(span);
          }
        };
    tracer.setSpan(Span.child(tracer, null));
    failRestore.set(true);
    TraceRunnable runnable =
        new TraceRunnable(
            tracer,
            () -> {
              throw taskFailure;
            }) {
          @Override
          protected Span createSpan() {
            throw new RuntimeException("span boom");
          }
        };

    // When / Then the delegate failure wins with the restore failure suppressed
    assertThatThrownBy(runnable::run).isSameAs(taskFailure).hasSuppressedException(restoreFailure);
  }

  @Test
  @DisplayName("should stay alive when restore fails after untraced delegate succeeds")
  void shouldStayAliveWhenRestoreFailsAfterUntracedDelegateSucceeds() {
    // Given span creation failing plus a restore failing after a successful delegate
    java.util.concurrent.atomic.AtomicBoolean failRestore =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (failRestore.get()) {
              throw new RuntimeException("restore boom");
            }
            super.setSpan(span);
          }
        };
    tracer.setSpan(Span.child(tracer, null));
    failRestore.set(true);
    java.util.concurrent.atomic.AtomicBoolean delegateRan =
        new java.util.concurrent.atomic.AtomicBoolean();
    TraceRunnable runnable =
        new TraceRunnable(tracer, () -> delegateRan.set(true)) {
          @Override
          protected Span createSpan() {
            throw new RuntimeException("span boom");
          }
        };

    // When / Then the restore failure is logged and the run completes normally
    assertThatCode(runnable::run).doesNotThrowAnyException();
    assertThat(delegateRan.get()).isTrue();
  }

  @Test
  @DisplayName("should not self-suppress when untraced restore rethrows delegate failure")
  void shouldNotSelfSuppressWhenUntracedRestoreRethrowsDelegateFailure() {
    // Given span creation failing, a delegate failing with X, and a restore rethrowing X
    RuntimeException shared = new RuntimeException("shared boom");
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void clearSpan() {
            sneakyThrow(shared);
          }
        };
    TraceRunnable runnable =
        new TraceRunnable(tracer, () -> sneakyThrow(shared)) {
          @Override
          protected Span createSpan() {
            throw new RuntimeException("span boom");
          }
        };

    // When / Then the shared failure propagates without self-suppression
    assertThatThrownBy(runnable::run).isSameAs(shared);
    assertThat(shared.getSuppressed()).isEmpty();
  }

  @Test
  @DisplayName("should restore interrupt status when untraced delegate is interrupted")
  void shouldRestoreInterruptStatusWhenUntracedDelegateIsInterrupted() {
    // Given span creation failing and a delegate sneaky-throwing InterruptedException
    DefaultTracer tracer = new DefaultTracer();
    InterruptedException interruption = new InterruptedException("delegate boom");
    TraceRunnable runnable =
        new TraceRunnable(tracer, () -> sneakyThrow(interruption)) {
          @Override
          protected Span createSpan() {
            throw new RuntimeException("span boom");
          }
        };

    // When / Then the interruption surfaces wrapped with the interrupt flag restored,
    // matching the traced path (TraceScope.executeSpan) and runUntraced
    assertThatThrownBy(runnable::run)
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(interruption);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    // Cleanup: the assertion above leaves the flag set on the pooled test thread
    Thread.interrupted();
  }

  @Test
  @DisplayName("should restore interrupt status when delegate is interrupted without span snapshot")
  void shouldRestoreInterruptStatusWhenDelegateIsInterruptedWithoutSpanSnapshot() {
    // Given span creation failing, a snapshot read failing, and an interrupted delegate
    java.util.concurrent.atomic.AtomicBoolean failSnapshot =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public Span getSpan() {
            if (failSnapshot.get()) {
              throw new RuntimeException("snapshot boom");
            }
            return super.getSpan();
          }
        };
    InterruptedException interruption = new InterruptedException("delegate boom");
    TraceRunnable runnable =
        new TraceRunnable(tracer, () -> sneakyThrow(interruption)) {
          @Override
          protected Span createSpan() {
            throw new RuntimeException("span boom");
          }
        };
    failSnapshot.set(true);

    // When / Then the interruption surfaces wrapped with the interrupt flag restored
    assertThatThrownBy(runnable::run)
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(interruption);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    // Cleanup: the assertion above leaves the flag set on the pooled test thread
    Thread.interrupted();
  }

  @Test
  @DisplayName("should clear polluted span when snapshot read fails and delegate pollutes")
  void shouldClearPollutedSpanWhenSnapshotReadFailsAndDelegatePollutes() {
    // Given span creation failing plus a snapshot read failing during the fallback,
    // with a delegate polluting the thread
    java.util.concurrent.atomic.AtomicBoolean failSnapshot =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public Span getSpan() {
            if (failSnapshot.get()) {
              throw new RuntimeException("snapshot boom");
            }
            return super.getSpan();
          }
        };
    Span leak = Span.child(new DefaultTracer(), null);
    TraceRunnable runnable =
        new TraceRunnable(tracer, () -> tracer.setSpan(leak)) {
          @Override
          protected Span createSpan() {
            throw new RuntimeException("span boom");
          }
        };
    failSnapshot.set(true);

    // When the delegate runs untraced and pollutes
    runnable.run();
    failSnapshot.set(false);

    // Then the polluted span is cleared instead of leaking into pooled threads
    assertThat(tracer.getSpan()).isNull();
  }

  @Test
  @DisplayName("should dispatch and rethrow setup Error like OrderedTraceRunnable")
  void shouldDispatchAndRethrowSetupErrorLikeOrdered() {
    // Given a tracer whose span ids fail with an Error
    AssertionError setupFailure = new AssertionError("id boom");
    DefaultTracer failingTracer =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw setupFailure;
          }
        };
    java.util.concurrent.atomic.AtomicReference<Throwable> rejected =
        new java.util.concurrent.atomic.AtomicReference<>();
    class NotingProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {}

      @Override
      public void rejected(Throwable cause) {
        rejected.set(cause);
      }
    }
    TraceRunnable runnable = new TraceRunnable(failingTracer, new NotingProbe());

    // When / Then the Error propagates (fail-fast, runner dies loudly) after completing
    // the future via RejectionAware, matching OrderedTraceRunnable.buildSpanOrFail
    assertThatThrownBy(runnable::run).isSameAs(setupFailure);
    assertThat(rejected.get()).isSameAs(setupFailure);
  }

  @Test
  @DisplayName("should not notify a rejection-aware delegate that already ran and threw Error")
  void shouldNotNotifyRejectionAwareDelegateThatAlreadyRanAndThrewError() {
    // Given a healthy tracer and a rejection-aware delegate that runs then throws Error
    DefaultTracer tracer = new DefaultTracer();
    AssertionError bodyFailure = new AssertionError("body boom");
    java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<Throwable> rejected =
        new java.util.concurrent.atomic.AtomicReference<>();
    class RanProbe implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
      @Override
      public void run() {
        ran.set(true);
        throw bodyFailure;
      }

      @Override
      public void rejected(Throwable cause) {
        rejected.set(cause);
      }
    }

    // When
    assertThatThrownBy(() -> new TraceRunnable(tracer, new RanProbe()).run()).isSameAs(bodyFailure);

    // Then the delegate ran, so its own Error must not be re-notified as a rejection
    assertThat(ran).isTrue();
    assertThat(rejected.get()).isNull();
  }

  @Test
  @DisplayName("should suppress throwing discard callback onto setup Error")
  void shouldSuppressThrowingDiscardCallbackOntoSetupError() {
    // Given a failing span setup with an Error and a discard callback that itself fails
    AssertionError setupFailure = new AssertionError("id boom");
    DefaultTracer failingTracer =
        new DefaultTracer() {
          @Override
          public String nextSpanId() {
            throw setupFailure;
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
    TraceRunnable runnable = new TraceRunnable(failingTracer, new ThrowingProbe());

    // When / Then the setup Error wins with the callback failure suppressed
    assertThatThrownBy(runnable::run)
        .isSameAs(setupFailure)
        .hasSuppressedException(callbackFailure);
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
    throw (E) throwable;
  }
}
