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

import io.github.jinganix.peashooter.internal.KeySanitizer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Thrown when a synchronous ordered call stops waiting because the waiting thread was interrupted.
 *
 * <p>Unchecked by design, matching {@link TraceTimeoutException}: catch this type specifically to
 * distinguish interruption from task failures. The thread's interrupt status is restored before
 * throwing.
 *
 * <p>Key, future, and serialization rejection live on {@link TraceWaitException}.
 */
public final class TraceInterruptedException extends TraceWaitException {

  private static final long serialVersionUID = 1L;

  /**
   * Canonical constructor.
   *
   * @param message interruption description, must not be {@code null}
   * @param cause interrupt cause, must not be {@code null}
   * @param key ordering key that was interrupted, must not be {@code null}
   * @param future submission future for the interrupted wait, must not be {@code null}
   */
  public TraceInterruptedException(
      String message, InterruptedException cause, String key, CompletableFuture<?> future) {
    super(message, Objects.requireNonNull(cause, "cause"), key, future);
  }

  /**
   * Creates the standard sync-wait interruption failure, embedding a sanitized key in the message
   * (raw key stays in {@link #getKey} for programmatic dedup).
   *
   * @param cause interrupt cause, must not be {@code null}
   * @param key ordering key that was interrupted, must not be {@code null}
   * @param future submission future, must not be {@code null}
   * @return interruption failure
   */
  public static TraceInterruptedException forInterrupt(
      InterruptedException cause, String key, CompletableFuture<?> future) {
    Objects.requireNonNull(cause, "cause");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(future, "future");
    return new TraceInterruptedException(
        "Interrupted while waiting for ordered task on key '"
            + KeySanitizer.sanitize(key)
            + "': work stays submitted, deduplicate retries via key/future",
        cause,
        key,
        future);
  }

  /**
   * Returns the {@link InterruptedException} cause with its static type.
   *
   * @return interrupt cause, never {@code null}
   */
  public InterruptedException getInterruptCause() {
    return expectedCause(InterruptedException.class, "an InterruptedException");
  }
}
