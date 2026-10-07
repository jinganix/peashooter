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

import io.github.jinganix.peashooter.ThrowingSupplier;
import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.internal.Interruptions;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared span-scope handling for {@link TraceRunnable}, {@link TraceCallable}, and checked
 * suppliers.
 *
 * <p>One owner for the save/restore contract: install the new span, isolate {@code beforeCall}
 * failures (logged, delegate still runs), report the delegate outcome via {@code afterCall} with
 * {@code addSuppressed} preservation, then restore the previous span.
 *
 * <p>Failure contract (no sneaky-throw): {@link RuntimeException}s and {@link Error}s propagate
 * as-is. Any other {@link Throwable} (a checked failure smuggled past {@link Runnable}, {@link
 * Supplier}, or tracer signatures) is wrapped in a {@link CompletionException} instead of being
 * rethrown under a false static type. Only {@link #call} (whose delegate declares {@code throws
 * Exception}) propagates checked {@link Exception}s unwrapped, and only {@link #callChecked} (whose
 * delegate declares {@code throws E} with a {@code Class<E>} witness) unwraps a checked failure
 * known to be {@code E}; everything else is unchecked.
 */
final class TraceScope {

  private static final Logger log = LoggerFactory.getLogger(TraceScope.class);

  /**
   * Lenient untraced fallbacks are counted per tracer ({@link
   * AbstractTracer#getTracerFallbackCount}).
   *
   * <p>Counted once per degraded run where an {@link Exception} from tracer storage, span factory,
   * or install forces the delegate to run without tracing: without this, lenient fallback only logs
   * and silently breaks the span chain, masking tracer bugs. {@link Error}s stay loud and never
   * count.
   */
  private static void recordFallback(Tracer tracer) {
    if (tracer instanceof AbstractTracer abstractTracer) {
      abstractTracer.recordTracerFallback();
    }
  }

  private TraceScope() {}

  /**
   * Install strictness: strict installs propagate a throwing install/factory (fail fast), lenient
   * ones degrade to an untraced run. An {@link Error} stays loud on both paths instead of degrading
   * on a compromised JVM.
   */
  enum InstallMode {
    STRICT,
    LENIENT
  }

  /**
   * Single parameter object behind every scope template. Replaces the seven-argument {@code
   * execute} form so call sites cannot mix up the delegate, policy, and leniency positions, and so
   * the span-source invariant is validated once in the constructor instead of at every use.
   *
   * @param <V> delegate result type
   * @param <E> witness type for {@link ErrorPolicy#TYPED}; unused (any type) otherwise
   * @param tracer tracer owning the span storage
   * @param spanFactory factory for the new span (cold path), or {@code null} for prebuilt
   * @param prebuilt prebuilt span (hot path), or {@code null} for factory
   * @param call delegate work
   * @param policy how delegate/finish failures surface
   * @param type witness for {@link ErrorPolicy#TYPED}, {@code null} otherwise
   * @param mode strict vs lenient install handling
   */
  record ScopeRequest<V, E>(
      Tracer tracer,
      Supplier<Span> spanFactory,
      Span prebuilt,
      ScopedCall<V> call,
      ErrorPolicy policy,
      Class<E> type,
      InstallMode mode) {
    ScopeRequest {
      Objects.requireNonNull(tracer, "tracer");
      Objects.requireNonNull(call, "call");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(mode, "mode");
      if ((spanFactory == null) == (prebuilt == null)) {
        throw new IllegalArgumentException("exactly one of spanFactory/prebuilt must be non-null");
      }
      if (policy == ErrorPolicy.TYPED && type == null) {
        throw new IllegalArgumentException("TYPED policy requires a witness type");
      }
    }
  }

  /**
   * Runs {@code delegate} inside a fresh span from {@code spanFactory}.
   *
   * <p>A throwing install propagates (fail fast): direct callers observe tracer bugs immediately
   * instead of silently running untraced. Queue runners must use {@link #runLenient}, where a dead
   * install would strand every waiter behind the runner.
   *
   * <p>Unchecked only: checked failures surface wrapped in {@link CompletionException}.
   */
  static void run(Tracer tracer, Supplier<Span> spanFactory, Runnable delegate) {
    Objects.requireNonNull(tracer, "tracer");
    Objects.requireNonNull(spanFactory, "spanFactory");
    Objects.requireNonNull(delegate, "delegate");
    runInternal(tracer, spanFactory, null, delegate, InstallMode.STRICT);
  }

  /**
   * Lenient variant of {@link #run}: when the span install fails with an {@link Exception}, the
   * delegate runs untraced instead of stranding. An {@link Error} stays loud and fail-open instead
   * of degrading to an untraced run on a compromised JVM.
   *
   * <p>For queue runners and ordered tasks, a loud install failure would kill the runner and stall
   * every waiter behind it until timeout. The install already restores the thread state, so the
   * untraced delegate runs on a clean thread; its own failures (including interrupt restoration)
   * propagate exactly as on the traced path (checked wrapped).
   */
  static void runLenient(Tracer tracer, Supplier<Span> spanFactory, Runnable delegate) {
    Objects.requireNonNull(tracer, "tracer");
    Objects.requireNonNull(spanFactory, "spanFactory");
    Objects.requireNonNull(delegate, "delegate");
    runInternal(tracer, spanFactory, null, delegate, InstallMode.LENIENT);
  }

  /**
   * Lenient variant taking a prebuilt span instead of a factory. An {@link Error} from the install
   * stays loud instead of degrading to an untraced run. Saves one capturing lambda per task on the
   * hot path: callers that already built the span eagerly (e.g. {@link OrderedTraceRunnable}, which
   * must build it to complete waiters on setup failure) pass it directly instead of wrapping it in
   * {@code () -> span}.
   *
   * @param tracer tracer owning the span storage
   * @param span prebuilt span, installed when the tracer is healthy
   * @param delegate work to run
   */
  static void runLenient(Tracer tracer, Span span, Runnable delegate) {
    Objects.requireNonNull(tracer, "tracer");
    Objects.requireNonNull(span, "span");
    Objects.requireNonNull(delegate, "delegate");
    runInternal(tracer, null, span, delegate, InstallMode.LENIENT);
  }

  /**
   * Single run template behind {@link #run} and both {@link #runLenient} overloads: the only place
   * that adapts a {@link Runnable} to the unchecked scope, so lenient vs strict differs by exactly
   * one mode and the prebuilt-span overload saves the factory lambda without forking the failure
   * contract.
   */
  private static void runInternal(
      Tracer tracer,
      Supplier<Span> spanFactory,
      Span prebuilt,
      Runnable delegate,
      InstallMode mode) {
    executeUnchecked(
        new ScopeRequest<>(
            tracer,
            spanFactory,
            prebuilt,
            () -> {
              delegate.run();
              return null;
            },
            ErrorPolicy.UNCHECKED,
            null,
            mode));
  }

  /**
   * Calls {@code delegate} inside a fresh span from {@code spanFactory}.
   *
   * <p>Checked {@link Exception}s from the delegate propagate as-is (the delegate declares {@code
   * throws Exception}). Any other checked {@link Throwable} (smuggled infrastructure failures)
   * surfaces wrapped in {@link CompletionException}. A throwing install propagates like {@link
   * #run}.
   */
  static <V> V call(Tracer tracer, Supplier<Span> spanFactory, Callable<V> delegate)
      throws Exception {
    Objects.requireNonNull(tracer, "tracer");
    Objects.requireNonNull(spanFactory, "spanFactory");
    Objects.requireNonNull(delegate, "delegate");
    // E is Exception here, so executeChecked never throws a Throwable that is neither an Error
    // nor an Exception; checked failures declared by Callable propagate unwrapped via `throws`.
    return TraceScope.<V, Exception>executeChecked(
        new ScopeRequest<>(
            tracer,
            spanFactory,
            null,
            delegate::call,
            ErrorPolicy.CALLABLE,
            Exception.class,
            InstallMode.STRICT));
  }

  /**
   * Typed call over a prebuilt span.
   *
   * <p>Strict counterpart to {@link #runLenient(Tracer, Span, Runnable)} for reentrant checked
   * paths that already built the span eagerly: saves the factory lambda and the invoked-flag
   * allocation, since setup failures surface before this call and never masquerade as {@code E}.
   */
  static <V, E extends Throwable> V callChecked(
      Tracer tracer, Span span, Class<E> type, ThrowingSupplier<V, E> delegate) throws E {
    Objects.requireNonNull(tracer, "tracer");
    Objects.requireNonNull(span, "span");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(delegate, "delegate");
    try {
      return TraceScope.<V, E>executeChecked(
          new ScopeRequest<>(
              tracer, null, span, delegate::get, ErrorPolicy.TYPED, type, InstallMode.STRICT));
    } catch (Error | RuntimeException rethrow) {
      throw rethrow;
    } catch (Throwable other) {
      if (type.isInstance(other)) {
        throw type.cast(other);
      }
      throw new CompletionException(other);
    }
  }

  /** Uniform delegate shape: every failure surfaces as {@link Throwable}. */
  @FunctionalInterface
  private interface ScopedCall<V> {
    V invoke() throws Throwable;
  }

  /**
   * Unchecked scope template behind {@link #run} and both {@link #runLenient} overloads: install
   * the new span, isolate {@code beforeCall} failures (logged, delegate still runs), report the
   * delegate outcome via {@code afterCall} with {@code addSuppressed} preservation, then restore
   * the previous span. Checked failures surface wrapped; no sneaky throws.
   *
   * <p><b>Intentional overlap with {@code executeChecked}:</b> both share {@code acquirePrevious} /
   * {@code acquireSpan} helpers; the remaining linear flows stay separate because lenient
   * degradation vs strict propagation plus unchecked vs checked delegate mapping differ per branch.
   * Merging them behind a policy lambda would allocate per task on this hot path.
   */
  private static <V> V executeUnchecked(ScopeRequest<V, ?> req) {
    // Locals instead of a Scope record: one fewer allocation per task on this hot path.
    // Single linear flow: acquire previous, acquire span, traced run. Lenient acquisition
    // failures degrade to an untraced run; strict ones propagate (checked wrapped).
    boolean lenient = req.mode() == InstallMode.LENIENT;
    Span previous;
    try {
      previous = acquirePrevious(req.tracer());
    } catch (Error fatal) {
      throw fatal;
    } catch (Throwable getFailure) {
      if (!lenient) {
        throw ErrorPolicy.UNCHECKED.rethrowUnchecked(getFailure);
      }
      log.error("Tracer.getSpan failed; running delegate without tracing", getFailure);
      return runUntraced(req.tracer(), null, req.call());
    }
    Span span;
    if (req.prebuilt() != null) {
      span = req.prebuilt();
    } else {
      try {
        span = acquireSpan(req.spanFactory());
      } catch (Error fatal) {
        throw fatal;
      } catch (Throwable factoryFailure) {
        if (!lenient) {
          throw ErrorPolicy.UNCHECKED.rethrowUnchecked(factoryFailure);
        }
        log.error("Span factory failed; running delegate without tracing", factoryFailure);
        return runUntraced(req.tracer(), previous, req.call());
      }
    }
    return tracedRunUnchecked(req.tracer(), previous, span, req.call(), lenient);
  }

  /**
   * Checked scope template behind {@link #call} and {@link #callChecked}. Strict installs only: a
   * throwing install/factory propagates (checked wrapped, never masquerading as {@code E}).
   * Delegate and finish failures matching {@code type} (or any {@link Exception} for {@link
   * ErrorPolicy#CALLABLE}) propagate with their static type via declared {@code throws}; everything
   * else checked surfaces wrapped. No sneaky throws.
   */
  private static <V, E extends Throwable> V executeChecked(ScopeRequest<V, E> req)
      throws E, Exception {
    // Witness lives typed on the request (validated at construction), so no cast is needed to
    // throw it via declared `throws E`.
    Class<E> witness = req.type();
    Span previous;
    try {
      previous = acquirePrevious(req.tracer());
    } catch (Error fatal) {
      throw fatal;
    } catch (Throwable getFailure) {
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(getFailure);
    }
    Span span;
    if (req.prebuilt() != null) {
      span = req.prebuilt();
    } else {
      try {
        span = acquireSpan(req.spanFactory());
      } catch (Error fatal) {
        throw fatal;
      } catch (Throwable factoryFailure) {
        throw ErrorPolicy.UNCHECKED.rethrowUnchecked(factoryFailure);
      }
    }
    return TraceScope.<V, E>tracedRunChecked(
        req.tracer(), previous, span, req.call(), req.policy(), witness);
  }

  /** Reads the current span; failures propagate to the scope templates for policy handling. */
  private static Span acquirePrevious(Tracer tracer) {
    return tracer.getSpan();
  }

  /** Builds the new span; failures propagate to the scope templates for policy handling. */
  private static Span acquireSpan(Supplier<Span> spanFactory) {
    return Objects.requireNonNull(spanFactory.get(), "span");
  }

  /**
   * Unchecked traced run over an acquired span: installs (lenient or strict), runs the delegate
   * with unchecked mapping, and finishes unchecked. Checked failures surface wrapped; no sneaky
   * throws.
   */
  private static <V> V tracedRunUnchecked(
      Tracer tracer, Span previous, Span span, ScopedCall<V> call, boolean lenientInstall) {
    if (lenientInstall && !installOrUntraced(tracer, span, previous)) {
      // Untraced fallback: failure propagation (including interrupt restoration) matches the
      // traced path below so callers cannot tell which path ran. The delegate runs on the
      // restored thread, but a misbehaving delegate may still pollute it (setSpan without
      // clearing): always restore previous afterwards so pooled threads never leak spans
      // into later tasks and corrupt reentrancy detection.
      return runUntraced(tracer, previous, call);
    }
    if (!lenientInstall) {
      try {
        installSpan(tracer, span, previous);
      } catch (Error fatal) {
        throw fatal;
      } catch (Throwable installFailure) {
        throw ErrorPolicy.UNCHECKED.rethrowUnchecked(installFailure);
      }
    }
    Throwable error = null;
    try {
      beforeCallQuietly(tracer, span);
      return call.invoke();
    } catch (Throwable e) {
      error = e;
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(e);
    } finally {
      finishUnchecked(tracer, span, error, previous);
    }
  }

  /**
   * Checked traced run over an acquired span (strict installs only). Delegate and finish failures
   * matching the policy propagate with their static type via declared {@code throws}; everything
   * else checked surfaces wrapped. No sneaky throws.
   */
  private static <V, E extends Throwable> V tracedRunChecked(
      Tracer tracer,
      Span previous,
      Span span,
      ScopedCall<V> call,
      ErrorPolicy policy,
      Class<E> type)
      throws E, Exception {
    try {
      installSpan(tracer, span, previous);
    } catch (Error fatal) {
      throw fatal;
    } catch (Throwable installFailure) {
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(installFailure);
    }
    Throwable error = null;
    try {
      beforeCallQuietly(tracer, span);
      return call.invoke();
    } catch (Throwable e) {
      error = e;
      throw policy.<E>rethrowDelegate(e, type);
    } finally {
      finishChecked(tracer, span, error, previous, policy, type);
    }
  }

  /** Unchecked untraced template: checked failures surface wrapped. */
  private static <V> V runUntraced(Tracer tracer, Span previous, ScopedCall<V> call) {
    recordFallback(tracer);
    Throwable fallbackError = null;
    V fallbackResult = null;
    try {
      fallbackResult = call.invoke();
    } catch (Throwable fallbackFailure) {
      fallbackError = fallbackFailure;
      if (Interruptions.carriesInterrupt(fallbackFailure)) {
        Thread.currentThread().interrupt();
      }
    } finally {
      try {
        restore(tracer, previous);
      } catch (Throwable restoreFailure) {
        if (fallbackError != null) {
          if (fallbackError != restoreFailure) {
            fallbackError.addSuppressed(restoreFailure);
          }
        } else {
          // fallbackError == null implies the delegate succeeded: reaching this handler
          // requires call.invoke() to have returned normally into fallbackResult.
          // Lenient stay-alive for Exceptions only: a throwing restore already installed
          // previous before throwing (see InstallThenThrowTracer), so the thread holds
          // previous anyway and the runner survives. Errors stay loud and fail-open.
          if (restoreFailure instanceof Error err) {
            throw err;
          }
          log.error("Tracer restore failed after untraced fallback; continuing", restoreFailure);
        }
      }
    }
    if (fallbackError != null) {
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(fallbackError);
    }
    return fallbackResult;
  }

  /**
   * Reports the delegate outcome via {@code afterCall} with {@code addSuppressed} preservation,
   * then restores the previous span. A throwing {@code afterCall} is suppressed onto the task
   * failure, or propagates unchecked when the task succeeded. A throwing restore is likewise
   * suppressed onto whatever failure is already in flight instead of replacing it via {@code
   * finally} semantics.
   */
  private static void finishUnchecked(Tracer tracer, Span span, Throwable error, Span previous) {
    Throwable pending = finishCollect(tracer, span, error, previous);
    if (pending != null) {
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(pending);
    }
  }

  /**
   * Checked finish counterpart: pending failures matching the policy propagate with their static
   * type via declared {@code throws}; everything else checked surfaces wrapped.
   */
  private static <E extends Throwable> void finishChecked(
      Tracer tracer, Span span, Throwable error, Span previous, ErrorPolicy policy, Class<E> type)
      throws E, Exception {
    Throwable pending = finishCollect(tracer, span, error, previous);
    if (pending != null) {
      throw policy.<E>rethrowPending(pending, type);
    }
  }

  /** Shared afterCall/restore collection behind the finish variants. */
  private static Throwable finishCollect(Tracer tracer, Span span, Throwable error, Span previous) {
    Throwable pending = null;
    try {
      tracer.afterCall(span, error);
    } catch (Throwable afterFailure) {
      if (error != null) {
        if (error != afterFailure) {
          error.addSuppressed(afterFailure);
        }
      } else {
        pending = afterFailure;
      }
    }
    try {
      restore(tracer, previous);
    } catch (Throwable restoreFailure) {
      if (error != null) {
        if (error != restoreFailure) {
          error.addSuppressed(restoreFailure);
        }
      } else if (pending != null) {
        if (pending != restoreFailure) {
          pending.addSuppressed(restoreFailure);
        }
      } else {
        pending = restoreFailure;
      }
    }
    return pending;
  }

  /**
   * Installs the new span, restoring {@code previous} when a throwing tracer leaves the thread
   * polluted. Without this, a {@code setSpan} that installs-then-throws (e.g. a notifying bridge
   * bug) would strand the new span on a pooled thread, corrupting the parent chain and reentrancy
   * detection of every later task on it. The original failure always propagates; a failing restore
   * is suppressed onto it instead of masking it.
   */
  private static void installSpan(Tracer tracer, Span span, Span previous) {
    try {
      tracer.setSpan(span);
    } catch (Throwable setFailure) {
      try {
        restore(tracer, previous);
      } catch (Throwable restoreFailure) {
        if (restoreFailure != setFailure) {
          setFailure.addSuppressed(restoreFailure);
        }
      }
      throw setFailure;
    }
  }

  /**
   * Installs {@code span}, falling back to untraced execution when the tracer throws an {@link
   * Exception}. An {@link Error} stays loud and fail-open instead of degrading to an untraced run
   * on a compromised JVM.
   *
   * @return {@code false} when the caller must run its delegate without tracing: the install
   *     already restored the thread state, so the delegate runs on a clean thread
   */
  private static boolean installOrUntraced(Tracer tracer, Span span, Span previous) {
    try {
      installSpan(tracer, span, previous);
      return true;
    } catch (Error fatal) {
      throw fatal;
    } catch (Throwable installFailure) {
      log.error("Tracer.setSpan failed; running delegate without tracing", installFailure);
      return false;
    }
  }

  /**
   * Invokes {@code beforeCall} without letting it pollute thread state. Callbacks must not touch
   * thread state (see {@link io.github.jinganix.peashooter.TraceCallback}): after the callback, the
   * installed {@code span} is snapshot-compared via {@code getSpan}, and any pollution (including
   * {@code clearSpan} to {@code null} or a foreign span) is restored with a {@code warn} so the
   * delegate always runs with the installed span and pooled threads never inherit a foreign parent
   * chain. Failures (including the pollution check itself) are logged and the delegate still runs.
   */
  private static void beforeCallQuietly(Tracer tracer, Span span) {
    try {
      tracer.beforeCall(span);
    } catch (Throwable callbackFailure) {
      // Tracer bugs must not strand the task or its sync waiter: log and continue.
      log.error("Tracer.beforeCall failed; running delegate anyway", callbackFailure);
    }
    Span after;
    try {
      after = tracer.getSpan();
    } catch (Throwable getFailure) {
      log.warn("Tracer.beforeCall polluted thread state; restoring installed span", getFailure);
      try {
        tracer.setSpan(span);
      } catch (Throwable restoreFailure) {
        log.error(
            "Tracer.beforeCall pollution restore failed; running delegate anyway", restoreFailure);
      }
      return;
    }
    if (after != span) {
      log.warn("Tracer.beforeCall polluted thread state; restored installed span");
      try {
        tracer.setSpan(span);
      } catch (Throwable restoreFailure) {
        log.error(
            "Tracer.beforeCall pollution restore failed; running delegate anyway", restoreFailure);
      }
    }
  }

  static void restore(Tracer tracer, Span previous) {
    if (previous == null) {
      tracer.clearSpan();
    } else {
      tracer.setSpan(previous);
    }
  }
}
