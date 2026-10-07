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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Reentrant overtake fail-fast")
class ReentrantOvertakeFailFastTest {

  @Test
  @DisplayName("should fail fast when nested sync would overtake queued peer on the same key")
  void shouldFailFastWhenNestedSyncWouldOvertakeQueuedPeer() throws Exception {
    // Given an executor with a peer already queued behind the running holder on the same key
    ExecutorService pool = Executors.newFixedThreadPool(4);
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
    try {
      CountDownLatch holderStarted = new CountDownLatch(1);
      CountDownLatch peerQueued = new CountDownLatch(1);
      CountDownLatch holderDone = new CountDownLatch(1);
      CountDownLatch peerDone = new CountDownLatch(1);
      CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
      AtomicReference<Throwable> nestedFailure = new AtomicReference<>();

      // Holder occupies the key runner; waits until the peer is queued, then attempts nesting.
      executor.executeAsync(
          "k",
          () -> {
            holderStarted.countDown();
            try {
              if (!peerQueued.await(5, TimeUnit.SECONDS)) {
                return;
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            // When the holder nests same-key sync while a peer waits Then it must fail fast
            // instead of silently running inline ahead of the peer.
            try {
              executor.executeSync("k", () -> order.add("nested"));
            } catch (Throwable e) {
              nestedFailure.set(e);
            } finally {
              holderDone.countDown();
            }
          });
      assertThat(holderStarted.await(5, TimeUnit.SECONDS)).isTrue();

      // Peer queues behind the holder and blocks.
      Thread peer =
          new Thread(
              () ->
                  executor.executeSync(
                      "k",
                      () -> {
                        order.add("peer");
                        peerDone.countDown();
                      }));
      peer.start();
      Thread.sleep(300);
      peerQueued.countDown();

      // Then the nested call fails explicitly and never overtakes the peer.
      assertThat(holderDone.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(peerDone.await(5, TimeUnit.SECONDS)).isTrue();
      peer.join(5000);
      assertThat(nestedFailure.get())
          .as("nested same-key sync with a queued peer must fail fast, not inline-overtake")
          .isInstanceOf(IllegalStateException.class);
      assertThat(order).containsExactly("peer");
    } finally {
      pool.shutdownNow();
    }
  }
}
