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

import java.util.Objects;

/**
 * W3C Trace Context ({@code traceparent}) parsing and injection.
 *
 * @see <a href="https://www.w3.org/TR/trace-context/">Trace Context</a>
 */
public final class W3CTraceContext {

  /** HTTP / messaging header name for trace propagation. */
  public static final String TRACEPARENT = "traceparent";

  private static final String VERSION = "00";

  private W3CTraceContext() {}

  /**
   * Parsed {@code traceparent} fields.
   *
   * @param traceId 128-bit trace id (32 hex)
   * @param parentSpanId caller span id from the header (16 hex)
   * @param sampled whether the sampled flag is set
   */
  public record Context(String traceId, String parentSpanId, boolean sampled) {}

  /**
   * Parses a {@code traceparent} header value.
   *
   * @param traceparent header value
   * @return parsed context
   * @throws IllegalArgumentException if the value is invalid
   */
  public static Context parse(String traceparent) {
    Objects.requireNonNull(traceparent, "traceparent");
    String[] parts = traceparent.split("-");
    if (parts.length != 4) {
      throw new IllegalArgumentException("traceparent must have 4 '-'-delimited fields");
    }
    if (!VERSION.equals(parts[0])) {
      throw new IllegalArgumentException("unsupported traceparent version: " + parts[0]);
    }
    String traceId = TraceIds.normalizeHex(parts[1]);
    String parentSpanId = TraceIds.normalizeHex(parts[2]);
    if (!TraceIds.isValidTraceId(traceId)) {
      throw new IllegalArgumentException("invalid trace-id in traceparent");
    }
    if (!TraceIds.isValidSpanId(parentSpanId)) {
      throw new IllegalArgumentException("invalid parent span id in traceparent");
    }
    String flags = TraceIds.normalizeHex(parts[3]);
    if (flags.length() != 2 || !isHex(flags)) {
      throw new IllegalArgumentException("invalid trace-flags in traceparent");
    }
    boolean sampled = (Integer.parseInt(flags, 16) & 0x01) == 0x01;
    return new Context(traceId, parentSpanId, sampled);
  }

  /**
   * Builds a {@link Span} representing the remote parent described by {@code traceparent}.
   *
   * <p>Use as the parent when creating a local child, e.g. {@code new Span(tracer,
   * extractParent(h))}.
   *
   * @param traceparent header value
   * @return remote parent span (root of the local chain, carrying upstream ids)
   */
  public static Span extractParent(String traceparent) {
    Context context = parse(traceparent);
    return new Span(context.traceId(), context.parentSpanId(), null);
  }

  /**
   * Formats a {@code traceparent} value for outbound propagation from {@code span}.
   *
   * @param span current span (its span id becomes the parent id in the header)
   * @param sampled whether to set the sampled flag
   * @return header value
   */
  public static String inject(Span span, boolean sampled) {
    Objects.requireNonNull(span, "span");
    if (!TraceIds.isValidTraceId(span.getTraceId())) {
      throw new IllegalArgumentException("span trace id is not W3C-compatible");
    }
    if (!TraceIds.isValidSpanId(span.getSpanId())) {
      throw new IllegalArgumentException("span id is not W3C-compatible");
    }
    return VERSION
        + "-"
        + span.getTraceId()
        + "-"
        + span.getSpanId()
        + "-"
        + (sampled ? "01" : "00");
  }

  private static boolean isHex(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean digit = c >= '0' && c <= '9';
      boolean lower = c >= 'a' && c <= 'f';
      if (!digit && !lower) {
        return false;
      }
    }
    return true;
  }
}
