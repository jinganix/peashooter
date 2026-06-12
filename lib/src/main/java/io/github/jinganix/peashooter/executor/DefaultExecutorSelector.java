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
import io.github.jinganix.peashooter.queue.TaskQueue;
import io.github.jinganix.peashooter.trace.TraceRunnable;
import java.util.concurrent.Executor;

/**
 * Default {@link ExecutorSelector}: uses {@link DirectExecutor} for sync submissions when a span is
 * already active and the target queue is idle, otherwise the configured {@link TraceExecutor}.
 */
public class DefaultExecutorSelector implements ExecutorSelector {

  private final TraceExecutor traceExecutor;

  /**
   * Constructor.
   *
   * @param traceExecutor {@link TraceExecutor}
   */
  public DefaultExecutorSelector(TraceExecutor traceExecutor) {
    this.traceExecutor = traceExecutor;
  }

  /**
   * Returns {@link DirectExecutor} when {@code sync}, a span is present, and {@code queue} is
   * {@link TaskQueue#isEmpty() empty}; otherwise {@link TraceExecutor}. The direct path avoids a
   * thread hop for eligible nested sync work.
   */
  @Override
  public Executor getExecutor(TaskQueue queue, TraceRunnable task, boolean sync) {
    if (sync && traceExecutor.getTracer().getSpan() != null && queue.isEmpty()) {
      return DirectExecutor.INSTANCE;
    }
    return traceExecutor;
  }
}
