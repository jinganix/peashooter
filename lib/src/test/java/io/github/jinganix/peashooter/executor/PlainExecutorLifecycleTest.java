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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Plain executor lifecycle")
class PlainExecutorLifecycleTest {

  @Test
  @DisplayName("should run async work to completion on plain Executor")
  void shouldRunAsyncWorkToCompletionOnPlainExecutor() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);
    Executor plainAsync = cmd -> new Thread(cmd).start();
    OrderedTraceExecutor executor = new OrderedTraceExecutor(plainAsync);
    executor.executeAsync(
        "k",
        () -> {
          started.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        });
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("should run inline work on plain Executor without lifecycle")
  void shouldRunInlineWorkOnPlainExecutorWithoutLifecycle() throws Exception {
    Executor plain = cmd -> new Thread(cmd).start();
    OrderedTraceExecutor executor = new OrderedTraceExecutor(plain);
    CountDownLatch done = new CountDownLatch(1);
    executor.executeAsync("k", done::countDown);
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
  }
}
