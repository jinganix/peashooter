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

package io.github.jinganix.peashooter;

/**
 * Per-batch execution statistics, mainly for yield checks.
 *
 * <p>Lifecycle per runner invocation holding the lock: {@link #reset()} once after the lock is
 * acquired (a runner that fails to acquire never resets, so it cannot clear another batch's
 * counters), {@link #record()} after each executed task, reads in {@code shouldYield}. An async
 * handoff continuation owns the same lock hold but starts a new counting window. Instances are used
 * by one queue runner at a time but may hand off across threads; implementations must ensure the
 * handoff is visible across threads without relying on the backing {@link
 * java.util.concurrent.Executor} for happens-before, which custom {@code Executor}s are not
 * required to provide.
 *
 * <p>Throwing contract: implementations must not throw for control flow. An {@link Exception} is
 * contained (a throwing {@code reset} reschedules the runner, a throwing {@code record}/{@code
 * shouldYield} ends the batch; all are logged by {@link
 * io.github.jinganix.peashooter.queue.LockableTaskQueue}). An {@link Error} stays loud and
 * fail-open: the runner claim is dropped, the backlog is preserved for the next explicit submit,
 * and no automatic retry is scheduled.
 */
public interface ExecutionStats {

  /** Reset stats. */
  void reset();

  /** Record stats. */
  void record();

  /**
   * Execution count since last {@link #reset}.
   *
   * <p>Saturates at {@link Integer#MAX_VALUE} instead of overflowing: never compare with {@code ==
   * N}, test {@code >= N}, because a saturated counter stays at {@code MAX_VALUE} and an equality
   * check would never yield again.
   *
   * @return executions recorded in the current batch
   */
  int getExecutionCount();
}
