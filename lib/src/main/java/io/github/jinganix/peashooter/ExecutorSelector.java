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

import java.util.concurrent.Executor;

/** Select a {@link Executor} for next execution. */
public interface ExecutorSelector {

  /**
   * Get executor.
   *
   * @param queue {@link TaskQueue}
   * @param task {@link TraceRunnable}
   * @param sync true if a sync call
   * @return {@link Executor}
   */
  Executor getExecutor(TaskQueue queue, TraceRunnable task, boolean sync);
}
