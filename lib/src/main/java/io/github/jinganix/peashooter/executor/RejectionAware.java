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

/** Runnable notified when its queue discards pending work after executor submission failure. */
public interface RejectionAware {

  /**
   * Called when the enclosing {@link io.github.jinganix.peashooter.queue.TaskQueue} discards this
   * task without running it.
   *
   * @param cause rejection reported by {@link java.util.concurrent.Executor#execute(Runnable)}
   */
  void rejected(RuntimeException cause);
}
