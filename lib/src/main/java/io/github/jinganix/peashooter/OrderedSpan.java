/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.Objects;

/** Span with ordered key and sync flag. */
public class OrderedSpan extends Span {

  private final String key;

  private final boolean sync;

  /**
   * Constructor.
   *
   * @param traceId trace id
   * @param parent parent {@link Span}
   * @param key trace key
   * @param sync true if a sync call
   */
  public OrderedSpan(String traceId, Span parent, String key, boolean sync) {
    super(traceId, parent);
    this.key = key;
    this.sync = sync;
  }

  /**
   * Constructor.
   *
   * @param traceIdGenerator {@link TraceIdGenerator}
   * @param parent parent {@link Span}
   * @param key trace key
   * @param sync true if a sync call
   */
  public OrderedSpan(TraceIdGenerator traceIdGenerator, Span parent, String key, boolean sync) {
    super(traceIdGenerator, parent);
    this.key = key;
    this.sync = sync;
  }

  /**
   * Check if the key is in the task call chain.
   *
   * @param span {@link Span} to check
   * @param key trace key
   * @return true if the {@link Span} is called by key.
   */
  public static boolean invokedBy(Span span, String key) {
    if (span == null) {
      return false;
    }
    if (span instanceof OrderedSpan) {
      OrderedSpan orderedSpan = (OrderedSpan) span;
      if (orderedSpan.sync && Objects.equals(orderedSpan.key, key)) {
        return true;
      }
    }
    return invokedBy(span.getParent(), key);
  }
}
