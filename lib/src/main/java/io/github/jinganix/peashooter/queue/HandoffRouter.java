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

package io.github.jinganix.peashooter.queue;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executor-handoff router for {@link LockableTaskQueue}.
 *
 * <p>Owns the sync/async handoff handshake and head-only rejection: when consecutive tasks use
 * different {@link Executor}s, the external lock stays held across <em>asynchronous</em> handoff
 * until the new runner starts. Synchronous handoff (same-thread execution, decided by thread
 * identity) loops in the caller frame (trampoline) instead of nesting depth.
 */
final class HandoffRouter {

  private static final Logger log = LoggerFactory.getLogger(HandoffRouter.class);

  /** Handoff decision for {@link #transfer}. */
  enum HandoffOutcome {
    /** Handoff ran inline: caller loops in this frame keeping the lock. */
    SYNC_CONTINUE,
    /** Handoff transferred async: caller keeps the lock and returns. */
    ASYNC_KEEP,
    /** Scheduling failure (head rejected, backlog preserved): caller loops keeping the lock. */
    REJECT_CONTINUE,
  }

  private final LockableTaskQueue queue;

  HandoffRouter(LockableTaskQueue queue) {
    this.queue = queue;
  }

  /**
   * Hands off the runner to {@code handoffExecutor} without holding the queue monitor.
   *
   * <p>Sync vs async is decided by thread identity, not by timing or a marker interface: a fast
   * async pool may start the runner before execute() returns, so a "started" flag would misclassify
   * it as sync and unlock early, breaking mutual exclusion. Same-thread execution means inline
   * (sync) and trampolines via the caller's loop; any other thread (or not yet started) means async
   * ownership transfer. A deferred same-thread run after this method returned still drains via a
   * fresh frame, so no runner is lost.
   *
   * <p>The backing executor must provide happens-before between {@code execute()} and task start.
   * Without it the handshake degrades to an async transfer (caller keeps the lock and returns)
   * instead of risking a sync misclassification that would release the lock early and break mutual
   * exclusion.
   *
   * <p>On scheduling failure only the handoff head ({@code handoffHead}, already requeued at the
   * front) is rejected and removed; the remaining backlog is preserved in order, the runner claim
   * is repointed at the new head, and the caller loops keeping the external lock.
   *
   * @return handoff decision for the caller
   */
  HandoffOutcome transfer(Executor handoffExecutor, TaskQueue.Task handoffHead) {
    Thread handoffThread = Thread.currentThread();
    Runnable asyncDrain =
        () -> {
          LockableTaskQueue.RunState inner = new LockableTaskQueue.RunState();
          inner.ownsBatch = true;
          queue.runOuter(inner);
        };
    HandoffTemplate.Result result =
        HandoffTemplate.transfer(
            handoffExecutor, handoffThread, asyncDrain, e -> rejectHead(handoffHead, e));
    return switch (result) {
      case SYNC_LOOP -> HandoffOutcome.SYNC_CONTINUE;
      case REJECT_LOOP -> HandoffOutcome.REJECT_CONTINUE;
      case ASYNC_RETURN -> HandoffOutcome.ASYNC_KEEP;
    };
  }

  /**
   * Rejects only {@code failedHead} after a scheduling failure, preserving the remaining backlog in
   * order.
   *
   * <p>Removes the head under the queue monitor and repoints the runner claim at the new head (or
   * releases it when empty). While the external lock is held the claim is never published as idle:
   * the caller keeps draining in the same frame (handoff path) or the unlock path reschedules
   * below, so a concurrent submitter can never start a second runner on the held lock. Notifies the
   * head outside the monitor; a throwing rejection callback is logged and contained so the
   * surviving backlog still drains.
   *
   * @param failedHead head task whose executor rejected the scheduling attempt
   * @param e rejection or failure cause
   */
  void rejectHead(TaskQueue.Task failedHead, Throwable e) {
    queue.discardHeadAndRepoint(failedHead);
    notifyHeadRejected(failedHead, e);
  }

  /**
   * Logs a head-only rejection and notifies the rejected head. Must be called outside the queue
   * monitor: {@code rejected} callbacks are arbitrary user code and must never run under our lock.
   *
   * @param rejected rejected head task body holder
   * @param e rejection or failure cause
   */
  private void notifyHeadRejected(TaskQueue.Task rejected, Throwable e) {
    log.warn(
        "Rejected task in {} due to {}: {}; backlog preserved",
        queue.describeQueue(),
        e.getClass().getName(),
        e.getMessage());
    queue.notifyDiscardedContained(rejected.runnable(), e);
  }
}
