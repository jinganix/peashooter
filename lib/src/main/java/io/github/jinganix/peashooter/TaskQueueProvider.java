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

import io.github.jinganix.peashooter.queue.TaskQueue;

/** {@link TaskQueue} collection holder. */
public interface TaskQueueProvider {

  /**
   * Whether the queue for {@code key} is idle: no pending tasks and no active runner.
   *
   * <p>A key with no entry counts as idle, and probing it creates nothing. This is the only
   * read-only probe: the provider never hands out an unfenced queue instance, so submissions cannot
   * bypass the {@link #getForSubmit} fence and split per-key ordering across two queue instances.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @return {@code true} when no entry exists, or the live queue is idle
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws IllegalArgumentException if {@code key} is empty or blank
   */
  boolean isIdle(String key);

  /**
   * Whether tasks are queued behind the active runner for {@code key}.
   *
   * <p>A key with no entry has no peers and reports {@code false}; probing creates nothing. Used
   * for reentrant deadlock-vs-overtake detection: a nested same-key sync with queued peers must
   * fail fast instead of silently overtaking them, while a peer-free nesting may run inline to
   * avoid self-deadlock without violating FIFO.
   *
   * <p>No default: every provider must make an explicit choice. Returning {@code true} always is
   * the fail-closed option (forfeits peer-free inline nesting entirely); probing the live queue
   * enables it. A silent inherited {@code true} hid the forfeit, so the choice is now forced at
   * compile time.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @return {@code true} when at least one task is queued for the key
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws IllegalArgumentException if {@code key} is empty or blank
   */
  boolean hasPending(String key);

  /**
   * Get the {@link TaskQueue} for submitting work under {@code key}.
   *
   * <p>The returned instance is fenced against eviction: no maintenance may detach it between this
   * call and the subsequent {@link TaskQueue#execute}, so the submitted work always lands on the
   * live instance for the key. Each {@code getForSubmit} must be paired with exactly one {@link
   * TaskQueue#execute} or {@link #abortSubmit(String, TaskQueue)}; the fence is released when that
   * submission is enqueued, after which the entry follows the normal idle-drain lifecycle.
   *
   * <p>An unpaired call is never released implicitly — not by a later submission and not by
   * maintenance — so an abandoned fence pins the entry for the key until it is explicitly aborted.
   * Callers must abort on every path that abandons a submission (for example {@link
   * io.github.jinganix.peashooter.executor.OrderedTraceExecutor} does so when its {@link
   * io.github.jinganix.peashooter.ExecutorSelector} fails). Callers must not retain the reference
   * beyond submission.
   *
   * <p>Implementations must return a stable instance per key while the key is in use: per-key
   * ordering relies on all submissions for a key reaching the same queue. Must be thread-safe;
   * concurrent callers with the same key must observe the same queue.
   *
   * <p>Implementations may build the queue lazily through a queue factory while holding an internal
   * lock (for example a cache bin lock during an atomic get-or-create). Such a factory must not
   * re-enter the provider: calling {@link #getForSubmit}, {@link #abortSubmit}, {@link #isIdle}, or
   * any maintenance method from inside it can deadlock or corrupt the submit fence.
   *
   * @param key key of the {@link TaskQueue}; must not be {@code null}, empty, or blank
   * @return live {@link TaskQueue} for submission, stable per key
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws IllegalArgumentException if {@code key} is empty or blank
   */
  TaskQueue getForSubmit(String key);

  /**
   * Releases the submit fence taken by {@link #getForSubmit} when the submission is abandoned
   * before {@link TaskQueue#execute} (for example the {@link
   * io.github.jinganix.peashooter.ExecutorSelector} threw). Providers without fencing release
   * nothing; providers that pin entries must release exactly one fence so the entry can idle-evict
   * again.
   *
   * <p>Idempotent with respect to identity: when {@code queue} is not the live entry for {@code
   * key} (evicted, replaced, or foreign), this is a no-op and the live entry is untouched.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @param queue the instance returned by {@link #getForSubmit}; must not be {@code null}
   * @throws NullPointerException if {@code key} or {@code queue} is {@code null}
   * @throws IllegalArgumentException if {@code key} is empty or blank
   */
  void abortSubmit(String key, TaskQueue queue);
}
