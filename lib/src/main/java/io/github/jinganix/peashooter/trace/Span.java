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
import java.util.Objects;

/**
 * Trace task call chain.
 *
 * <p>Id validation is strict: {@link #ofIds}, {@link #child}, and {@link #continueTrace} reject ids
 * that are not W3C-compatible (32/16 lowercase hex, neither all zeros) with an {@link
 * IllegalArgumentException}, so malformed ids fail at construction instead of surfacing late at
 * {@link W3CTraceContext#inject(Span, boolean)} propagation. Tests needing arbitrary ids use the
 * package-private {@link #ofIdsUnchecked} factory in the same package.
 */
public sealed class Span permits OrderedSpan {

  private final Span parent;

  private final String traceId;

  private final String spanId;

  /**
   * Canonical storage constructor for the single permitted subclass {@link OrderedSpan}.
   *
   * <p>Sealed (not protected-for-all): only {@link OrderedSpan} may extend, so the validation below
   * cannot be bypassed by an external subclass. All other construction goes through the strict
   * factories, which validate the same way.
   *
   * @param traceId trace id
   * @param spanId span id for this hop
   * @param parent parent {@link Span}
   */
  protected Span(String traceId, String spanId, Span parent) {
    this(traceId, spanId, parent, true);
  }

  /**
   * Storage constructor with optional validation.
   *
   * @param validate when {@code true} both ids must be W3C-compatible; when {@code false} they are
   *     kept as-is for package-private test factories
   */
  private Span(String traceId, String spanId, Span parent, boolean validate) {
    this.parent = parent;
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(spanId, "spanId");
    if (validate) {
      requireValidTraceId(traceId);
      requireValidSpanId(spanId);
    }
    this.traceId = traceId;
    this.spanId = spanId;
  }

  /**
   * Span with explicit trace and span ids (e.g. from W3C {@code traceparent}).
   *
   * <p>Both ids must be W3C-compatible (32/16 lowercase hex, neither all zeros).
   *
   * @param traceId trace id
   * @param spanId span id for this hop
   * @param parent parent {@link Span}
   * @return new span
   * @throws NullPointerException if {@code traceId} or {@code spanId} is {@code null}
   * @throws IllegalArgumentException if either id is not W3C-compatible
   */
  public static Span ofIds(String traceId, String spanId, Span parent) {
    return new Span(traceId, spanId, parent, true);
  }

  /**
   * Unchecked span with explicit ids, without W3C validation.
   *
   * <p>Package-private test factory for hand-built spans that intentionally carry arbitrary ids:
   * {@link #isValid()} reports {@code false} for such spans, and {@link
   * W3CTraceContext#inject(Span, boolean)} still rejects them at propagation.
   */
  static Span ofIdsUnchecked(String traceId, String spanId, Span parent) {
    return new Span(
        Objects.requireNonNull(traceId, "traceId"),
        Objects.requireNonNull(spanId, "spanId"),
        parent,
        false);
  }

  /**
   * Child span inheriting the parent trace id (or generating one at the root).
   *
   * <p>The inherited or generated ids are validated the same way as {@link #ofIds}: a broken {@link
   * TraceIdGenerator} (or an unchecked parent carrying an invalid trace id) fails fast here instead
   * of producing a span that only fails at propagation.
   *
   * @param traceIdGenerator {@link TraceIdGenerator}
   * @param parent {@link Span}
   * @return new span
   * @throws NullPointerException if {@code traceIdGenerator} is {@code null}
   * @throws IllegalArgumentException if either id is not W3C-compatible
   */
  public static Span child(TraceIdGenerator traceIdGenerator, Span parent) {
    Objects.requireNonNull(traceIdGenerator, "traceIdGenerator");
    String traceId = resolveTraceId(traceIdGenerator, parent);
    String spanId = nextSpanId(traceIdGenerator);
    return new Span(traceId, spanId, parent, true);
  }

  /**
   * Child span with a forced trace id.
   *
   * <p>Unlike {@link #child}, the given {@code traceId} always wins, even when {@code parent} is
   * set. The parent link is kept (reentrant detection and chain walking still work); only {@code
   * nextSpanId()} is taken from {@code generator}. Use this to continue an externally provided
   * trace (e.g. an inbound {@code traceparent} header or a request id from another propagation
   * mechanism) on a worker thread whose {@link io.github.jinganix.peashooter.Tracer} holds a
   * different span.
   *
   * @param traceId forced trace id, validated as W3C-compatible (not passed through as-is)
   * @param generator {@link TraceIdGenerator} used only for the new span id
   * @param parent parent {@link Span}, kept for chain linkage
   * @return new span
   * @throws NullPointerException if {@code traceId} or {@code generator} is {@code null}
   * @throws IllegalArgumentException if either id is not W3C-compatible
   */
  public static Span continueTrace(String traceId, TraceIdGenerator generator, Span parent) {
    String spanId = nextSpanId(Objects.requireNonNull(generator, "generator"));
    return new Span(traceId, spanId, parent, true);
  }

  /**
   * Trace id for a child span: the parent's trace id, or a freshly generated one at the root.
   *
   * <p>Shared with {@link OrderedSpan} so the ordered factories can build their span directly
   * instead of allocating a throwaway intermediate {@code Span}.
   */
  static String resolveTraceId(TraceIdGenerator traceIdGenerator, Span parent) {
    return parent == null
        ? Objects.requireNonNull(traceIdGenerator.nextTraceId(), "traceIdGenerator.nextTraceId()")
        : parent.traceId;
  }

  /** New span id from {@code traceIdGenerator}; shared with {@link OrderedSpan}. */
  static String nextSpanId(TraceIdGenerator traceIdGenerator) {
    return Objects.requireNonNull(traceIdGenerator.nextSpanId(), "traceIdGenerator.nextSpanId()");
  }

  /**
   * Requires a W3C-compatible trace id (32 lowercase hex, not all zeros).
   *
   * <p>Shared with {@link OrderedSpan} so the ordered factories validate their forced trace id the
   * same way instead of failing late at propagation.
   */
  static String requireValidTraceId(String traceId) {
    Objects.requireNonNull(traceId, "traceId");
    if (!TraceIds.isValidTraceId(traceId)) {
      throw new IllegalArgumentException(
          "traceId must be a W3C-compatible 32-char lowercase hex id (not all zeros)");
    }
    return traceId;
  }

  /**
   * Requires a W3C-compatible span id (16 lowercase hex, not all zeros).
   *
   * <p>Shared with {@link OrderedSpan} so the ordered factories validate generated span ids the
   * same way instead of failing late at propagation.
   */
  static String requireValidSpanId(String spanId) {
    Objects.requireNonNull(spanId, "spanId");
    if (!TraceIds.isValidSpanId(spanId)) {
      throw new IllegalArgumentException(
          "spanId must be a W3C-compatible 16-char lowercase hex id (not all zeros)");
    }
    return spanId;
  }

  /**
   * Get the trace id.
   *
   * @return trace id
   */
  public String getTraceId() {
    return traceId;
  }

  /**
   * Get the span id for this hop.
   *
   * @return span id
   */
  public String getSpanId() {
    return spanId;
  }

  /**
   * Get the parent {@link Span}.
   *
   * @return parent {@link Span}
   */
  public Span getParent() {
    return parent;
  }

  /**
   * Check if the root {@link Span}.
   *
   * @return true if the root.
   */
  public boolean isRoot() {
    return parent == null;
  }

  /**
   * Whether this span carries W3C-valid ids (32-char lowercase hex trace id and 16-char lowercase
   * hex span id, neither all zeros).
   *
   * <p>Strict factories ({@link #ofIds}, {@link #child}, {@link #continueTrace}) always produce
   * valid spans; only the package-private unchecked factory can carry invalid ids. Call this before
   * propagating a span across a process boundary ({@link W3CTraceContext#inject(Span, boolean)}
   * validates the same way and rejects invalid ids).
   *
   * @return {@code true} if both ids are W3C-valid
   */
  public boolean isValid() {
    return TraceIds.isValidTraceId(traceId) && TraceIds.isValidSpanId(spanId);
  }

  @Override
  public String toString() {
    return "Span{traceId=" + traceId + ", spanId=" + spanId + "}";
  }

  /**
   * Spans use identity equality ({@link Object#equals}): two distinct {@code Span} instances are
   * never equal, even when they carry identical trace and span ids.
   *
   * <p>Do not use a {@code Span} as a {@link java.util.Map} key when lookup by ids is intended; use
   * {@link SpanIdKey} instead. Custom {@link io.github.jinganix.peashooter.TraceIdGenerator}s must
   * still emit globally unique span ids (see {@link TraceIds}).
   */
}
