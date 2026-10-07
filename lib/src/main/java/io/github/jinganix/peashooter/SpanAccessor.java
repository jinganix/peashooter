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

package io.github.jinganix.peashooter;

import io.github.jinganix.peashooter.trace.Span;

/**
 * Per-thread {@link Span} storage.
 *
 * <p>Extracted from {@link Tracer} so bridges that only own span storage (id generation and
 * callbacks live elsewhere) can implement this narrow interface and compose it into a full {@link
 * Tracer} via delegation.
 */
public interface SpanAccessor {

  /**
   * Get a {@link Span}.
   *
   * @return current span, or {@code null} when no span is set on this thread
   */
  Span getSpan();

  /**
   * Set a {@link Span}.
   *
   * @param span span to store, or {@code null} to clear (equivalent to {@link #clearSpan()})
   */
  void setSpan(Span span);

  /** Clear the stored {@link Span}. */
  void clearSpan();

  /**
   * Unchecked scope handle for try-with-resources. Narrows {@link AutoCloseable#close} to no
   * checked exceptions so callers never catch {@code Exception} for unchecked span-storage
   * failures.
   */
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Opens a {@link Span} scope for try-with-resources: installs {@code span} and restores the
   * previous span on {@link Scope#close}. Guarantees pooled threads never leak spans when callers
   * forget {@link #clearSpan}.
   *
   * <p>Exception-safe: if installing {@code span} throws, the previous span is restored before the
   * failure propagates (with any restore failure suppressed).
   *
   * <p>The returned scope is single-use on the calling thread: close it exactly once via
   * try-with-resources on the same thread; a second close is a no-op, and closing from another
   * thread is unsupported.
   *
   * <pre>{@code
   * try (var scope = tracer.scope(span)) {
   *   // work with span installed
   * }
   * }</pre>
   *
   * @param span span to install, or {@code null} to clear
   * @return scope restoring the previous span on close
   */
  default Scope scope(Span span) {
    Span previous = getSpan();
    try {
      setSpan(span);
    } catch (Throwable installFailure) {
      try {
        if (previous == null) {
          clearSpan();
        } else {
          setSpan(previous);
        }
      } catch (Throwable restoreFailure) {
        if (restoreFailure != installFailure) {
          installFailure.addSuppressed(restoreFailure);
        }
      }
      throw installFailure;
    }
    return new SpanScope(this, previous);
  }
}
