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
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Thrown when a synchronous ordered call waits longer than {@link
 * OrderedTraceExecutor#setTimeout(java.time.Duration)}.
 *
 * <p>Unchecked by design: sync waits fail like {@link java.util.concurrent.CompletionException}
 * sources (runtime failures need no checked catch type), and the {@link #getCause() cause} is
 * always a {@link TimeoutException} for interop with timed-wait utilities.
 *
 * <p>Key, future, and serialization rejection live on {@link TraceWaitException}.
 */
public final class TraceTimeoutException extends TraceWaitException {

  private static final long serialVersionUID = 1L;

  /**
   * Canonical constructor.
   *
   * @param message timeout description including key and configured wait, must not be {@code null}
   * @param cause timeout cause, must not be {@code null}
   * @param key ordering key that timed out, must not be {@code null}
   * @param future submission future for the timed-out work, must not be {@code null}
   */
  public TraceTimeoutException(
      String message, TimeoutException cause, String key, CompletableFuture<?> future) {
    super(message, Objects.requireNonNull(cause, "cause"), key, future);
  }

  /**
   * Creates the standard sync-wait timeout failure, embedding the configured wait, the remaining
   * wait, and a sanitized key in the message (raw key stays in {@link #getKey} for programmatic
   * dedup).
   *
   * @param key ordering key, must not be {@code null}
   * @param waitNanos configured wait in nanos
   * @param remainingNanos remaining wait in nanos
   * @param cause timeout cause, must not be {@code null}
   * @param future submission future, must not be {@code null}
   * @return timeout failure
   */
  public static TraceTimeoutException forTimeout(
      String key,
      long waitNanos,
      long remainingNanos,
      TimeoutException cause,
      CompletableFuture<?> future) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(cause, "cause");
    Objects.requireNonNull(future, "future");
    return new TraceTimeoutException(
        "Timed out after "
            + Duration.ofNanos(waitNanos)
            + " (remaining "
            + Duration.ofNanos(remainingNanos)
            + ") waiting for key '"
            + KeySanitizer.sanitize(key)
            + "': work stays submitted, deduplicate retries via key/future",
        cause,
        key,
        future);
  }

  /**
   * Returns the {@link TimeoutException} cause with its static type.
   *
   * <p>Prefer this over {@link #getCause()} when catching this exception: {@code catch
   * (TimeoutException)} never fires for sync timeouts (this unchecked type is thrown instead), so
   * catch {@code TraceTimeoutException} and read the cause here.
   *
   * @return timeout cause, never {@code null}
   */
  public TimeoutException getTimeoutCause() {
    return expectedCause(TimeoutException.class, "a TimeoutException");
  }
}
