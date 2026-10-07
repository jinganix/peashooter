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

import io.github.jinganix.peashooter.ThrowingSupplier;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runnable notified when its submission is rejected without running it.
 *
 * <p>Located in the {@code queue} package (not {@code executor}) so {@link TaskQueue} does not
 * depend on the {@code executor} package: queue &rarr; executor was a package cycle (executor
 * already depends on queue for {@link TaskQueue}).
 *
 * <p>Dispatch stays strongly typed: one overload per supported delegate shape ({@link Runnable},
 * {@link Supplier}, {@link ThrowingSupplier}). There is deliberately no {@code dispatch(Object,
 * ...)} entry: a weak untyped shim hides wrong-type call sites instead of failing at compile time.
 */
@FunctionalInterface
public interface RejectionAware {

  /**
   * Called when the enclosing {@link TaskQueue} rejects this task without running it.
   *
   * <p>Only the rejected submission is notified; every other pending task is preserved in order.
   * The cause is the failure reported by {@link java.util.concurrent.Executor#execute(Runnable)},
   * including {@link Error}s: sync waiters observe the original {@link Throwable} via their futures
   * regardless of this callback.
   *
   * @param cause rejection or failure reported by {@link
   *     java.util.concurrent.Executor#execute(Runnable)}
   */
  void rejected(Throwable cause);

  /**
   * Forwards {@code cause} to {@code delegate} when it is {@link RejectionAware}.
   *
   * @param delegate task body that may implement {@link RejectionAware}; must not be {@code null}
   * @param cause rejection or failure cause; must not be {@code null}
   * @return {@code true} when the delegate was notified
   */
  static boolean dispatch(Runnable delegate, Throwable cause) {
    Objects.requireNonNull(delegate, "delegate");
    Objects.requireNonNull(cause, "cause");
    if (delegate instanceof RejectionAware aware) {
      aware.rejected(cause);
      return true;
    }
    return false;
  }

  /**
   * Forwards {@code cause} to {@code delegate} when it is {@link RejectionAware}.
   *
   * @param delegate task body that may implement {@link RejectionAware}; must not be {@code null}
   * @param cause rejection or failure cause; must not be {@code null}
   * @return {@code true} when the delegate was notified
   */
  static boolean dispatch(Supplier<?> delegate, Throwable cause) {
    Objects.requireNonNull(delegate, "delegate");
    Objects.requireNonNull(cause, "cause");
    if (delegate instanceof RejectionAware aware) {
      aware.rejected(cause);
      return true;
    }
    return false;
  }

  /**
   * Forwards {@code cause} to {@code delegate} when it is {@link RejectionAware}.
   *
   * @param delegate task body that may implement {@link RejectionAware}; must not be {@code null}
   * @param cause rejection or failure cause; must not be {@code null}
   * @return {@code true} when the delegate was notified
   */
  static boolean dispatch(ThrowingSupplier<?, ?> delegate, Throwable cause) {
    Objects.requireNonNull(delegate, "delegate");
    Objects.requireNonNull(cause, "cause");
    if (delegate instanceof RejectionAware aware) {
      aware.rejected(cause);
      return true;
    }
    return false;
  }
}
