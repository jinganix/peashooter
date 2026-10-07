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

import io.github.jinganix.peashooter.internal.Interruptions;
import java.util.concurrent.CompletionException;

/**
 * Error mapping for the unified scope template.
 *
 * <p>Single owner for the Runtime/Error/checked three-branch: unchecked paths wrap checked failures
 * in {@link CompletionException} via {@link #rethrowUnchecked}, callable paths unwrap {@link
 * Exception}, typed paths unwrap only the witnessed type. {@link RuntimeException}s and {@link
 * Error}s always propagate as-is with interrupt status preserved for delegate interruptions.
 *
 * <p>No sneaky throws: checked failures that keep their static type propagate only through methods
 * declaring {@code throws} ({@link #rethrowDelegate} declares {@code throws E, Exception} and
 * throws the narrowed instance directly). Callers on unchecked paths must use {@link
 * #rethrowUnchecked}, which never throws checked.
 */
public enum ErrorPolicy {
  /** Unchecked only: checked failures surface wrapped. */
  UNCHECKED,
  /** Callable: checked {@link Exception}s propagate unwrapped. */
  CALLABLE,
  /** Typed: only the witnessed type propagates unwrapped. */
  TYPED;

  /**
   * Unchecked translation single entry: {@link RuntimeException}s and {@link Error}s propagate
   * as-is (delegate interruptions also restore the interrupt flag); every other {@link Throwable}
   * surfaces wrapped in a {@link CompletionException}. Never throws checked.
   *
   * @param failure failure to translate, must not be {@code null}
   * @return never returns normally; always throws the translated failure
   */
  public RuntimeException rethrowUnchecked(Throwable failure) {
    if (Interruptions.carriesInterrupt(failure)) {
      Thread.currentThread().interrupt();
    }
    if (failure instanceof RuntimeException re) {
      throw re;
    }
    if (failure instanceof Error err) {
      throw err;
    }
    throw new CompletionException(failure);
  }

  /**
   * Rethrows a delegate failure per this policy, preserving interrupt status for {@link
   * InterruptedException}. Checked failures propagate unwrapped only where declared: {@link
   * Exception}s for {@link #CALLABLE}, the witnessed type for {@link #TYPED}; everything else
   * checked surfaces wrapped in a {@link CompletionException}.
   *
   * @param failure delegate failure, must not be {@code null}
   * @param type witness for {@link #TYPED}, or {@code null} otherwise
   * @param <E> witnessed checked failure type
   * @return never returns normally; always throws the translated failure
   * @throws E when {@code failure} is the witnessed type and this policy is {@link #TYPED}
   * @throws Exception when {@code failure} is a checked {@link Exception} and this policy is {@link
   *     #CALLABLE}
   */
  public <E extends Throwable> RuntimeException rethrowDelegate(Throwable failure, Class<E> type)
      throws E, Exception {
    if (Interruptions.carriesInterrupt(failure)) {
      Thread.currentThread().interrupt();
    }
    if (failure instanceof RuntimeException re) {
      throw re;
    }
    if (failure instanceof Error err) {
      throw err;
    }
    if (this == CALLABLE && failure instanceof Exception ex) {
      throw ex;
    }
    if (this == TYPED && type != null && type.isInstance(failure)) {
      throw type.cast(failure);
    }
    throw new CompletionException(failure);
  }

  /**
   * Rethrows a finish-pending failure (afterCall/restore) per this policy.
   *
   * @param pending pending failure, must not be {@code null}
   * @param type witness for {@link #TYPED}, or {@code null} otherwise
   * @param <E> witnessed checked failure type
   * @return never returns normally; always throws the translated failure
   * @throws E when {@code pending} is the witnessed type and this policy is {@link #TYPED}
   * @throws Exception when {@code pending} is a checked {@link Exception} and this policy is {@link
   *     #CALLABLE}
   */
  public <E extends Throwable> RuntimeException rethrowPending(Throwable pending, Class<E> type)
      throws E, Exception {
    throw rethrowDelegate(pending, type);
  }
}
