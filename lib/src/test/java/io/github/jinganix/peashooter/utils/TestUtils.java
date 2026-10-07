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

import static org.awaitility.Awaitility.await;

import io.github.jinganix.peashooter.ThrowingRunnable;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

public class TestUtils {

  private static final Duration DEFAULT_AWAIT = Duration.ofSeconds(10);

  public static void awaitCountDown(CountDownLatch latch) {
    await().atMost(DEFAULT_AWAIT).until(() -> latch.getCount() == 0);
  }

  /**
   * Waits {@code millis} for test orchestration (task bodies, lock contention windows).
   *
   * <p>Stays on {@link Thread#sleep} rather than Awaitility ({@link #awaitCountDown} covers
   * condition waits): a duration wait must fail fast on interrupt, while Awaitility waits
   * uninterruptibly and would swallow cancellation. Stays unchecked rather than declaring {@link
   * InterruptedException} because callers are {@link Runnable} task bodies that cannot throw
   * checked failures; the interrupt status is restored before wrapping so cancellation still
   * propagates, matching {@link #uncheckedRun}.
   *
   * @param millis wait in milliseconds
   * @return {@code millis}
   */
  public static long sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
    return millis;
  }

  /**
   * Runs a throwing task as a {@link Runnable} body, preserving apparent failures.
   *
   * <p>An {@link InterruptedException} restores the interrupt status before wrapping (like {@link
   * #sleep}): without this, a task interrupted mid-wait would surface as a plain {@link
   * RuntimeException} with the flag cleared, and callers testing {@code Thread.interrupted()} would
   * lose the cancellation.
   *
   * @param runnable task to run
   */
  public static void uncheckedRun(ThrowingRunnable runnable) {
    try {
      runnable.run();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
