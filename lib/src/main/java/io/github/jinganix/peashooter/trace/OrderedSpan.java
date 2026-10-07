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
import io.github.jinganix.peashooter.internal.KeySanitizer;
import io.github.jinganix.peashooter.internal.Keys;
import java.util.Objects;

/**
 * {@link Span} tagged with the per-key ordering key and whether the call was synchronous.
 *
 * <p>Created by {@link OrderedTraceRunnable} for each ordered submission. The {@code sync} flag
 * participates in {@link #invokedBy(Span, String)}: a span matching the nested key is detected as
 * reentrant, and {@link io.github.jinganix.peashooter.executor.OrderedTraceExecutor} fails fast
 * instead of running inline, preserving strict per-key FIFO. An async span with a different key
 * stops the chain walk.
 *
 * <p>Equality is identity, inherited from {@link Span}: key/sync never participate because no
 * value-based {@code equals} exists to narrow. Use {@link SpanIdKey} to look spans up by ids.
 */
public final class OrderedSpan extends Span {

  private final String key;

  private final boolean sync;

  private OrderedSpan(String traceId, String spanId, Span parent, String key, boolean sync) {
    super(traceId, spanId, parent);
    // No key validation here: public factories validate once via requireKey, and the
    // validated-key path below skips it entirely, so the hot path pays exactly one check.
    this.key = key;
    this.sync = sync;
  }

  /**
   * Ordered child span inheriting the parent trace id (or generating one at the root).
   *
   * <p>Ids are validated the same way as {@link Span#child}: a broken {@link TraceIdGenerator} (or
   * an unchecked parent carrying an invalid trace id) fails fast here instead of producing a span
   * that only fails at propagation.
   *
   * @param traceIdGenerator {@link TraceIdGenerator}
   * @param parent parent {@link Span}
   * @param key trace key
   * @param sync true if a sync call
   * @return new span
   * @throws NullPointerException if {@code traceIdGenerator} or {@code key} is {@code null}
   * @throws IllegalArgumentException if either id is not W3C-compatible
   */
  public static OrderedSpan child(
      TraceIdGenerator traceIdGenerator, Span parent, String key, boolean sync) {
    Objects.requireNonNull(traceIdGenerator, "traceIdGenerator");
    Keys.requireKey(key);
    // The super constructor is the single owner of id validation: both ids are validated there,
    // so re-validating them here would scan each id twice on every span creation.
    return new OrderedSpan(
        Span.resolveTraceId(traceIdGenerator, parent),
        Span.nextSpanId(traceIdGenerator),
        parent,
        key,
        sync);
  }

  /**
   * Ordered child span with a forced trace id.
   *
   * <p>See {@link Span#continueTrace}: the given {@code traceId} always wins, even when {@code
   * parent} carries a different one, while the parent link (and therefore {@link #invokedBy(Span,
   * String)}) is preserved.
   *
   * @param traceId forced trace id, validated as W3C-compatible (not passed through as-is)
   * @param generator {@link TraceIdGenerator} used only for the new span id
   * @param parent parent {@link Span}, kept for chain linkage
   * @param key trace key
   * @param sync true if a sync call
   * @return new span
   * @throws NullPointerException if {@code traceId}, {@code generator}, or {@code key} is {@code
   *     null}
   * @throws IllegalArgumentException if either id is not W3C-compatible
   */
  public static OrderedSpan continueTrace(
      String traceId, TraceIdGenerator generator, Span parent, String key, boolean sync) {
    Objects.requireNonNull(generator, "generator");
    Keys.requireKey(key);
    // Forced trace id and generated span id are validated by the super constructor (single owner).
    return new OrderedSpan(traceId, Span.nextSpanId(generator), parent, key, sync);
  }

  /**
   * Ordered child span for an already-validated key.
   *
   * <p>Package-private hot-path entry for {@link OrderedTraceRunnable}, which validates the key
   * once at construction: skips the second {@code requireKey} the public factories pay, so one
   * submission pays exactly one key check.
   */
  static OrderedSpan childForValidatedKey(
      TraceIdGenerator traceIdGenerator, Span parent, String key, boolean sync) {
    return new OrderedSpan(
        Span.resolveTraceId(traceIdGenerator, parent),
        Span.nextSpanId(traceIdGenerator),
        parent,
        key,
        sync);
  }

  /**
   * Ordered forced-trace span for an already-validated key; see {@link
   * #childForValidatedKey(TraceIdGenerator, Span, String, boolean)}.
   */
  static OrderedSpan continueForValidatedKey(
      String traceId, TraceIdGenerator generator, Span parent, String key, boolean sync) {
    return new OrderedSpan(traceId, Span.nextSpanId(generator), parent, key, sync);
  }

  /**
   * Get the per-key ordering identifier.
   *
   * @return ordering key
   */
  public String getKey() {
    return key;
  }

  /**
   * Whether this span was created by a synchronous submission.
   *
   * @return {@code true} for sync paths
   */
  public boolean isSync() {
    return sync;
  }

  @Override
  public String toString() {
    return "OrderedSpan{traceId="
        + getTraceId()
        + ", spanId="
        + getSpanId()
        + ", key="
        + KeySanitizer.sanitize(key)
        + ", sync="
        + sync
        + "}";
  }

  /**
   * Maximum chain links walked by {@link #invokedBy}. Span depth is bounded by Java stack depth in
   * practice, but an adversarial or leaked chain must not pin the submit thread in an unbounded
   * walk: past this bound the probe fails closed (reports reentrant) so FIFO is preserved at the
   * cost of forfeiting one peer-free inline fast path.
   */
  static final int MAX_INVOKED_BY_WALK = 4096;

  /**
   * Whether a nested {@code executeSync}/{@code supply} for {@code key} would deadlock or overtake
   * queued peers if enqueued.
   *
   * <p>Walks the active span chain from {@code span} toward the root:
   *
   * <ul>
   *   <li>Matching {@code OrderedSpan} key (sync or async) → {@code true} (reentrant; the caller
   *       already holds {@code key}, so enqueueing would wait behind itself).
   *   <li>Async {@code OrderedSpan} with a different key → {@code false} (stop; async breaks
   *       reentrancy).
   *   <li>Sync {@code OrderedSpan} with a different key → continue to parent (multi-key nesting).
   * </ul>
   *
   * <p><b>Ordering guarantee:</b> {@link
   * io.github.jinganix.peashooter.executor.OrderedTraceExecutor} never silently overtakes on {@code
   * true}: with queued peers it fails fast with {@link IllegalStateException} so strict per-key
   * FIFO holds; peer-free it runs inline to avoid self-deadlock without overtaking anyone.
   * Restructure code to avoid nested same-key sync while peers are waiting.
   *
   * @param span current {@link Span}, or {@code null}
   * @param key ordered trace key, already validated by the caller (executor entry points validate
   *     once at construction; no validation here so the reentrancy probe stays cheap)
   * @return {@code true} if the sync call is nested on an already-held key
   */
  public static boolean invokedBy(Span span, String key) {
    // Iterative chain walk: span chains grow with nesting depth, recursion would risk
    // StackOverflowError on deep (e.g. recursive) sync nesting.
    // Keys are non-null (enforced in factories), so direct equals avoids Objects overhead.
    int walked = 0;
    for (Span current = span; current != null; current = current.getParent()) {
      if (++walked > MAX_INVOKED_BY_WALK) {
        // Fail closed: assume reentrant rather than silently overtaking after an unbounded scan.
        return true;
      }
      if (current instanceof OrderedSpan orderedSpan) {
        boolean match = orderedSpan.key.equals(key);
        if (!orderedSpan.sync && !match) {
          return false;
        }
        if (match) {
          return true;
        }
      }
    }
    return false;
  }
}
