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
import io.github.jinganix.peashooter.internal.Keys;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@link TraceRunnable} that installs an {@link OrderedSpan} for per-key ordering and tracing.
 *
 * <p>The {@code sync} flag is stored on the span and drives {@link OrderedSpan#invokedBy(Span,
 * String)} for reentrant sync detection.
 *
 * <p>Instances are single-use: the span (including its span id) is created lazily on first use
 * ({@link #createSpan()} or {@link #run()}) and never for discarded instances, so constructing —
 * let alone discarding — a runnable spends no id generation. Only {@link #run()} and {@link
 * #rejected(Throwable)} consume the instance; {@link #createSpan()} only installs the cached span
 * for a later {@link #run()} to reuse. Do not resubmit the same instance; create a new one per
 * submission via {@link #forKey} / {@link #forTraceId}.
 *
 * <p><b>Concurrent misuse:</b> running the same instance from two threads at once is a caller bug
 * (instances are never shared by the framework). Both callers get {@link IllegalStateException};
 * the loser additionally completes the delegate (e.g. fails the waiting future) with the reuse
 * failure first so a sync waiter fails fast instead of hanging — but only while the winner is still
 * in flight. A sequential resubmission after a completed run never notifies again (the delegate
 * already ran), so non-idempotent delegates observe exactly one invocation per logical submission.
 */
public class OrderedTraceRunnable extends TraceRunnable {

  private final String key;

  private final boolean sync;

  /** Forced trace id, or {@code null} to generate one. */
  private final String traceId;

  /** Lifecycle of a single-use instance: only {@link #run()} / {@link #rejected} consume it. */
  private enum Lifecycle {
    FRESH,
    READY,
    RUN,
    DONE,
    REJECTED
  }

  /** Single holder for lifecycle plus the built span; one atomic carries both. */
  private record Slot(Lifecycle lifecycle, Span span) {}

  // Single AtomicReference owns exactly-once claim and span publication together.
  private final AtomicReference<Slot> slot = new AtomicReference<>(new Slot(Lifecycle.FRESH, null));

  /**
   * Creates an ordered runnable for {@code key}.
   *
   * @param tracer tracer
   * @param key per-key ordering identifier
   * @param sync true for sync paths
   * @param delegate task body, single-use with the returned instance
   * @return new runnable
   */
  public static OrderedTraceRunnable forKey(
      Tracer tracer, String key, boolean sync, Runnable delegate) {
    return new OrderedTraceRunnable(tracer, key, sync, delegate);
  }

  /**
   * Creates an ordered runnable with a forced trace id.
   *
   * @param tracer tracer, also used for the new span id
   * @param traceId forced trace id, validated as W3C-compatible (not passed through as-is)
   * @param key per-key ordering identifier
   * @param sync true for sync paths
   * @param delegate task body, single-use with the returned instance
   * @return new runnable
   */
  public static OrderedTraceRunnable forTraceId(
      Tracer tracer, String traceId, String key, boolean sync, Runnable delegate) {
    return new OrderedTraceRunnable(tracer, traceId, key, sync, delegate);
  }

  /**
   * Builds an {@link OrderedSpan} from the ordering key and sync flag.
   *
   * @param tracer {@link Tracer}
   * @param key per-key ordering identifier
   * @param sync {@code true} for {@link
   *     io.github.jinganix.peashooter.executor.OrderedTraceExecutor} sync paths; {@code false} for
   *     async
   * @param delegate {@link Runnable}
   */
  private OrderedTraceRunnable(Tracer tracer, String key, boolean sync, Runnable delegate) {
    super(tracer, delegate);
    // Fail fast like the eager OrderedSpan construction did: key validation must surface at
    // construction time, not on first run.
    this.key = Keys.requireKey(key);
    this.sync = sync;
    this.traceId = null;
  }

  /**
   * Forces {@code traceId} on the installed {@link OrderedSpan}.
   *
   * <p>See {@link OrderedSpan#continueTrace(String, TraceIdGenerator, Span, String, boolean)}: the
   * forced id wins over any id carried by the span active at construction time, while the parent
   * link is preserved.
   *
   * @param tracer {@link Tracer}, also used for the new span id
   * @param traceId forced trace id, kept as-is
   * @param key per-key ordering identifier
   * @param sync {@code true} for {@link
   *     io.github.jinganix.peashooter.executor.OrderedTraceExecutor} sync paths; {@code false} for
   *     async
   * @param delegate {@link Runnable}
   */
  private OrderedTraceRunnable(
      Tracer tracer, String traceId, String key, boolean sync, Runnable delegate) {
    super(tracer, delegate);
    this.traceId = Objects.requireNonNull(traceId, "traceId");
    this.key = Keys.requireKey(key);
    this.sync = sync;
  }

  @Override
  public Span createSpan() {
    // Build before claiming: publishing READY(null) first would let a concurrent run() observe
    // READY with a null span, enter runScoped(null), and then be resurrected by the late
    // set(READY(span)) overwriting its RUN claim. One CAS FRESH->READY(span) leaves no
    // observable intermediate state; a duplicate submission still fails without reuse, at the
    // cost of one wasted span build for the loser.
    Span span = buildSpan();
    Slot observed = slot.get();
    if (observed.lifecycle() != Lifecycle.FRESH
        || !slot.compareAndSet(observed, new Slot(Lifecycle.READY, span))) {
      throw new IllegalStateException(
          "OrderedTraceRunnable is single-use: create a new instance per submission");
    }
    return span;
  }

  @Override
  public void run() {
    // Single CAS on the observed slot: READY reuses the prebuilt span, FRESH builds it below.
    Slot observed = slot.get();
    Lifecycle current = observed.lifecycle();
    if (current != Lifecycle.READY && current != Lifecycle.FRESH) {
      throw throwReuseAfterConsume();
    }
    Slot claimed = new Slot(Lifecycle.RUN, observed.span());
    if (!slot.compareAndSet(observed, claimed)) {
      throw throwReuseAfterConsume();
    }
    boolean reuse = current == Lifecycle.READY;
    // Reuse a span installed by createSpan() when present, so pre-building never counts as a
    // duplicate submission nor mints a second id. Passed directly (no factory lambda) to save
    // one allocation per task. Single lenient owner is the inherited runScoped helper.
    Span span;
    if (reuse) {
      span = claimed.span();
    } else {
      try {
        span = buildSpanOrFail();
      } catch (Throwable buildFailure) {
        // Build already notified the delegate once: park as REJECTED so a second run()
        // takes the already-discarded branch and fails without a second dispatch.
        slot.set(new Slot(Lifecycle.REJECTED, null));
        throw buildFailure;
      }
      slot.set(new Slot(Lifecycle.RUN, span));
    }
    try {
      runScoped(span);
    } finally {
      // Completed (success or delegate failure): park as DONE so a sequential resubmission
      // fails without a second dispatch. A concurrent loser racing this transition still
      // observes RUN and dispatches once for fail-fast; later resubmissions observe DONE.
      // The prebuilt span is dropped so a dead runnable retains no parent chain.
      slot.set(new Slot(Lifecycle.DONE, null));
    }
  }

  private IllegalStateException throwReuseAfterConsume() {
    IllegalStateException reuseFailure =
        new IllegalStateException(
            "OrderedTraceRunnable is single-use: create a new instance per submission");
    Lifecycle terminal = slot.get().lifecycle();
    if (terminal == Lifecycle.REJECTED || terminal == Lifecycle.DONE) {
      // Already consumed (discarded via rejected() or completed via run()): the delegate was
      // notified at most once already (discard cause or the task body itself). Notifying again
      // with reuse would double-notify non-idempotent delegates for one logical submission,
      // so fail explicitly without a second dispatch. Only an in-flight RUN (concurrent loser)
      // dispatches below for fail-fast.
      throw reuseFailure;
    }
    // Complete the delegate first (e.g. a future callback): otherwise a sync waiter on the
    // resubmitted instance would hang until timeout instead of failing on the misuse. A
    // failing callback is suppressed, never masking the reuse itself.
    try {
      dispatchDiscard(reuseFailure);
    } catch (Throwable dispatchFailure) {
      if (dispatchFailure != reuseFailure) {
        reuseFailure.addSuppressed(dispatchFailure);
      }
    }
    throw reuseFailure;
  }

  /**
   * Builds the single-use span, completing the delegate when setup fails. Without this the delegate
   * (e.g. a sync future callback) would never run and its waiter would strand until timeout: a
   * throwing tracer or id generator must fail the submission with its own cause, not a {@link
   * io.github.jinganix.peashooter.executor.TraceTimeoutException}. A failing callback is
   * suppressed, never masking the setup failure itself.
   */
  private Span buildSpanOrFail() {
    try {
      return buildSpan();
    } catch (Throwable setupFailure) {
      // Wrap checked failures before notifying: the future behind the delegate carries the
      // unchecked wrapper, so enqueueChecked rethrows it as-is instead of masquerading a
      // foreign checked cause under the caller's unrelated static type E. RuntimeExceptions
      // and Errors dispatch as-is.
      Throwable toDispatch =
          (setupFailure instanceof RuntimeException || setupFailure instanceof Error)
              ? setupFailure
              : new CompletionException(setupFailure);
      try {
        dispatchDiscard(toDispatch);
      } catch (Throwable dispatchFailure) {
        if (dispatchFailure != setupFailure) {
          setupFailure.addSuppressed(dispatchFailure);
        }
      }
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(setupFailure);
    }
  }

  /**
   * Builds the single-use span. Installed at most once per instance (guarded by {@link #slot}), so
   * id generation is skipped entirely for discarded instances.
   */
  private Span buildSpan() {
    // Key was validated once at construction: use the validated-key factories so one submission
    // pays exactly one key check. Narrow dependency: span factories need only the id
    // generator plus the captured parent, never the full tracer.
    if (traceId != null) {
      return OrderedSpan.continueForValidatedKey(traceId, idGenerator(), parentSpan(), key, sync);
    }
    return OrderedSpan.childForValidatedKey(idGenerator(), parentSpan(), key, sync);
  }

  /** Reads the current span; tracer storage failures surface unchecked, errors stay loud. */
  private static Span acquireParent(Tracer tracer) {
    try {
      return tracer.getSpan();
    } catch (Error fatal) {
      throw fatal;
    } catch (Throwable getFailure) {
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(getFailure);
    }
  }

  /** Builds an inline ordered span on an already-validated key; setup failures never masquerade. */
  private static Span buildInlineSpan(Tracer tracer, Span parent, String key) {
    try {
      return OrderedSpan.childForValidatedKey(tracer, parent, key, true);
    } catch (Throwable setupFailure) {
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(setupFailure);
    }
  }

  /**
   * Runs a checked supplier inside a fresh {@link OrderedSpan} for {@code key}, propagating its
   * typed failure.
   *
   * <p>Used by reentrant sync paths that cannot wrap the supplier in a {@link Runnable} without
   * losing its static failure type. Each call builds its own span (no single-use instance needed);
   * the span factory lambda always executes, unlike a dummy delegate that would sit uncovered.
   *
   * <p>Only failures known to be {@code E} (via {@code type}) propagate as {@code E}; any other
   * checked failure (span setup or a smuggled foreign type) surfaces wrapped in {@link
   * CompletionException}.
   *
   * @param tracer tracer owning the span storage
   * @param key per-key ordering identifier
   * @param type witness for the declared checked failure type
   * @param supplier work to run
   * @param <R> result type
   * @param <E> checked failure type
   * @return supplier result
   * @throws E if the supplier throws its declared failure
   */
  public static <R, E extends Throwable> R runChecked(
      Tracer tracer, String key, Class<E> type, ThrowingSupplier<R, E> supplier) throws E {
    Objects.requireNonNull(tracer, "tracer");
    Keys.requireKey(key);
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(supplier, "supplier");
    Span parent = acquireParent(tracer);
    // Build eagerly on the validated key: setup failures surface here (never masquerading as E),
    // so no invoked-flag allocation is needed to tell setup apart from delegate failures.
    final Span span = buildInlineSpan(tracer, parent, key);
    try {
      return TraceScope.callChecked(tracer, span, type, supplier);
    } catch (RuntimeException rethrow) {
      throw rethrow;
    } catch (Error error) {
      throw error;
    } catch (Throwable delegateFailure) {
      if (type.isInstance(delegateFailure)) {
        throw type.cast(delegateFailure);
      }
      throw new CompletionException(delegateFailure);
    }
  }

  /**
   * Runs an unchecked supplier inside a fresh {@link OrderedSpan} for {@code key}.
   *
   * <p>Unchecked counterpart to {@link #runChecked}: {@link java.util.function.Supplier} declares
   * no checked failures, so no invocation tracking is needed — any checked {@link Throwable}
   * escaping the scope necessarily comes from span setup and is wrapped in a {@link
   * CompletionException} instead of masquerading under a false static type. Saves the {@code
   * ResultBox} + wrapper {@code Runnable} that a {@code forKey(...).run()} round-trip would
   * allocate on this hottest inline path.
   *
   * @param tracer tracer owning the span storage
   * @param key per-key ordering identifier
   * @param supplier work to run
   * @param <R> result type
   * @return supplier result
   */
  public static <R> R runValue(Tracer tracer, String key, Supplier<R> supplier) {
    Objects.requireNonNull(tracer, "tracer");
    Keys.requireKey(key);
    Objects.requireNonNull(supplier, "supplier");
    Span parent = acquireParent(tracer);
    final Span span = buildInlineSpan(tracer, parent, key);
    try {
      return TraceScope.callChecked(tracer, span, RuntimeException.class, supplier::get);
    } catch (RuntimeException rethrow) {
      throw rethrow;
    } catch (Error error) {
      throw error;
    } catch (Throwable delegateFailure) {
      throw new CompletionException(delegateFailure);
    }
  }

  @Override
  public void rejected(Throwable cause) {
    // Idempotent consume via CAS: a discarded instance must never be resubmitted,
    // otherwise the same span id would be reused for a different execution. Both FRESH and
    // READY (prebuilt but not yet run) are consumable; only the first rejection notifies
    // the delegate, a duplicate (caller bug) is dropped so a non-idempotent RejectionAware
    // delegate is not notified twice, and a lost CAS race notifies nothing.
    Slot previous = slot.get();
    Lifecycle lifecycle = previous.lifecycle();
    // REJECTED always drops the span: a discarded instance never runs, so retaining the prebuilt
    // span would only extend the parent chain lifetime through a dead runnable.
    boolean consumed =
        lifecycle == Lifecycle.FRESH
            ? slot.compareAndSet(previous, new Slot(Lifecycle.REJECTED, null))
            : lifecycle == Lifecycle.READY
                && slot.compareAndSet(previous, new Slot(Lifecycle.REJECTED, null));
    if (consumed) {
      dispatchDiscard(cause);
    }
  }
}
