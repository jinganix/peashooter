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

import io.github.jinganix.peashooter.ExecutorSelector;
import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.queue.TaskQueue;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ExecutorSelector}: uses {@link DirectExecutor} for sync submissions when a span is
 * already active and the target queue is idle, otherwise the configured {@link TraceExecutor}.
 *
 * <p>Only one inline channel lives here: the idle-queue hint (gated by {@code allowInlineSync}). It
 * is a best-effort racy {@link TaskQueue#isIdle()} shortcut for nested sync work. The probe first
 * checks the span (no lock) so non-nested sync never touches the queue monitor; only nested sync
 * takes it, and the monitor is held for a few field reads while task bodies run outside it, so the
 * uncontended fast path costs far less than the thread hop it saves. Hint hits are debug-logged,
 * not counted: the only execution metric is {@link OrderedTraceExecutor#getReentrantInlineCount()}.
 * Disable when call stacks must stay shallow or foreign tasks must never run on another subsystem's
 * thread. Peer-free nested same-key sync bypasses the queue in {@link OrderedTraceExecutor}
 * (deadlock-avoidance, counted there); nesting with queued peers fails fast there instead of
 * overtaking, so strict per-key FIFO holds.
 */
public class DefaultExecutorSelector implements ExecutorSelector {

  private static final Logger log = LoggerFactory.getLogger(DefaultExecutorSelector.class);

  private final TraceExecutor traceExecutor;

  /**
   * Whether the idle-queue hint below may run eligible nested sync work inline on the calling
   * thread.
   *
   * <p>Disable when call stacks must stay shallow or when foreign tasks must never execute on a
   * thread owned by another subsystem; selection then always returns the configured {@link
   * TraceExecutor}. Nested same-key sync is unaffected by this flag: peer-free it runs inline in
   * {@link OrderedTraceExecutor} (deadlock-avoidance); with queued peers it fails fast there to
   * preserve strict per-key FIFO.
   */
  private final boolean allowInlineSync;

  /**
   * Constructor with inline sync enabled.
   *
   * @param traceExecutor {@link TraceExecutor}
   */
  public DefaultExecutorSelector(TraceExecutor traceExecutor) {
    this(traceExecutor, true);
  }

  /**
   * Constructor.
   *
   * @param traceExecutor {@link TraceExecutor}
   * @param allowInlineSync {@code false} to always route through the configured executor
   */
  public DefaultExecutorSelector(TraceExecutor traceExecutor, boolean allowInlineSync) {
    this.traceExecutor = Objects.requireNonNull(traceExecutor, "traceExecutor");
    this.allowInlineSync = allowInlineSync;
  }

  /**
   * Maximum pool threads behind the configured {@link TraceExecutor}, or {@link
   * MultiKeyGuard#UNKNOWN_MAX_THREADS} when not introspectable. Single-layer introspection owned by
   * this selector so callers never disassemble {@code selector -> traceExecutor -> delegate}
   * themselves.
   *
   * @return known maximum or unknown sentinel
   */
  int maxThreadsOrUnknown() {
    return MultiKeyGuard.maxThreadsOf(traceExecutor);
  }

  /**
   * Tracer behind the configured {@link TraceExecutor}.
   *
   * @return tracer, never {@code null}
   */
  Tracer tracer() {
    return traceExecutor.getTracer();
  }

  /**
   * Returns {@link DirectExecutor} when {@code sync}, a span is present, and {@code queue} is
   * {@link TaskQueue#isIdle() idle}; otherwise {@link TraceExecutor}. The direct path avoids a
   * thread hop for eligible nested sync work.
   *
   * <p>This is a best-effort racy hint — the idle snapshot may change before {@link
   * TaskQueue#execute} enqueues. Either executor preserves FIFO order; the direct path may inline
   * foreign tasks and deepen the call stack. Hint hits are debug-logged, not counted.
   *
   * <p>A failing hint read degrades to the safe {@link TraceExecutor} instead of abandoning the
   * submit fence. Only {@link Error}s propagate so fatal failures stay loud: {@link Exception}s
   * degrade, since the hint is expendable.
   *
   * @return {@link DirectExecutor} on a hint hit, else the configured {@link TraceExecutor}
   */
  @Override
  public Executor getExecutor(TaskQueue queue, boolean sync) {
    Objects.requireNonNull(queue, "queue");
    if (allowInlineSync && sync) {
      try {
        // Cheap-to-costly order: thread-local depth (no lock), then span read, then the queue
        // monitor (isIdle). Non-nested sync fails the span check before touching the monitor.
        if (DirectExecutor.depth() < DirectExecutor.MAX_INLINE_DEPTH
            && traceExecutor.getTracer().getSpan() != null
            && queue.isIdle()) {
          log.debug("Inline-sync hint hit; routing through direct executor");
          return DirectExecutor.INSTANCE;
        }
      } catch (Error fatal) {
        throw fatal;
      } catch (Exception hintFailure) {
        log.debug("Inline-sync hint failed; routing through trace executor", hintFailure);
      }
    }
    return traceExecutor;
  }
}
