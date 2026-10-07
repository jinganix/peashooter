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

import io.github.jinganix.peashooter.TraceIdGenerator;
import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.queue.RejectionAware;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Runnable} with task chain tracing.
 *
 * <p>Instances are reusable: a fresh {@link Span} is created per {@link #run()} via {@link
 * #createSpan()}. Contrast {@link OrderedTraceRunnable}, which is single-use.
 *
 * <p>Implements {@link RejectionAware} by forwarding to the delegate when it is rejection-aware: a
 * wrapper passed through {@link io.github.jinganix.peashooter.executor.TraceExecutor} unwrapped
 * must still deliver queue rejection notifications to its delegate instead of degrading sync
 * waiters to timeouts.
 */
public class TraceRunnable implements Runnable, RejectionAware {

  private static final Logger log = LoggerFactory.getLogger(TraceRunnable.class);

  private final Tracer tracer;

  private final Span parent;

  private final Runnable delegate;

  /**
   * Constructor.
   *
   * @param tracer {@link Tracer}
   * @param delegate {@link Runnable}
   */
  public TraceRunnable(Tracer tracer, Runnable delegate) {
    this.tracer = Objects.requireNonNull(tracer, "tracer");
    this.parent = tracer.getSpan();
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Create a new {@link Span}.
   *
   * @return {@link Span}
   */
  protected Span createSpan() {
    return Span.child(tracer, parent);
  }

  /**
   * Returns the parent span captured at construction time, may be {@code null}.
   *
   * <p>Narrow hook for subclass span factories: subclasses build spans from this parent plus {@link
   * #idGenerator()}, never from the full {@link Tracer}.
   *
   * @return parent span, or {@code null} when submitted without one
   */
  protected final Span parentSpan() {
    return parent;
  }

  /**
   * Returns the id generator for subclass span factories.
   *
   * <p>Narrow dependency: span creation needs only {@link TraceIdGenerator}, so subclasses never
   * observe span storage or callbacks through the full {@link Tracer}.
   *
   * @return id generator (the captured tracer narrowed)
   */
  protected final TraceIdGenerator idGenerator() {
    return tracer;
  }

  @Override
  public void run() {
    runScoped(this::createSpan);
  }

  /**
   * Unified lenient run over a span factory: the single owner of the delegate Error dispatch.
   * {@link TraceScope} degrades {@link Exception}s to an untraced run; an {@link Error} stays loud
   * here so sync waiters observe it via {@link RejectionAware} instead of timing out.
   */
  final void runScoped(Supplier<Span> spanFactory) {
    runScopedInternal(spanFactory, null);
  }

  /** Unified lenient run over a prebuilt span; same Error dispatch as the factory path. */
  final void runScoped(Span prebuilt) {
    runScopedInternal(null, Objects.requireNonNull(prebuilt, "span"));
  }

  /** Notifies the delegate of a discard without exposing it to subclasses. */
  final void dispatchDiscard(Throwable cause) {
    RejectionAware.dispatch(delegate, cause);
  }

  private void runScopedInternal(Supplier<Span> spanFactory, Span prebuilt) {
    // Invocation tracking is needed only when the delegate can be notified (RejectionAware): a
    // setup Error must complete its future, but an Error thrown by the delegate body must not be
    // re-notified as a rejection. Non-aware delegates surface the Error by propagation alone, so
    // they skip the wrapper (no hot-path allocation).
    Invocation invocation = delegate instanceof RejectionAware ? new Invocation(delegate) : null;
    Runnable body = invocation != null ? invocation : delegate;
    try {
      if (prebuilt != null) {
        TraceScope.runLenient(tracer, prebuilt, body);
      } else {
        TraceScope.runLenient(tracer, spanFactory, body);
      }
    } catch (Error setupError) {
      if (invocation != null && !invocation.invoked) {
        log.error("Trace span creation failed with Error; failing fast", setupError);
        try {
          invocation.dispatch(setupError);
        } catch (Throwable dispatchFailure) {
          if (dispatchFailure != setupError) {
            setupError.addSuppressed(dispatchFailure);
          }
        }
      }
      throw setupError;
    }
  }

  /** Tracks whether a rejection-aware delegate started, so its own Error is not re-notified. */
  private static final class Invocation implements Runnable {

    private final Runnable delegate;

    private boolean invoked;

    Invocation(Runnable delegate) {
      this.delegate = delegate;
    }

    @Override
    public void run() {
      invoked = true;
      delegate.run();
    }

    void dispatch(Throwable cause) {
      RejectionAware.dispatch(delegate, cause);
    }
  }

  @Override
  public void rejected(Throwable cause) {
    RejectionAware.dispatch(delegate, cause);
  }
}
