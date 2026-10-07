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

package io.github.jinganix.peashooter.executor;

import io.github.jinganix.peashooter.ExecutorSelector;
import io.github.jinganix.peashooter.TaskQueueProvider;
import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.internal.KeySanitizer;
import io.github.jinganix.peashooter.queue.RejectionAware;
import io.github.jinganix.peashooter.queue.TaskQueue;
import io.github.jinganix.peashooter.trace.ErrorPolicy;
import io.github.jinganix.peashooter.trace.OrderedTraceRunnable;
import io.github.jinganix.peashooter.trace.TraceRunnable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sync/async submission plumbing for {@link OrderedTraceExecutor}.
 *
 * <p>Owns single-key enqueue, selector resolution, peer probing, and wait translation so the facade
 * keeps only public API, key normalization, and policy wiring. All state stays with the caller;
 * this class holds the shared collaborators.
 */
final class SubmissionRouter {

  private static final Logger log = LoggerFactory.getLogger(SubmissionRouter.class);

  private final TaskQueueProvider queues;
  private final ExecutorSelector selector;
  private final Tracer tracer;

  SubmissionRouter(TaskQueueProvider queues, ExecutorSelector selector, Tracer tracer) {
    this.queues = queues;
    this.selector = selector;
    this.tracer = tracer;
  }

  /**
   * Shared single-key fire-and-forget enqueue: hands an already-fenced {@code runnable} to the
   * per-key queue. Single submission path behind both {@link
   * OrderedTraceExecutor#executeAsync(String, Runnable)} and {@link #enqueueAsync}: selector
   * resolution, fence release, and queue handoff stay in one place instead of drifting across two
   * call sites.
   */
  void executeAsync(String key, TraceRunnable runnable) {
    TaskQueue queue = queues.getForSubmit(key);
    queue.execute(selectExecutor(key, queue, runnable, false), runnable);
  }

  /**
   * Shared single-key async enqueue: hands an already-fenced {@code runnable} to the per-key queue
   * and returns {@code future} as the cancel/dedup handle.
   *
   * <p>Selector {@link Exception}s are contained: the submission future is completed and returned.
   * A selector {@link Error} stays loud and propagates (the future was already completed via
   * rejection dispatch, but no handle is returned on the throwing path — callers must treat the
   * {@code Error} itself as the signal and deduplicate via the key).
   */
  <R> CompletableFuture<R> enqueueAsync(
      String key, TraceRunnable runnable, CompletableFuture<R> future) {
    TaskQueue queue = queues.getForSubmit(key);
    Executor selected;
    try {
      selected = selectExecutor(key, queue, runnable, false);
    } catch (Exception selectorFailure) {
      log.warn(
          "ExecutorSelector failed for async key '{}'; submission future already completed",
          KeySanitizer.sanitize(key),
          selectorFailure);
      return future;
    }
    try {
      queue.execute(selected, runnable);
    } catch (Throwable executeFailure) {
      // Reuse the same future-completion translation as the fire-and-forget path's rejection
      // notice: the future carries the original failure while the throw keeps the
      // fail-fast submission contract.
      future.completeExceptionally(executeFailure);
      throw executeFailure;
    }
    return future;
  }

  /**
   * Shared single-key sync submission: wraps {@code task} in an ordered span, enqueues it, and
   * blocks on {@code future} until the shared deadline.
   */
  <R> R enqueueSync(
      String key, Runnable task, CompletableFuture<R> future, long waitNanos, long deadlineNanos) {
    return enqueueTimed(key, task, future, null, ErrorPolicy.UNCHECKED, waitNanos, deadlineNanos);
  }

  /**
   * Shared single-key checked submission; like {@link #enqueueSync} but unwraps the declared
   * checked failure (via {@code type}) instead of re-wrapping it.
   */
  <R, E extends Throwable> R enqueueChecked(
      String key,
      Runnable task,
      CompletableFuture<R> future,
      Class<E> type,
      long waitNanos,
      long deadlineNanos)
      throws E {
    return enqueueTimed(key, task, future, type, ErrorPolicy.TYPED, waitNanos, deadlineNanos);
  }

  private <R, E extends Throwable> R enqueueTimed(
      String key,
      Runnable task,
      CompletableFuture<R> future,
      Class<E> type,
      ErrorPolicy policy,
      long waitNanos,
      long deadlineNanos)
      throws E {
    submitSync(key, task);
    try {
      return SyncWait.awaitFuture(future, deadlineNanos);
    } catch (InterruptedException e) {
      // CompletableFuture ignores mayInterruptIfRunning (no runner interruption): cancel(false)
      // only discards the late result, the submitted work stays submitted for dedup via key/future.
      future.cancel(false);
      throw SyncWait.syncException(e, key, future);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause == null) {
        throw new CompletionException(e);
      }
      // Typed unwrapping without sneaky throws: the witness-narrowed failure propagates via the
      // declared `throws E`; everything else converges on the unchecked single entry.
      if (policy == ErrorPolicy.TYPED && type != null && type.isInstance(cause)) {
        throw type.cast(cause);
      }
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(cause);
    } catch (TimeoutException e) {
      // Same as above: discard the late result only, never imply runner interruption.
      future.cancel(false);
      throw SyncWait.timeoutFor(key, waitNanos, deadlineNanos, e, future);
    }
  }

  /** Shared single-key sync enqueue: wraps {@code task} in an ordered span. */
  void submitSync(String key, Runnable task) {
    TraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, key, true, task);
    TaskQueue queue = queues.getForSubmit(key);
    queue.execute(selectExecutor(key, queue, runnable, true), runnable);
  }

  /**
   * Selects the runner executor for an already-fenced submission. A throwing selector notifies the
   * submission and releases the fence before propagating so waiters never strand. Private: only the
   * fenced {@code submitSync}/{@code enqueueAsync} paths may resolve executors so fence accounting
   * cannot be bypassed.
   */
  private Executor selectExecutor(String key, TaskQueue queue, Runnable runnable, boolean sync) {
    try {
      Executor selected = selector.getExecutor(queue, sync);
      if (selected == null) {
        throw new IllegalStateException(
            "ExecutorSelector returned null for key '" + KeySanitizer.sanitize(key) + "'");
      }
      return selected;
    } catch (Throwable selectorFailure) {
      try {
        RejectionAware.dispatch(runnable, selectorFailure);
      } catch (Throwable dispatchFailure) {
        if (dispatchFailure != selectorFailure) {
          selectorFailure.addSuppressed(dispatchFailure);
        }
        log.error("Rejection callback failed for selector failure", dispatchFailure);
      }
      try {
        queues.abortSubmit(key, queue);
      } catch (Throwable abortFailure) {
        if (abortFailure != selectorFailure) {
          selectorFailure.addSuppressed(abortFailure);
        }
        log.error("Failed to release submit fence after selector failure", abortFailure);
      }
      throw ErrorPolicy.UNCHECKED.rethrowUnchecked(selectorFailure);
    }
  }

  /**
   * Whether tasks are queued behind the active runner for {@code key}. Fail-closed: a probe failure
   * assumes peers so a nested same-key sync throws instead of silently overtaking them.
   */
  boolean hasQueuedPeers(String key) {
    try {
      return queues.hasPending(key);
    } catch (Error fatal) {
      throw fatal;
    } catch (Throwable probeFailure) {
      log.warn(
          "Peer probe failed for key '{}'; failing closed to preserve FIFO",
          KeySanitizer.sanitize(key),
          probeFailure);
      return true;
    }
  }

  /** Explicit deadlock-vs-overtake failure: nested same-key sync with queued peers. */
  static IllegalStateException overtakeRejected(String key) {
    return new IllegalStateException(
        "Nested sync for key '"
            + KeySanitizer.sanitize(key)
            + "' would overtake queued peers;"
            + " restructure to avoid nested same-key sync while peers are waiting");
  }
}
