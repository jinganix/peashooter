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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Base for unchecked synchronous-wait failures ({@link TraceTimeoutException}, {@link
 * TraceInterruptedException}).
 *
 * <p>Public so callers can {@code catch (TraceWaitException)} to handle timeout and interruption
 * together. Deliberately a plain abstract class, not sealed: callers may extend it for custom wait
 * failures (e.g. deadline-exceeded with extra context) without forking the hierarchy.
 *
 * <p><b>Extension contract:</b> subclasses must pass a non-null wait cause, ordering key, and
 * submission future to {@link #TraceWaitException(String, Exception, String, CompletableFuture)};
 * {@link #getKey} and {@link #getFuture} stay non-null in memory. Java serialization stays rejected
 * (the future is transient); propagate {@link #getKey()} across process boundaries instead.
 *
 * <p><b>Timeout/interruption means submitted:</b> the wait only unblocked the caller — the work
 * stays submitted and may still complete late. Use {@link #getKey} as the idempotency key and
 * {@link #getFuture} to observe or cancel the original submission.
 *
 * <p><b>Java serialization is not supported:</b> the submission {@link CompletableFuture} is not
 * serializable, so serializing this failure would silently drop it and break the non-null {@link
 * #getFuture} contract after deserialization. Both writing and reading throw ({@link
 * java.io.NotSerializableException} / {@link java.io.InvalidObjectException}) instead of producing
 * a half-restored instance. Propagate {@link #getKey()} (a plain string) across process boundaries
 * instead.
 */
public abstract class TraceWaitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Ordering key that was interrupted or timed out. */
  private final String key;

  /**
   * Submission future for the wait.
   *
   * <p>{@code transient} because {@link CompletableFuture} is not {@link java.io.Serializable}:
   * after deserialization this reads {@code null}.
   */
  private final transient CompletableFuture<?> future;

  /**
   * Constructor.
   *
   * @param message failure description
   * @param cause wait cause, must not be {@code null}
   * @param key ordering key, must not be {@code null}
   * @param future submission future, must not be {@code null}
   */
  protected TraceWaitException(
      String message, Exception cause, String key, CompletableFuture<?> future) {
    super(message, Objects.requireNonNull(cause, "cause"));
    this.key = Objects.requireNonNull(key, "key");
    this.future = Objects.requireNonNull(future, "future");
  }

  /**
   * Ordering key.
   *
   * @return key, never {@code null} in memory
   */
  public String getKey() {
    return key;
  }

  /**
   * Submission future for the wait.
   *
   * @return submission future, never {@code null} (Java serialization is rejected, so no
   *     half-restored instance can exist)
   */
  public CompletableFuture<?> getFuture() {
    return future;
  }

  /**
   * Returns the cause narrowed to its static type.
   *
   * @param type expected cause type
   * @param description human-readable cause description used in the failure message (including the
   *     article, e.g. {@code "a TimeoutException"})
   * @param <C> expected cause type
   * @return cause narrowed to {@code C}, never {@code null}
   * @throws IllegalStateException when the cause is foreign
   */
  protected final <C extends Exception> C expectedCause(Class<C> type, String description) {
    return narrowCause(getCause(), type, description);
  }

  // Private: foreign causes are impossible through the constructors (which require the exact
  // cause type), so this only fires for corrupted state; callers go through the public typed
  // accessors instead.
  private static <C extends Exception> C narrowCause(
      Throwable cause, Class<C> type, String description) {
    if (type.isInstance(cause)) {
      return type.cast(cause);
    }
    throw new IllegalStateException("Cause is not " + description + ": " + cause, cause);
  }

  /**
   * Rejects Java serialization on write: the transient submission future cannot survive the round
   * trip, so writing throws instead of producing a half-restored instance.
   *
   * @param out serialization stream (unused, serialization always rejected)
   * @throws java.io.NotSerializableException always
   */
  private void writeObject(java.io.ObjectOutputStream out) throws java.io.NotSerializableException {
    throw new java.io.NotSerializableException(
        getClass().getSimpleName()
            + " does not support Java serialization; propagate getKey() across processes instead");
  }

  /**
   * Rejects Java serialization on read: no half-restored instance with a {@code null} future may
   * exist, so reading throws instead of materializing one.
   *
   * @param in serialization stream (unused, serialization always rejected)
   * @throws java.io.InvalidObjectException always
   */
  private void readObject(java.io.ObjectInputStream in) throws java.io.InvalidObjectException {
    throw new java.io.InvalidObjectException(
        getClass().getSimpleName()
            + " does not support Java serialization; propagate getKey() across processes instead");
  }
}
