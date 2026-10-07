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

import io.github.jinganix.peashooter.ThrowingSupplier;
import io.github.jinganix.peashooter.queue.RejectionAware;
import io.github.jinganix.peashooter.trace.ErrorPolicy;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Sync submission callbacks completing a {@link CompletableFuture} and notifying it on queue
 * discard.
 *
 * <p>Owns the future-callback sealed family used by sync and async submissions. All types are
 * package-private; {@link OrderedTraceExecutor} instantiates the concrete callbacks directly.
 */
final class SyncCallbacks {

  private SyncCallbacks() {}

  /**
   * Shared runnable wrapper completing a {@link CompletableFuture} and notifying it on queue
   * discard. Subclasses implement {@link #invoke()} with the success path; failures complete the
   * future exceptionally and rethrow so {@link io.github.jinganix.peashooter.queue.TaskQueue} logs
   * them even when the waiter already timed out.
   *
   * <p>Only {@link CheckedSupplierCallback} adapts throwing work ({@link ThrowingSupplier}); the
   * {@link Runnable} and {@link java.util.function.Supplier} overrides can only throw unchecked.
   * Failure translation converges on {@link ErrorPolicy#rethrowUnchecked(Throwable)} (single
   * three-branch owner): {@link Error} rethrown, {@link RuntimeException} rethrown, any other
   * {@link Throwable} wrapped in a {@link CompletionException} preserving the cause.
   */
  abstract static sealed class FutureCallback<T> implements Runnable, RejectionAware
      permits RunnableCallback, SyncSupplierCallback, CheckedSupplierCallback {

    protected final CompletableFuture<T> future;

    FutureCallback(CompletableFuture<T> future) {
      this.future = Objects.requireNonNull(future, "future");
    }

    /**
     * Success path; must complete {@code future} or throw.
     *
     * <p>Declares {@code throws Throwable} because {@link CheckedSupplierCallback} adapts {@link
     * ThrowingSupplier}, whose checked failures must complete the future untyped; the {@code run}
     * catch maps each failure shape ({@link RuntimeException}, {@link Error}, other {@link
     * Throwable}s) to its propagation without losing the cause: checked failures complete the
     * future with the original cause and rethrow wrapped in a {@link CompletionException}.
     *
     * @throws Throwable delegate failure
     */
    abstract void invoke() throws Throwable;

    @Override
    public final void run() {
      try {
        invoke();
      } catch (Throwable ex) {
        future.completeExceptionally(ex);
        throw ErrorPolicy.UNCHECKED.rethrowUnchecked(ex);
      }
    }

    @Override
    public final void rejected(Throwable cause) {
      future.completeExceptionally(cause);
      dispatchToDelegate(cause);
    }

    /**
     * Forwards a rejection to the typed delegate, when the delegate is {@link RejectionAware}.
     *
     * @param cause rejection cause
     */
    abstract void dispatchToDelegate(Throwable cause);
  }

  /** Runnable callback completing a {@code Void} future. */
  static final class RunnableCallback extends FutureCallback<Void> {

    private final Runnable task;

    RunnableCallback(CompletableFuture<Void> future, Runnable task) {
      super(future);
      this.task = Objects.requireNonNull(task, "task");
    }

    @Override
    void invoke() {
      task.run();
      future.complete(null);
    }

    @Override
    void dispatchToDelegate(Throwable cause) {
      RejectionAware.dispatch(task, cause);
    }
  }

  /** Supplier callback completing a value future. */
  static final class SyncSupplierCallback<R> extends FutureCallback<R> {

    private final Supplier<R> supplier;

    SyncSupplierCallback(CompletableFuture<R> future, Supplier<R> supplier) {
      super(future);
      this.supplier = Objects.requireNonNull(supplier, "supplier");
    }

    @Override
    void invoke() {
      future.complete(supplier.get());
    }

    @Override
    void dispatchToDelegate(Throwable cause) {
      RejectionAware.dispatch(supplier, cause);
    }
  }

  /** Adapts a checked supplier: the original failure completes the future untyped. */
  static final class CheckedSupplierCallback<R> extends FutureCallback<R> {

    private final ThrowingSupplier<R, ?> supplier;

    CheckedSupplierCallback(CompletableFuture<R> future, ThrowingSupplier<R, ?> supplier) {
      super(future);
      this.supplier = Objects.requireNonNull(supplier, "supplier");
    }

    @Override
    void invoke() throws Throwable {
      future.complete(supplier.get());
    }

    @Override
    void dispatchToDelegate(Throwable cause) {
      RejectionAware.dispatch(supplier, cause);
    }
  }
}
