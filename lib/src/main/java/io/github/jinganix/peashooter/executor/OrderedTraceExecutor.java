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
import io.github.jinganix.peashooter.ThrowingSupplier;
import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.internal.Keys;
import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.queue.TaskQueue;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import io.github.jinganix.peashooter.trace.OrderedTraceRunnable;
import io.github.jinganix.peashooter.trace.TraceRunnable;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Executes tasks in strict per-key order and records an ordered trace chain.
 *
 * <p>Same-key tasks run one at a time; different keys may run concurrently. See {@link Builder} for
 * construction; behavior contracts live with the delegates ({@link TimeoutPolicy}, {@link
 * ReentrancyGate}, {@link MultiKeyGuard}, {@link SubmissionRouter}).
 */
public class OrderedTraceExecutor {

  /** {@link TaskQueueProvider} */
  private final TaskQueueProvider queues;

  /** {@link Tracer} */
  private final Tracer tracer;

  /** Sync-wait timeout policy (facade delegates). */
  private final TimeoutPolicy timeoutPolicy = new TimeoutPolicy();

  /** Peer-free reentrant inline gate (facade delegates). */
  private final ReentrancyGate reentrancyGate = new ReentrancyGate();

  /** Multi-key sizing guard (facade delegates). */
  private final MultiKeyGuard multiKeyGuard;

  /** Sync/async submission plumbing (facade delegates for enqueue/select/peer paths). */
  private final SubmissionRouter submissions;

  /**
   * Creates an executor with default queues; never takes lifecycle ownership.
   *
   * @param executor backing pool for queued work
   */
  public OrderedTraceExecutor(Executor executor) {
    this(executor, (IntSupplier) null);
  }

  /**
   * Creates an executor with default queues and an explicit pool maximum.
   *
   * @param executor backing pool for queued work
   * @param maxPoolThreads known pool maximum, or {@code null} when unknown
   */
  public OrderedTraceExecutor(Executor executor, IntSupplier maxPoolThreads) {
    this(builder(executor).maxPoolThreads(maxPoolThreads));
  }

  /**
   * Creates an executor with explicit components.
   *
   * @param queues per-key {@link TaskQueue} source
   * @param selector chooses the {@link Executor} for each submission
   * @param tracer records the ordered call chain
   */
  public OrderedTraceExecutor(TaskQueueProvider queues, ExecutorSelector selector, Tracer tracer) {
    this(queues, selector, tracer, null);
  }

  /**
   * Creates an executor with explicit components and pool maximum.
   *
   * @param queues per-key {@link TaskQueue} source
   * @param selector chooses the {@link Executor} for each submission
   * @param tracer records the ordered call chain
   * @param maxPoolThreads known pool maximum, or {@code null} when unknown
   */
  public OrderedTraceExecutor(
      TaskQueueProvider queues,
      ExecutorSelector selector,
      Tracer tracer,
      IntSupplier maxPoolThreads) {
    this.queues = Objects.requireNonNull(queues, "queues");
    Objects.requireNonNull(selector, "selector");
    this.tracer = Objects.requireNonNull(tracer, "tracer");
    this.multiKeyGuard = new MultiKeyGuard(composeMaxThreads(maxPoolThreads, null, selector));
    this.submissions = new SubmissionRouter(this.queues, selector, this.tracer);
  }

  private OrderedTraceExecutor(Builder builder) {
    Objects.requireNonNull(builder, "builder");
    Executor executor = Objects.requireNonNull(builder.backing, "backing");
    TaskQueueProvider resolvedQueues =
        builder.queues != null ? builder.queues : new CaffeineTaskQueueProvider();
    ExecutorSelector selector;
    Tracer resolvedTracer;
    if (builder.selector != null) {
      selector = builder.selector;
      resolvedTracer = builder.tracer != null ? builder.tracer : defaultTracerOf(selector);
    } else if (executor instanceof TraceExecutor traceExecutor) {
      resolvedTracer = builder.tracer != null ? builder.tracer : traceExecutor.getTracer();
      selector = new DefaultExecutorSelector(traceExecutor, true);
    } else {
      resolvedTracer = builder.tracer != null ? builder.tracer : new DefaultTracer();
      selector = new DefaultExecutorSelector(new TraceExecutor(executor, resolvedTracer), true);
    }
    this.queues = resolvedQueues;
    this.tracer = resolvedTracer;
    if (builder.timeout != null) {
      this.timeoutPolicy.setTimeout(builder.timeout);
    }
    // A custom selector decides scheduling, so the builder's backing executor is unused: sizing
    // must not introspect it (a small unused backing pool would otherwise false-fail a multi-key
    // call routed to a sufficient pool). With no custom selector the backing executor is what the
    // derived DefaultExecutorSelector schedules on, so it stays the sizing source.
    Executor sizingExecutor = builder.selector != null ? null : executor;
    this.multiKeyGuard =
        new MultiKeyGuard(composeMaxThreads(builder.maxPoolThreads, sizingExecutor, selector));
    this.submissions = new SubmissionRouter(this.queues, selector, this.tracer);
  }

  private static Tracer defaultTracerOf(ExecutorSelector selector) {
    if (selector instanceof DefaultExecutorSelector defaultSelector) {
      return defaultSelector.tracer();
    }
    return new DefaultTracer();
  }

  /**
   * Starts a builder for an executor on {@code executor}.
   *
   * @param executor backing pool for queued work
   * @return builder, never {@code null}
   */
  public static Builder builder(Executor executor) {
    return new Builder(executor);
  }

  /**
   * Builder for {@link OrderedTraceExecutor}: one place for every construction axis. Details for
   * each axis live with its owner ({@link CaffeineTaskQueueProvider}, {@link
   * DefaultExecutorSelector}, {@link MultiKeyGuard}, {@link TimeoutPolicy}).
   */
  public static final class Builder {
    private final Executor backing;
    private TaskQueueProvider queues;
    private ExecutorSelector selector;
    private Tracer tracer;
    private IntSupplier maxPoolThreads;
    private Duration timeout;

    private Builder(Executor executor) {
      this.backing = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Sets the queues source.
     *
     * @param queues per-key queue source
     * @return this builder
     */
    public Builder queues(TaskQueueProvider queues) {
      this.queues = Objects.requireNonNull(queues, "queues");
      return this;
    }

    /**
     * Sets the executor selector.
     *
     * @param selector chooses the executor per submission
     * @return this builder
     */
    public Builder selector(ExecutorSelector selector) {
      this.selector = Objects.requireNonNull(selector, "selector");
      return this;
    }

    /**
     * Sets the tracer.
     *
     * @param tracer records the ordered call chain
     * @return this builder
     */
    public Builder tracer(Tracer tracer) {
      this.tracer = Objects.requireNonNull(tracer, "tracer");
      return this;
    }

    /**
     * Sets the known pool maximum for multi-key fail-fast sizing.
     *
     * @param maxPoolThreads known maximum, or {@code null} when unknown
     * @return this builder
     */
    public Builder maxPoolThreads(IntSupplier maxPoolThreads) {
      this.maxPoolThreads = maxPoolThreads;
      return this;
    }

    /**
     * Sets the sync-wait timeout.
     *
     * @param timeout maximum wait, must not be negative
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      this.timeout = Objects.requireNonNull(timeout, "timeout");
      return this;
    }

    /**
     * Builds the executor.
     *
     * @return new executor, never {@code null}
     */
    public OrderedTraceExecutor build() {
      return new OrderedTraceExecutor(this);
    }
  }

  private static IntSupplier composeMaxThreads(
      IntSupplier explicit, Executor backingExecutor, ExecutorSelector selector) {
    return () -> {
      if (explicit != null) {
        int supplied;
        try {
          supplied = explicit.getAsInt();
        } catch (Error fatal) {
          throw fatal;
        } catch (Exception expected) {
          return MultiKeyGuard.UNKNOWN_MAX_THREADS;
        }
        return supplied >= 0 ? supplied : MultiKeyGuard.UNKNOWN_MAX_THREADS;
      }
      if (backingExecutor != null) {
        int introspected = MultiKeyGuard.maxThreadsOf(backingExecutor);
        if (introspected >= 0) {
          return introspected;
        }
      }
      if (selector instanceof DefaultExecutorSelector defaultSelector) {
        return defaultSelector.maxThreadsOrUnknown();
      }
      return MultiKeyGuard.UNKNOWN_MAX_THREADS;
    };
  }

  /**
   * Returns cumulative peer-free reentrant inline executions. See {@link ReentrancyGate}.
   *
   * @return cumulative peer-free reentrant inline executions
   */
  public long getReentrantInlineCount() {
    return reentrancyGate.getReentrantInlineCount();
  }

  /**
   * Sets the maximum wait for sync calls. See {@link TimeoutPolicy}.
   *
   * @param timeout maximum wait, must not be negative and must not be {@code null}
   */
  public void setTimeout(Duration timeout) {
    timeoutPolicy.setTimeout(timeout);
  }

  /**
   * Returns the currently configured sync wait timeout.
   *
   * @return timeout, never negative
   */
  public Duration getTimeout() {
    return timeoutPolicy.getTimeout();
  }

  /**
   * Returns the tracer.
   *
   * @return tracer, never {@code null}
   */
  public Tracer getTracer() {
    return tracer;
  }

  private List<String> sortedKeys(Collection<String> keys) {
    List<String> sorted = MultiKeyNesting.lockKeys(keys);
    multiKeyGuard.checkMultiKeyPoolSize(sorted.size());
    return sorted;
  }

  /**
   * Completes {@code future} with a {@code forKey} construction failure, matching the selector
   * contract in {@link SubmissionRouter}: {@link Exception}s complete and return the handle; an
   * {@link Error} completes it then stays loud (no handle returned on the throwing path,
   * deduplicate via the key).
   *
   * @return {@code future} for {@link Exception} paths (the caller returns it); never returns for
   *     {@link Error} paths (the error is rethrown)
   */
  private static <R> CompletableFuture<R> failForKeyConstruction(
      Throwable forKeyFailure, CompletableFuture<R> future) {
    future.completeExceptionally(forKeyFailure);
    if (forKeyFailure instanceof Error err) {
      throw err;
    }
    return future;
  }

  private boolean inlineReentrantReady(String key, boolean allowReentrant) {
    if (!allowReentrant || !reentrancyGate.isReentrant(tracer, key)) {
      return false;
    }
    // Peer probe is fail-closed (probe failure assumes peers and throws). The probe itself holds
    // the queue monitor, so a peer enqueued before the probe is always observed. A peer enqueued
    // after the probe submitted after this inline call, so running inline first preserves
    // submission order rather than overtaking: the window between probe and inline run admits no
    // FIFO violation for same-thread nesting (span storage is per-thread; cross-thread span
    // sharing with concurrent same-key execution is unsupported).
    if (submissions.hasQueuedPeers(key)) {
      throw SubmissionRouter.overtakeRejected(key);
    }
    return true;
  }

  /**
   * Returns whether no tasks are queued and no runner is in flight for {@code key}.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @return {@code true} when idle
   */
  public boolean isIdle(String key) {
    return queues.isIdle(Keys.requireKey(key));
  }

  /**
   * Enqueues a task for ordered async execution. See {@link SubmissionRouter} for failure contract.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @param task work to run; must not be {@code null}
   */
  public void executeAsync(String key, Runnable task) {
    Keys.requireKey(key);
    Objects.requireNonNull(task, "task");
    submissions.executeAsync(key, OrderedTraceRunnable.forKey(getTracer(), key, false, task));
  }

  /**
   * Enqueues a task for ordered async execution.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @param task work to run; must not be {@code null}
   * @return future completed after the task runs, exceptionally on failure
   */
  public CompletableFuture<Void> submitAsync(String key, Runnable task) {
    Keys.requireKey(key);
    Objects.requireNonNull(task, "task");
    return enqueueAsync(
        key,
        future ->
            OrderedTraceRunnable.forKey(
                getTracer(), key, false, new SyncCallbacks.RunnableCallback(future, task)));
  }

  /**
   * Enqueues a supplier for ordered async execution.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @return future completed with the supplier result, exceptionally on failure
   */
  public <R> CompletableFuture<R> supplyAsync(String key, Supplier<R> supplier) {
    Keys.requireKey(key);
    Objects.requireNonNull(supplier, "supplier");
    return enqueueAsync(
        key,
        future ->
            OrderedTraceRunnable.forKey(
                getTracer(),
                key,
                false,
                new SyncCallbacks.SyncSupplierCallback<>(future, supplier)));
  }

  /**
   * Enqueues a checked supplier for ordered async execution.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @param <E> checked failure type (carried by the future, never thrown)
   * @return future completed with the supplier result, exceptionally on failure
   */
  public <R, E extends Throwable> CompletableFuture<R> supplyCheckedAsync(
      String key, ThrowingSupplier<R, E> supplier) {
    Keys.requireKey(key);
    Objects.requireNonNull(supplier, "supplier");
    return enqueueAsync(
        key,
        future ->
            OrderedTraceRunnable.forKey(
                getTracer(),
                key,
                false,
                new SyncCallbacks.CheckedSupplierCallback<>(future, supplier)));
  }

  /**
   * Shared async submission plumbing: builds the ordered runnable from the future and the failure
   * contract, then enqueues it. Consolidating the three async shapes here keeps the for-key failure
   * policy identical across them.
   *
   * @param key ordering key (already validated by the caller)
   * @param wrap builds the ordered runnable around the future
   * @param <R> result type
   * @return future completed after the task runs
   */
  private <R> CompletableFuture<R> enqueueAsync(
      String key, Function<CompletableFuture<R>, TraceRunnable> wrap) {
    CompletableFuture<R> future = new CompletableFuture<>();
    TraceRunnable runnable;
    try {
      runnable = wrap.apply(future);
    } catch (Throwable forKeyFailure) {
      return failForKeyConstruction(forKeyFailure, future);
    }
    return submissions.enqueueAsync(key, runnable, future);
  }

  /**
   * Enqueues a task while holding each key in turn. See {@link MultiKeyNesting}.
   *
   * @param keys ordering keys; must not be {@code null} or empty, no element may be {@code null},
   *     empty, or blank
   * @param task work to run; must not be {@code null}
   * @return future completed after the chain runs, exceptionally on failure
   * @throws IllegalArgumentException when {@code keys} is empty
   * @throws IllegalStateException when the known pool maximum is below the distinct key count
   */
  public CompletableFuture<Void> submitAsync(Collection<String> keys, Runnable task) {
    Objects.requireNonNull(task, "task");
    List<String> sorted = sortedKeys(keys);
    if (sorted.size() == 1) {
      return submitAsync(sorted.get(0), task);
    }
    long waitNanos = timeoutPolicy.waitNanos();
    long deadlineNanos = SyncWait.deadlineOf(waitNanos);
    Runnable chain =
        MultiKeyNesting.nestSync(
            sorted.subList(1, sorted.size()),
            task,
            (key, inner) -> executeSyncAt(key, inner, waitNanos, deadlineNanos, false));
    return submitAsync(sorted.get(0), chain);
  }

  /**
   * Enqueues a supplier while holding each key in turn. See {@link MultiKeyNesting}.
   *
   * @param keys ordering keys; must not be {@code null} or empty, no element may be {@code null},
   *     empty, or blank
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @return future completed with the supplier result, exceptionally on failure
   * @throws IllegalArgumentException when {@code keys} is empty
   * @throws IllegalStateException when the known pool maximum is below the distinct key count
   */
  public <R> CompletableFuture<R> supplyAsync(Collection<String> keys, Supplier<R> supplier) {
    Objects.requireNonNull(supplier, "supplier");
    List<String> sorted = sortedKeys(keys);
    if (sorted.size() == 1) {
      return supplyAsync(sorted.get(0), supplier);
    }
    long waitNanos = timeoutPolicy.waitNanos();
    long deadlineNanos = SyncWait.deadlineOf(waitNanos);
    Supplier<R> chain =
        MultiKeyNesting.nestSupply(
            sorted.subList(1, sorted.size()),
            supplier,
            (key, inner) -> supplyAt(key, inner, waitNanos, deadlineNanos, false));
    return supplyAsync(sorted.get(0), chain);
  }

  /**
   * Enqueues a checked supplier while holding each key in turn. See {@link MultiKeyNesting}.
   *
   * @param keys ordering keys; must not be {@code null} or empty, no element may be {@code null},
   *     empty, or blank
   * @param type witness for the declared checked failure type
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @param <E> checked failure type (carried by the future, never thrown)
   * @return future completed with the supplier result, exceptionally on failure
   * @throws IllegalArgumentException when {@code keys} is empty
   * @throws IllegalStateException when the known pool maximum is below the distinct key count
   */
  public <R, E extends Throwable> CompletableFuture<R> supplyCheckedAsync(
      Collection<String> keys, Class<E> type, ThrowingSupplier<R, E> supplier) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(supplier, "supplier");
    List<String> sorted = sortedKeys(keys);
    if (sorted.size() == 1) {
      return supplyCheckedAsync(sorted.get(0), supplier);
    }
    long waitNanos = timeoutPolicy.waitNanos();
    long deadlineNanos = SyncWait.deadlineOf(waitNanos);
    ThrowingSupplier<R, E> chain =
        MultiKeyNesting.nestChecked(
            sorted.subList(1, sorted.size()),
            supplier,
            (key, inner) -> supplyCheckedAt(key, type, inner, waitNanos, deadlineNanos, false));
    return supplyCheckedAsync(sorted.get(0), chain);
  }

  /**
   * Runs a task under {@code key}, blocking until done. See {@link SubmissionRouter} and {@link
   * ReentrancyGate} for FIFO and timeout contracts.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @param task work to run; must not be {@code null}
   * @throws IllegalStateException if nested on {@code key} while peers are queued
   * @throws TraceTimeoutException if the wait exceeds {@link #setTimeout(Duration)}
   * @throws RuntimeException if the delegate throws (original unchecked type preserved)
   * @throws Error if the delegate throws (original error preserved)
   */
  public void executeSync(String key, Runnable task) {
    Keys.requireKey(key);
    Objects.requireNonNull(task, "task");
    long waitNanos = timeoutPolicy.waitNanos();
    executeSyncAt(key, task, waitNanos, SyncWait.deadlineOf(waitNanos), true);
  }

  /**
   * Single-key sync submission against a shared deadline (used by multi-key nesting so the
   * configured timeout bounds the whole call, not each level).
   *
   * @param allowReentrant whether peer-free nested same-key sync may run inline; multi-key nesting
   *     passes {@code false} so every level enqueues and the global sorted acquisition order holds
   *     (an overlapping nested multi-key call queues behind itself and times out instead of
   *     silently skipping the sorted order)
   */
  private void executeSyncAt(
      String key, Runnable task, long waitNanos, long deadlineNanos, boolean allowReentrant) {
    if (inlineReentrantReady(key, allowReentrant)) {
      reentrancyGate.runReentrantSync(tracer, key, task);
      return;
    }
    CompletableFuture<Void> future = new CompletableFuture<>();
    submissions.enqueueSync(
        key, new SyncCallbacks.RunnableCallback(future, task), future, waitNanos, deadlineNanos);
  }

  /**
   * Runs a task while holding each key in turn. See {@link MultiKeyNesting}.
   *
   * @param keys ordering keys; no element may be {@code null}, empty, or blank
   * @param task work to run; must not be {@code null}
   * @throws IllegalArgumentException when {@code keys} is empty
   * @throws IllegalStateException when the known pool maximum is below the distinct key count
   */
  public void executeSync(Collection<String> keys, Runnable task) {
    Objects.requireNonNull(task, "task");
    List<String> sorted = sortedKeys(keys);
    if (sorted.size() == 1) {
      executeSync(sorted.get(0), task);
      return;
    }
    long waitNanos = timeoutPolicy.waitNanos();
    long deadlineNanos = SyncWait.deadlineOf(waitNanos);
    MultiKeyNesting.nestSync(
            sorted,
            task,
            (key, inner) -> executeSyncAt(key, inner, waitNanos, deadlineNanos, false))
        .run();
  }

  /**
   * Runs a supplier under {@code key}, blocking until a result is available.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @return supplier result
   * @throws IllegalStateException if nested on {@code key} while peers are queued
   * @throws TraceTimeoutException if the wait exceeds {@link #setTimeout(Duration)}
   * @throws CompletionException wrapping any checked failure from the supplier as its cause
   * @throws Error if the supplier throws (original error preserved)
   */
  public <R> R supply(String key, Supplier<R> supplier) {
    Keys.requireKey(key);
    Objects.requireNonNull(supplier, "supplier");
    long waitNanos = timeoutPolicy.waitNanos();
    return supplyAt(key, supplier, waitNanos, SyncWait.deadlineOf(waitNanos), true);
  }

  /**
   * Single-key supply against a shared deadline; see {@link #executeSyncAt}.
   *
   * @param allowReentrant whether peer-free nested same-key sync may run inline
   */
  private <R> R supplyAt(
      String key,
      Supplier<R> supplier,
      long waitNanos,
      long deadlineNanos,
      boolean allowReentrant) {
    if (inlineReentrantReady(key, allowReentrant)) {
      return reentrancyGate.runReentrantSync(tracer, key, supplier);
    }
    CompletableFuture<R> future = new CompletableFuture<>();
    return submissions.enqueueSync(
        key,
        new SyncCallbacks.SyncSupplierCallback<>(future, supplier),
        future,
        waitNanos,
        deadlineNanos);
  }

  /**
   * Runs a checked supplier under {@code key}, blocking until a result is available.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @param type witness for the declared checked failure type
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @param <E> checked failure type
   * @return supplier result
   * @throws E if the supplier throws its declared failure
   * @throws IllegalStateException if nested on {@code key} while peers are queued
   * @throws TraceTimeoutException if the wait exceeds {@link #setTimeout(Duration)}
   * @throws Error if the supplier throws (original error preserved)
   */
  public <R, E extends Throwable> R supplyChecked(
      String key, Class<E> type, ThrowingSupplier<R, E> supplier) throws E {
    Keys.requireKey(key);
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(supplier, "supplier");
    long waitNanos = timeoutPolicy.waitNanos();
    return supplyCheckedAt(key, type, supplier, waitNanos, SyncWait.deadlineOf(waitNanos), true);
  }

  /**
   * Single-key checked supply against a shared deadline; see {@link #executeSyncAt}.
   *
   * @param allowReentrant whether peer-free nested same-key sync may run inline
   */
  private <R, E extends Throwable> R supplyCheckedAt(
      String key,
      Class<E> type,
      ThrowingSupplier<R, E> supplier,
      long waitNanos,
      long deadlineNanos,
      boolean allowReentrant)
      throws E {
    if (inlineReentrantReady(key, allowReentrant)) {
      return reentrancyGate.runReentrantChecked(tracer, key, type, supplier);
    }
    CompletableFuture<R> future = new CompletableFuture<>();
    return submissions.enqueueChecked(
        key,
        new SyncCallbacks.CheckedSupplierCallback<>(future, supplier),
        future,
        type,
        waitNanos,
        deadlineNanos);
  }

  /**
   * Runs a supplier while holding each key in turn. See {@link MultiKeyNesting}.
   *
   * @param keys ordering keys; no element may be {@code null}, empty, or blank
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @return supplier result
   * @throws IllegalArgumentException when {@code keys} is empty
   * @throws IllegalStateException when the known pool maximum is below the distinct key count
   */
  public <R> R supply(Collection<String> keys, Supplier<R> supplier) {
    Objects.requireNonNull(supplier, "supplier");
    List<String> sorted = sortedKeys(keys);
    if (sorted.size() == 1) {
      return supply(sorted.get(0), supplier);
    }
    long waitNanos = timeoutPolicy.waitNanos();
    long deadlineNanos = SyncWait.deadlineOf(waitNanos);
    return MultiKeyNesting.nestSupply(
            sorted, supplier, (key, inner) -> supplyAt(key, inner, waitNanos, deadlineNanos, false))
        .get();
  }

  /**
   * Runs a checked supplier while holding each key in turn. See {@link MultiKeyNesting}.
   *
   * @param keys ordering keys; no element may be {@code null}, empty, or blank
   * @param type witness for the declared checked failure type
   * @param supplier work to run; must not be {@code null}
   * @param <R> result type
   * @param <E> checked failure type
   * @return supplier result
   * @throws E if the supplier throws its declared failure
   * @throws IllegalArgumentException when {@code keys} is empty
   * @throws IllegalStateException when the known pool maximum is below the distinct key count
   */
  public <R, E extends Throwable> R supplyChecked(
      Collection<String> keys, Class<E> type, ThrowingSupplier<R, E> supplier) throws E {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(supplier, "supplier");
    List<String> sorted = sortedKeys(keys);
    if (sorted.size() == 1) {
      return supplyChecked(sorted.get(0), type, supplier);
    }
    long waitNanos = timeoutPolicy.waitNanos();
    long deadlineNanos = SyncWait.deadlineOf(waitNanos);
    return MultiKeyNesting.nestChecked(
            sorted,
            supplier,
            (key, inner) -> supplyCheckedAt(key, type, inner, waitNanos, deadlineNanos, false))
        .get();
  }
}
