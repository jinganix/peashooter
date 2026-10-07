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
 * Value key for {@link Span} identity by trace and span ids.
 *
 * <p>{@link Span} itself uses identity equality, so use this record wherever spans must be looked
 * up by ids (for example as {@link java.util.Map} keys). Two keys are equal when both ids match;
 * the parent link and {@link OrderedSpan} ordering metadata (key/sync) never participate.
 *
 * <p>Deliberately equality-only: unlike {@link Span#ofIds} factories, this key accepts
 * non-canonical ids so raw header values can be compared/deduplicated before validation. Validate
 * with {@link TraceIds#isValidTraceId}/{@link TraceIds#isValidSpanId} when W3C conformance matters.
 *
 * @param traceId trace id
 * @param spanId span id for the hop
 */
public record SpanIdKey(String traceId, String spanId) {

  /** Creates a key, rejecting null ids (non-canonical values allowed, see class docs). */
  public SpanIdKey {
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(spanId, "spanId");
  }

  /**
   * Key for explicit ids.
   *
   * @param traceId trace id
   * @param spanId span id for the hop
   * @return new key
   */
  public static SpanIdKey of(String traceId, String spanId) {
    return new SpanIdKey(traceId, spanId);
  }

  /**
   * Key for the ids carried by {@code span}.
   *
   * @param span span to key on
   * @return new key
   */
  public static SpanIdKey of(Span span) {
    Objects.requireNonNull(span, "span");
    return new SpanIdKey(span.getTraceId(), span.getSpanId());
  }
}
