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

package io.github.jinganix.peashooter.utils;

import io.github.jinganix.peashooter.TaskQueueProvider;
import io.github.jinganix.peashooter.internal.KeySanitizer;
import io.github.jinganix.peashooter.internal.Keys;
import io.github.jinganix.peashooter.queue.TaskQueue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Non-evicting {@link TaskQueueProvider} for tests.
 *
 * <p>Entries live until the provider is dropped: no idle expiry and no size bound. {@link
 * #getForSubmit} returns the factory-built delegate directly (composition, never a decorating
 * subclass), so the only live queue state is the delegate's own deque and runner claim. The submit
 * fence is an external count on the entry: every {@link #getForSubmit} takes exactly one fence,
 * released by exactly one {@link #abortSubmit(String, TaskQueue)}. Unlike the production {@code
 * PinnedTaskQueue}, a successful {@link TaskQueue#execute} needs no release hook — entries are
 * never evicted, so there is no detach window to fence. Tests that abandon a submission (for
 * example a throwing {@link io.github.jinganix.peashooter.ExecutorSelector} without the matching
 * abort) show up here as a leaked {@link #pendingSubmits} count instead of passing silently.
 */
public class InMemoryTaskQueueProvider implements TaskQueueProvider {

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  private final Function<String, TaskQueue> factory;

  /**
   * Creates a provider materializing queues via {@code factory}.
   *
   * <p>The factory runs inside the map bin lock (an atomic get-or-create), so it must not re-enter
   * this provider; any provider call from the factory can deadlock on that lock or corrupt the
   * submit fence.
   *
   * @param factory queue factory, invoked at most once per key; must never return {@code null}
   */
  public InMemoryTaskQueueProvider(Function<String, TaskQueue> factory) {
    this.factory = Objects.requireNonNull(factory, "factory");
  }

  @Override
  public boolean isIdle(String key) {
    Keys.requireKey(key);
    Entry entry = entries.get(key);
    return entry == null || entry.queue.isIdle();
  }

  @Override
  public boolean hasPending(String key) {
    Keys.requireKey(key);
    Entry entry = entries.get(key);
    return entry != null && entry.queue.hasPending();
  }

  @Override
  public TaskQueue getForSubmit(String key) {
    Keys.requireKey(key);
    Entry entry =
        entries.compute(
            key,
            (k, existing) -> {
              if (existing == null) {
                TaskQueue created = factory.apply(k);
                if (created == null) {
                  throw new IllegalStateException(
                      "TaskQueue factory returned null for key '" + KeySanitizer.sanitize(k) + "'");
                }
                existing = new Entry(created);
              }
              existing.fences.incrementAndGet();
              return existing;
            });
    return entry.queue;
  }

  @Override
  public void abortSubmit(String key, TaskQueue queue) {
    Keys.requireKey(key);
    Objects.requireNonNull(queue, "queue");
    // Identity-gated like production: a detached or foreign instance releases nothing, so a
    // stale caller can never drop the fence of the live entry.
    Entry entry = entries.get(key);
    if (entry != null && entry.queue == queue) {
      entry.releaseFence();
    }
  }

  /**
   * Outstanding submit fences for {@code key}: {@link #getForSubmit} calls not yet paired with an
   * {@link #abortSubmit(String, TaskQueue)}. Zero when the key was never submitted. Test-only
   * observable for the fence pairing contract.
   *
   * @param key ordering key; must not be {@code null}, empty, or blank
   * @return outstanding fence count, never negative
   */
  public int pendingSubmits(String key) {
    Keys.requireKey(key);
    Entry entry = entries.get(key);
    return entry == null ? 0 : entry.fences.get();
  }

  /** Per-key live entry: the factory-built queue plus its outstanding fence count. */
  private static final class Entry {
    final TaskQueue queue;

    final AtomicInteger fences = new AtomicInteger();

    Entry(TaskQueue queue) {
      this.queue = queue;
    }

    /**
     * Releases one fence for an abandoned submission. A consumed fence is a no-op: the count only
     * drops while a submitter holds an unreleased fence.
     */
    void releaseFence() {
      fences.updateAndGet(fenced -> fenced > 0 ? fenced - 1 : 0);
    }
  }
}
