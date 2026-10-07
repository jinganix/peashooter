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

import io.github.jinganix.peashooter.Tracer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceScope")
class TraceScopeTest {

  /** Tracer that installs the span and then throws, simulating a notifying tracer bug. */
  static final class InstallThenThrowTracer implements Tracer {
    Span current;
    final RuntimeException failure = new RuntimeException("setSpan boom");
    final AtomicBoolean delegateRan = new AtomicBoolean();
    final AtomicReference<Throwable> afterCallSeen = new AtomicReference<>();

    InstallThenThrowTracer(Span previous) {
      this.current = previous;
    }

    @Override
    public Span getSpan() {
      return current;
    }

    @Override
    public void setSpan(Span span) {
      current = span;
      throw failure;
    }

    @Override
    public void clearSpan() {
      current = null;
    }

    @Override
    public String nextTraceId() {
      return TraceIds.nextTraceId();
    }

    @Override
    public String nextSpanId() {
      return TraceIds.nextSpanId();
    }

    @Override
    public void beforeCall(Span span) {}

    @Override
    public void afterCall(Span span, Throwable e) {
      afterCallSeen.set(e);
    }
  }

  @Test
  @DisplayName("should restore previous span when setSpan throws on run")
  void shouldRestorePreviousSpanWhenSetSpanThrowsOnRun() {
    // Given a thread with a previous span and a tracer that throws after installing
    InstallThenThrowTracer tracer =
        new InstallThenThrowTracer(Span.child(tracerPlaceholder(), null));
    Span previous = tracer.getSpan();
    AtomicBoolean delegateRan = new AtomicBoolean();

    // When the scope fails to install the new span
    assertThatThrownBy(
            () ->
                TraceScope.run(tracer, () -> Span.child(tracer, null), () -> delegateRan.set(true)))
        .isSameAs(tracer.failure);

    // Then the delegate never runs and the thread keeps its previous span, not the pollution
    assertThat(delegateRan.get()).isFalse();
    assertThat(tracer.getSpan()).isSameAs(previous);
  }

  @Test
  @DisplayName("should suppress a failing restore onto the setSpan failure")
  void shouldSuppressAFailingRestoreOntoTheSetSpanFailure() {
    // Given a previous span and a tracer that installs-then-throws a distinct failure
    // on every call after priming
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    Span previous = Span.child(new DefaultTracer(), null);
    Tracer flapping =
        new DefaultTracer() {
          boolean primed = false;

          @Override
          public void setSpan(Span span) {
            super.setSpan(span);
            if (!primed) {
              primed = true;
              return;
            }
            throw new RuntimeException("setSpan boom " + calls.getAndIncrement());
          }
        };
    flapping.setSpan(previous);

    // When installing a new span fails and the restore fails differently
    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> TraceScope.run(flapping, () -> Span.child(flapping, null), () -> {}));

    // Then the original failure propagates with the restore failure suppressed, and the
    // previous span (installed by the best-effort restore before it threw) is retained
    assertThat(thrown).hasMessage("setSpan boom 0");
    assertThat(thrown.getSuppressed()).hasSize(1);
    assertThat(thrown.getSuppressed()[0]).hasMessage("setSpan boom 1");
    assertThat(flapping.getSpan()).isSameAs(previous);
  }

  @Test
  @DisplayName("should suppress a failing restore onto the task failure")
  void shouldSuppressAFailingRestoreOntoTheTaskFailure() {
    // Given a previous span and a tracer whose final restore throws
    Span previous = Span.child(new DefaultTracer(), null);
    RuntimeException taskFailure = new RuntimeException("task boom");
    RuntimeException restoreFailure = new RuntimeException("restore boom");
    AtomicBoolean armed = new AtomicBoolean();
    Tracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (armed.get() && span == previous) {
              throw restoreFailure;
            }
            super.setSpan(span);
          }
        };
    tracer.setSpan(previous);
    armed.set(true);

    // When the delegate fails and the final restore also fails
    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                TraceScope.run(
                    tracer,
                    () -> Span.child(tracer, null),
                    () -> {
                      throw taskFailure;
                    }));

    // Then the task failure wins with the restore failure suppressed instead of masked
    assertThat(thrown).isSameAs(taskFailure);
    assertThat(taskFailure.getSuppressed()).contains(restoreFailure);
  }

  @Test
  @DisplayName("should propagate a failing restore when the task succeeds")
  void shouldPropagateAFailingRestoreWhenTheTaskSucceeds() {
    // Given a previous span and a tracer whose final restore throws after a successful task
    Span previous = Span.child(new DefaultTracer(), null);
    RuntimeException restoreFailure = new RuntimeException("restore boom");
    AtomicBoolean armed = new AtomicBoolean();
    Tracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (armed.get() && span == previous) {
              throw restoreFailure;
            }
            super.setSpan(span);
          }
        };
    tracer.setSpan(previous);
    armed.set(true);

    // When the task succeeds but the restore fails
    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> TraceScope.run(tracer, () -> Span.child(tracer, null), () -> {}));

    // Then the restore failure propagates (there is no task failure to carry it)
    assertThat(thrown).isSameAs(restoreFailure);
  }

  @Test
  @DisplayName("should suppress a failing restore onto an afterCall failure")
  void shouldSuppressAFailingRestoreOntoAnAfterCallFailure() {
    // Given a previous span, a successful task, and both afterCall and the final restore failing
    Span previous = Span.child(new DefaultTracer(), null);
    RuntimeException afterFailure = new RuntimeException("afterCall boom");
    RuntimeException restoreFailure = new RuntimeException("restore boom");
    AtomicBoolean armed = new AtomicBoolean();
    Tracer tracer =
        new DefaultTracer() {
          @Override
          public void afterCall(Span span, Throwable e) {
            throw afterFailure;
          }

          @Override
          public void setSpan(Span span) {
            if (armed.get() && span == previous) {
              throw restoreFailure;
            }
            super.setSpan(span);
          }
        };
    tracer.setSpan(previous);
    armed.set(true);

    // When the task succeeds but both callbacks fail
    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> TraceScope.run(tracer, () -> Span.child(tracer, null), () -> {}));

    // Then the afterCall failure wins with the restore failure suppressed
    assertThat(thrown).isSameAs(afterFailure);
    assertThat(afterFailure.getSuppressed()).contains(restoreFailure);
  }

  @Test
  @DisplayName("should keep previous span when setSpan succeeds on restore")
  void shouldKeepPreviousSpanWhenSetSpanSucceedsOnRestore() {
    // Given a previous span and a tracer that fails only the next install
    Span previous = Span.child(new DefaultTracer(), null);
    Tracer flaky =
        new DefaultTracer() {
          boolean failed = false;

          @Override
          public void setSpan(Span span) {
            super.setSpan(span);
            if (!failed && span != previous) {
              failed = true;
              throw new RuntimeException("setSpan boom");
            }
          }
        };
    flaky.setSpan(previous);

    // When installing a new span fails but the restore succeeds
    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> TraceScope.run(flaky, () -> Span.child(flaky, null), () -> {}));

    // Then the original failure propagates with no suppression and the previous span retained
    assertThat(thrown).hasMessage("setSpan boom");
    assertThat(thrown.getSuppressed()).isEmpty();
    assertThat(flaky.getSpan()).isSameAs(previous);
  }

  @Test
  @DisplayName("should restore previous span when setSpan throws on call")
  void shouldRestorePreviousSpanWhenSetSpanThrowsOnCall() throws Exception {
    // Given a thread with a previous span and a tracer that throws after installing
    InstallThenThrowTracer tracer =
        new InstallThenThrowTracer(Span.child(tracerPlaceholder(), null));
    Span previous = tracer.getSpan();
    AtomicBoolean delegateRan = new AtomicBoolean();

    // When the scope fails to install the new span
    assertThatThrownBy(
            () ->
                TraceScope.call(
                    tracer,
                    () -> Span.child(tracer, null),
                    () -> {
                      delegateRan.set(true);
                      return null;
                    }))
        .isSameAs(tracer.failure);

    // Then the delegate never runs and the thread keeps its previous span, not the pollution
    assertThat(delegateRan.get()).isFalse();
    assertThat(tracer.getSpan()).isSameAs(previous);
  }

  @Test
  @DisplayName("should run delegate in a fresh span")
  void shouldRunDelegateInAFreshSpan() {
    // Given a tracer with no current span
    DefaultTracer tracer = new DefaultTracer();
    AtomicBoolean delegateRan = new AtomicBoolean();

    // When a scope runs normally Then the delegate runs inside the new span
    AtomicReference<Span> observed = new AtomicReference<>();
    TraceScope.run(
        tracer,
        () -> Span.child(tracer, null),
        () -> {
          observed.set(tracer.getSpan());
          delegateRan.set(true);
        });

    // And the previous (empty) state is restored afterwards
    assertThat(delegateRan.get()).isTrue();
    assertThat(observed.get()).isNotNull();
    assertThat(tracer.getSpan()).isNull();
  }

  @Test
  @DisplayName("should run delegate untraced when setSpan throws on runLenient")
  void shouldRunDelegateUntracedWhenSetSpanThrowsOnRunLenient() {
    // Given a thread with a previous span and a tracer that throws after installing
    InstallThenThrowTracer tracer =
        new InstallThenThrowTracer(Span.child(tracerPlaceholder(), null));
    Span previous = tracer.getSpan();
    AtomicBoolean delegateRan = new AtomicBoolean();

    // When the lenient scope fails to install the new span Then nothing propagates
    TraceScope.runLenient(tracer, () -> Span.child(tracer, null), () -> delegateRan.set(true));

    // And the delegate ran untraced while the thread kept its previous span, not the pollution
    assertThat(delegateRan.get()).isTrue();
    assertThat(tracer.getSpan()).isSameAs(previous);
  }

  @Test
  @DisplayName("should preserve interrupt status on runLenient untraced fallback")
  void shouldPreserveInterruptStatusOnRunLenientUntracedFallback() {
    // Given a clean interrupt flag and a tracer that refuses every install
    Thread.interrupted();
    InstallThenThrowTracer tracer =
        new InstallThenThrowTracer(Span.child(tracerPlaceholder(), null));

    // When the untraced delegate smuggles InterruptedException past the Runnable signature
    // Then it surfaces wrapped (never masquerading as an unchecked failure)
    InterruptedException interruption = new InterruptedException("boom");
    assertThatThrownBy(
            () ->
                TraceScope.runLenient(
                    tracer,
                    () -> Span.child(tracer, null),
                    () -> {
                      throw sneakyThrow(interruption);
                    }))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(interruption);

    // Then the interrupt status is preserved like on the traced path
    try {
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("should preserve interrupt status on wrapped interrupt in untraced fallback")
  void shouldPreserveInterruptStatusOnWrappedInterruptInUntracedFallback() {
    // Given a clean flag and a tracer that forces the untraced fallback
    Thread.interrupted();
    InstallThenThrowTracer tracer =
        new InstallThenThrowTracer(Span.child(tracerPlaceholder(), null));

    // When the delegate throws a wrapped interrupt Then the flag is still restored
    InterruptedException interruption = new InterruptedException("boom");
    java.util.concurrent.CompletionException wrapped =
        new java.util.concurrent.CompletionException(interruption);
    assertThatThrownBy(
            () ->
                TraceScope.runLenient(
                    tracer,
                    () -> Span.child(tracer, null),
                    () -> {
                      throw wrapped;
                    }))
        .isSameAs(wrapped);

    try {
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("should preserve interrupt status when Runnable smuggles InterruptedException")
  void shouldPreserveInterruptStatusWhenRunnableSmugglesInterruptedException() {
    // Given a clean interrupt flag
    Thread.interrupted();
    DefaultTracer tracer = new DefaultTracer();

    // When a Runnable smuggles InterruptedException past its signature (clearing nothing
    // itself, but the scope must still restore the flag like the Callable path does)
    // Then it surfaces wrapped instead of masquerading as an unchecked failure
    InterruptedException interruption = new InterruptedException("boom");
    assertThatThrownBy(
            () ->
                TraceScope.run(
                    tracer,
                    () -> Span.child(tracer, null),
                    () -> {
                      throw sneakyThrow(interruption);
                    }))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(interruption);

    // Then the interrupt status is preserved for callers relying on Thread.interrupted()
    try {
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("should restore previous when lenient install fails after pollution")
  void shouldRestorePreviousWhenLenientInstallFailsAfterPollution() {
    // Given a tracer failing the first install but allowing later sets
    Span previous = Span.child(new DefaultTracer(), null);
    Span leak = Span.child(new DefaultTracer(), null);
    java.util.concurrent.atomic.AtomicBoolean failNext =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (failNext.getAndSet(false)) {
              super.setSpan(span);
              throw new RuntimeException("install boom");
            }
            super.setSpan(span);
          }
        };
    tracer.setSpan(previous);
    failNext.set(true);

    // When lenient install fails and the untraced delegate pollutes the thread
    TraceScope.runLenient(tracer, () -> Span.child(tracer, null), () -> tracer.setSpan(leak));

    // Then the previous span is restored instead of leaking delegate pollution
    assertThat(tracer.getSpan()).isSameAs(previous);
  }

  @Test
  @DisplayName("should install prebuilt span via Span overload and restore afterwards")
  void shouldInstallPrebuiltSpanViaSpanOverloadAndRestoreAfterwards() {
    // Given a previous span and an eagerly built span
    DefaultTracer tracer = new DefaultTracer();
    Span previous = Span.child(tracer, null);
    tracer.setSpan(previous);
    Span prebuilt = Span.child(tracer, null);
    java.util.concurrent.atomic.AtomicReference<Span> observed =
        new java.util.concurrent.atomic.AtomicReference<>();

    // When running lenient with the prebuilt span (no factory lambda)
    TraceScope.runLenient(tracer, prebuilt, () -> observed.set(tracer.getSpan()));

    // Then the delegate saw the prebuilt span and the previous span was restored
    assertThat(observed.get()).isSameAs(prebuilt);
    assertThat(tracer.getSpan()).isSameAs(previous);
    tracer.clearSpan();
  }

  @Test
  @DisplayName("should run delegate untraced when getSpan throws on runLenient")
  void shouldRunDelegateUntracedWhenGetSpanThrowsOnRunLenient() {
    // Given a tracer whose getSpan throws
    RuntimeException getFailure = new RuntimeException("getSpan boom");
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public Span getSpan() {
            throw getFailure;
          }
        };
    AtomicBoolean delegateRan = new AtomicBoolean();

    // When lenient setup reads previous span Then delegate still runs untraced
    TraceScope.runLenient(
        tracer, () -> Span.child(new DefaultTracer(), null), () -> delegateRan.set(true));

    // Then nothing propagates and delegate ran
    assertThat(delegateRan.get()).isTrue();
  }

  @Test
  @DisplayName("should run delegate untraced when spanFactory throws on runLenient")
  void shouldRunDelegateUntracedWhenSpanFactoryThrowsOnRunLenient() {
    // Given a healthy tracer but a throwing span factory
    DefaultTracer tracer = new DefaultTracer();
    RuntimeException factoryFailure = new RuntimeException("factory boom");
    AtomicBoolean delegateRan = new AtomicBoolean();

    // When lenient setup builds span Then delegate still runs untraced
    TraceScope.runLenient(
        tracer,
        () -> {
          throw factoryFailure;
        },
        () -> delegateRan.set(true));

    // Then nothing propagates and delegate ran
    assertThat(delegateRan.get()).isTrue();
  }

  @Test
  @DisplayName("should validate span before getSpan side effect on prebuilt overload")
  void shouldValidateSpanBeforeGetSpanSideEffectOnPrebuiltOverload() {
    // Given a tracer whose getSpan throws
    RuntimeException getFailure = new RuntimeException("getSpan boom");
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public Span getSpan() {
            throw getFailure;
          }
        };

    // When span is null Then NPE for span wins over getSpan failure
    assertThatThrownBy(() -> TraceScope.runLenient(tracer, (Span) null, () -> {}))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("span");
  }

  @Test
  @DisplayName("should run prebuilt delegate untraced when getSpan throws")
  void shouldRunPrebuiltDelegateUntracedWhenGetSpanThrows() {
    // Given a tracer whose getSpan throws and an eagerly built span
    RuntimeException getFailure = new RuntimeException("getSpan boom");
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public Span getSpan() {
            throw getFailure;
          }
        };
    Span prebuilt = Span.child(new DefaultTracer(), null);
    AtomicBoolean delegateRan = new AtomicBoolean();

    // When lenient setup reads previous span Then delegate still runs untraced
    TraceScope.runLenient(tracer, prebuilt, () -> delegateRan.set(true));

    // Then nothing propagates and delegate ran
    assertThat(delegateRan.get()).isTrue();
  }

  @Test
  @DisplayName("should suppress restore failure onto untraced delegate failure")
  void shouldSuppressRestoreFailureOntoUntracedDelegateFailure() {
    // Given a previous span, a failing install (untraced fallback), and a failing restore
    Span previous = Span.child(new DefaultTracer(), null);
    RuntimeException taskFailure = new RuntimeException("task boom");
    RuntimeException restoreFailure = new RuntimeException("restore boom");
    AtomicBoolean failInstall = new AtomicBoolean(false);
    AtomicBoolean armed = new AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (failInstall.getAndSet(false)) {
              super.setSpan(span);
              throw new RuntimeException("install boom");
            }
            if (armed.get() && span == previous) {
              throw restoreFailure;
            }
            super.setSpan(span);
          }
        };
    tracer.setSpan(previous);
    failInstall.set(true);

    // When the untraced delegate fails and the restore also fails
    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                TraceScope.runLenient(
                    tracer,
                    () -> Span.child(tracer, null),
                    () -> {
                      armed.set(true);
                      throw sneakyThrow(taskFailure);
                    }));

    // Then the delegate failure wins with the restore failure suppressed
    assertThat(thrown).isSameAs(taskFailure);
    assertThat(taskFailure.getSuppressed()).contains(restoreFailure);
  }

  @Test
  @DisplayName("should continue after restore failure following successful untraced delegate")
  void shouldContinueAfterRestoreFailureFollowingSuccessfulUntracedDelegate() {
    // Given a previous span, a failing install (untraced fallback), and a failing restore
    Span previous = Span.child(new DefaultTracer(), null);
    RuntimeException restoreFailure = new RuntimeException("restore boom");
    AtomicBoolean failInstall = new AtomicBoolean(false);
    AtomicBoolean armed = new AtomicBoolean(false);
    AtomicBoolean delegateRan = new AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (failInstall.getAndSet(false)) {
              super.setSpan(span);
              throw new RuntimeException("install boom");
            }
            if (armed.get() && span == previous) {
              throw restoreFailure;
            }
            super.setSpan(span);
          }
        };
    tracer.setSpan(previous);
    failInstall.set(true);

    // When the untraced delegate succeeds but the restore fails Then nothing propagates
    // (lenient stay-alive: the throwing setSpan already installed previous before throwing)
    TraceScope.runLenient(
        tracer,
        () -> Span.child(tracer, null),
        () -> {
          armed.set(true);
          delegateRan.set(true);
        });

    assertThat(delegateRan.get()).isTrue();
  }

  @Test
  @DisplayName("should not self-suppress when untraced restore rethrows delegate failure")
  void shouldNotSelfSuppressWhenUntracedRestoreRethrowsDelegateFailure() {
    // Given an untraced delegate failing with X and a restore rethrowing the same X
    Span previous = Span.child(new DefaultTracer(), null);
    RuntimeException shared = new RuntimeException("shared boom");
    AtomicBoolean failInstall = new AtomicBoolean(false);
    AtomicBoolean armed = new AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (failInstall.getAndSet(false)) {
              super.setSpan(span);
              throw new RuntimeException("install boom");
            }
            if (armed.get() && span == previous) {
              throw shared;
            }
            super.setSpan(span);
          }
        };
    tracer.setSpan(previous);
    failInstall.set(true);

    // When / Then the shared failure propagates without self-suppression
    assertThatThrownBy(
            () ->
                TraceScope.runLenient(
                    tracer,
                    () -> Span.child(tracer, null),
                    () -> {
                      armed.set(true);
                      throw sneakyThrow(shared);
                    }))
        .isSameAs(shared);
    assertThat(shared.getSuppressed()).isEmpty();
  }

  @Test
  @DisplayName("should propagate Error from lenient install instead of running untraced")
  void shouldPropagateErrorFromLenientInstallInsteadOfRunningUntraced() {
    // Given a tracer whose install fails with an Error (compromised JVM / tracer bug)
    AssertionError installError = new AssertionError("install boom");
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            super.setSpan(span);
            throw installError;
          }
        };
    Span prebuilt = Span.child(new DefaultTracer(), null);
    AtomicBoolean delegateRan = new AtomicBoolean();

    // When / Then the Error stays loud instead of degrading to an untraced run
    assertThatThrownBy(() -> TraceScope.runLenient(tracer, prebuilt, () -> delegateRan.set(true)))
        .isSameAs(installError);
    assertThat(delegateRan.get()).isFalse();
  }

  @Test
  @DisplayName("should not self-suppress when afterCall rethrows the task failure")
  void shouldNotSelfSuppressWhenAfterCallRethrowsTheTaskFailure() {
    // Given a task failing with X and an afterCall rethrowing the received failure itself
    RuntimeException taskFailure = new RuntimeException("task boom");
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void afterCall(Span span, Throwable e) {
            sneakyRethrow(e);
          }

          @SuppressWarnings("unchecked")
          private <E extends Throwable> void sneakyRethrow(Throwable throwable) throws E {
            throw (E) throwable;
          }
        };

    // When / Then the task failure propagates without self-suppression
    assertThatThrownBy(
            () ->
                TraceScope.run(
                    tracer,
                    () -> Span.child(tracer, null),
                    () -> {
                      throw sneakyThrow(taskFailure);
                    }))
        .isSameAs(taskFailure);
    assertThat(taskFailure.getSuppressed()).isEmpty();
  }

  @Test
  @DisplayName("should not self-suppress when restore rethrows the task failure")
  void shouldNotSelfSuppressWhenRestoreRethrowsTheTaskFailure() {
    // Given a task failing with X and a restore rethrowing the same X
    RuntimeException taskFailure = new RuntimeException("task boom");
    Span previous = Span.child(new DefaultTracer(), null);
    AtomicBoolean armed = new AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void setSpan(Span span) {
            if (armed.get() && span == previous) {
              sneakyRethrow(taskFailure);
            }
            super.setSpan(span);
          }

          @SuppressWarnings("unchecked")
          private <E extends Throwable> void sneakyRethrow(Throwable throwable) throws E {
            throw (E) throwable;
          }
        };
    tracer.setSpan(previous);

    // When the delegate arms the restore and fails with X
    // Then the task failure propagates without self-suppression
    assertThatThrownBy(
            () ->
                TraceScope.run(
                    tracer,
                    () -> Span.child(tracer, null),
                    () -> {
                      armed.set(true);
                      throw sneakyThrow(taskFailure);
                    }))
        .isSameAs(taskFailure);
    assertThat(taskFailure.getSuppressed()).isEmpty();
  }

  @Test
  @DisplayName("should not self-suppress when restore rethrows the afterCall failure")
  void shouldNotSelfSuppressWhenRestoreRethrowsTheAfterCallFailure() {
    // Given a successful task, an afterCall failing with Y, and a restore rethrowing the same Y
    RuntimeException afterFailure = new RuntimeException("afterCall boom");
    Span previous = Span.child(new DefaultTracer(), null);
    AtomicBoolean armed = new AtomicBoolean(false);
    DefaultTracer tracer =
        new DefaultTracer() {
          @Override
          public void afterCall(Span span, Throwable e) {
            armed.set(true);
            sneakyRethrow(afterFailure);
          }

          @Override
          public void setSpan(Span span) {
            if (armed.get() && span == previous) {
              sneakyRethrow(afterFailure);
            }
            super.setSpan(span);
          }

          @SuppressWarnings("unchecked")
          private <E extends Throwable> void sneakyRethrow(Throwable throwable) throws E {
            throw (E) throwable;
          }
        };
    tracer.setSpan(previous);

    // When / Then the afterCall failure propagates without self-suppression
    assertThatThrownBy(() -> TraceScope.run(tracer, () -> Span.child(tracer, null), () -> {}))
        .isSameAs(afterFailure);
    assertThat(afterFailure.getSuppressed()).isEmpty();
  }

  @Test
  @DisplayName("should restore installed span when beforeCall pollutes thread state")
  void shouldRestoreInstalledSpanWhenBeforeCallPollutesThreadState() {
    // Given a tracer whose beforeCall pollutes the thread with a foreign span
    DefaultTracer backing = new DefaultTracer();
    Span previous = Span.child(backing, null);
    backing.setSpan(previous);
    Span pollution = Span.child(backing, null);
    Span installed = Span.child(backing, previous);
    AtomicReference<Span> observed = new AtomicReference<>();
    Tracer polluting =
        new DefaultTracer() {
          @Override
          public Span getSpan() {
            return backing.getSpan();
          }

          @Override
          public void setSpan(Span span) {
            backing.setSpan(span);
          }

          @Override
          public void clearSpan() {
            backing.clearSpan();
          }

          @Override
          public void beforeCall(Span span) {
            backing.setSpan(pollution);
          }
        };

    // When running a scope whose beforeCall pollutes
    TraceScope.run(polluting, () -> installed, () -> observed.set(polluting.getSpan()));

    // Then the delegate still sees the installed span (parent chain intact),
    // and the pool thread is restored to previous instead of leaking pollution
    assertThat(observed.get()).isSameAs(installed);
    assertThat(polluting.getSpan()).isSameAs(previous);
    backing.clearSpan();
  }

  @Test
  @DisplayName("should keep only the prebuilt-span callChecked overload")
  void shouldKeepOnlyPrebuiltSpanCallCheckedOverload() {
    long overloads =
        java.util.Arrays.stream(TraceScope.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("callChecked"))
            .count();
    assertThat(overloads).as("only the prebuilt-span callChecked overload is used").isEqualTo(1);
    boolean prebuilt =
        java.util.Arrays.stream(TraceScope.class.getDeclaredMethods())
            .anyMatch(
                m ->
                    m.getName().equals("callChecked")
                        && java.util.Arrays.equals(
                            m.getParameterTypes(),
                            new Class<?>[] {
                              Tracer.class,
                              Span.class,
                              Class.class,
                              io.github.jinganix.peashooter.ThrowingSupplier.class
                            }));
    assertThat(prebuilt).isTrue();
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws E {
    throw (E) throwable;
  }

  private static Tracer tracerPlaceholder() {
    return new DefaultTracer();
  }
}
