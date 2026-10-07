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
import java.util.concurrent.Executor;

/**
 * {@link Executor} that runs each command synchronously on the calling thread.
 *
 * <p>Used by {@link DefaultExecutorSelector} and {@link
 * io.github.jinganix.peashooter.queue.LockableTaskQueue} to avoid extra thread hops; nested {@code
 * execute} calls trampoline via the queue caller loop, so logical runner depth grows while Java
 * stack depth stays constant (see {@link io.github.jinganix.peashooter.queue.TaskQueue} handoff).
 *
 * <p>Exception: {@link io.github.jinganix.peashooter.queue.LockableTaskQueue} bounces a {@code
 * DirectExecutor} head to the caller-owned reschedule scheduler (exponential backoff 1-100ms with
 * Full Jitter) after a failed {@code tryLock}, so that path does not run inline.
 *
 * <p>Sync vs async is decided by thread identity in the queue trampolines, so any {@code
 * Runnable::run} executor gets the same bounded-depth protection.
 *
 * <p><b>Unified stack budget:</b> Channel 1 (this class, {@value #MAX_INLINE_DEPTH}) and Channel 2
 * ({@link OrderedTraceExecutor} peer-free reentrant inline, 256) share the calling thread, so one
 * thread can stack both. Worst case is the sum: 32 + 256 = 288 logical inline levels, each a
 * constant handful of Java frames ({@code execute} + queue runner + trace wrapper), far below
 * {@link StackOverflowError} on a default stack. Both channels fail fast with {@link
 * IllegalStateException} when their budget is exceeded — never {@link StackOverflowError}.
 */
public final class DirectExecutor implements Executor {
  /** Shared instance. */
  public static final DirectExecutor INSTANCE = new DirectExecutor();

  /**
   * Maximum nested inline depth per thread (Channel 1). Deeper Channel 1 hints route async via
   * {@link DefaultExecutorSelector}; direct {@link #execute} nesting beyond this fails fast with
   * {@link IllegalStateException}. Combined with Channel 2 the per-thread worst case is 32 + 256 =
   * 288 logical levels (see class Javadoc).
   */
  static final int MAX_INLINE_DEPTH = 32;

  /**
   * Per-runtime guard (one per classloader, not per-instance): this class is a singleton measuring
   * per-thread Java stack consumption, which is inherently shared across all callers on the thread.
   * Per-instance budgets would undercount cross-executor nesting on the same thread. Contrast with
   * {@link ReentrancyGate}, whose per-instance budget isolates logical reentrant counts per
   * executor.
   */
  private static final DepthGuard GUARD = new DepthGuard();

  /** Current thread's nested inline depth. */
  static int depth() {
    return GUARD.depth();
  }

  private DirectExecutor() {}

  @Override
  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable");
    GUARD.enter(
        MAX_INLINE_DEPTH,
        () -> new IllegalStateException("Excessive nested inline depth: max " + MAX_INLINE_DEPTH));
    try {
      runnable.run();
    } finally {
      GUARD.exit();
    }
  }
}
