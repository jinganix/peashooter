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
 * {@link Span} tagged with the per-key ordering key and whether the call was synchronous.
 *
 * <p>Created by {@link OrderedTraceRunnable} for each ordered submission. The {@code sync} flag
 * participates in {@link #invokedBy(Span, String)}: only sync spans on the active chain can trigger
 * reentrant inline execution in {@link
 * io.github.jinganix.peashooter.executor.OrderedTraceExecutor}.
 */
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
   * Whether a nested {@code executeSync}/{@code supply} for {@code key} may bypass the per-key
   * queue and run inline on the current thread.
   *
   * <p>Walks the active span chain from {@code span} toward the root:
   *
   * <ul>
   *   <li>Matching {@code OrderedSpan} key → {@code true} (reentrant sync; queue bypass).
   *   <li>Async {@code OrderedSpan} with a different key → {@code false} (stop).
   *   <li>Sync {@code OrderedSpan} with a different key → continue to parent (multi-key nesting).
   * </ul>
   *
   * <p><b>Ordering implication:</b> when this returns {@code true}, work runs immediately on the
   * caller thread even if other threads are blocked in the queue for the same key. Cross-thread
   * FIFO is not preserved for that nested call. This is intentional — it prevents a thread from
   * deadlocking on its own nested sync — but callers requiring strict global ordering per key must
   * structure code to avoid nested same-key sync while peers are waiting.
   *
   * @param span current {@link Span}, or {@code null}
   * @param key ordered trace key
   * @return {@code true} if the sync call may run inline without enqueueing
   */
  public static boolean invokedBy(Span span, String key) {
    if (span == null) {
      return false;
    }
    if (span instanceof OrderedSpan) {
      OrderedSpan orderedSpan = (OrderedSpan) span;
      if (!orderedSpan.sync && !Objects.equals(orderedSpan.key, key)) {
        return false;
      }
      if (Objects.equals(orderedSpan.key, key)) {
        return true;
      }
    }
    return invokedBy(span.getParent(), key);
  }
}
