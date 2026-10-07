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

package io.github.jinganix.peashooter.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DuplicateRunReject")
class DuplicateRunRejectTest {

  static class CountingProbe
      implements Runnable, io.github.jinganix.peashooter.queue.RejectionAware {
    final AtomicInteger notifications = new AtomicInteger();
    volatile Throwable lastCause;

    @Override
    public void run() {}

    @Override
    public void rejected(Throwable cause) {
      notifications.incrementAndGet();
      lastCause = cause;
    }
  }

  @Test
  @DisplayName("should reject second run without notifying delegate again")
  void shouldRejectSecondRunAndNotifyDelegateExactlyOnce() {
    DefaultTracer tracer = new DefaultTracer();
    CountingProbe probe = new CountingProbe();
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", true, probe);
    runnable.run();

    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThat(probe.notifications.get())
        .as("sequential reuse after a completed run must not dispatch again")
        .isEqualTo(0);
    assertThat(probe.lastCause).isNull();
  }

  @Test
  @DisplayName("should reject run after rejection without notifying twice")
  void shouldRejectRunAfterRejectionWithoutNotifyingTwice() {
    DefaultTracer tracer = new DefaultTracer();
    CountingProbe probe = new CountingProbe();
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", false, probe);
    runnable.rejected(new RuntimeException("discarded"));

    assertThatThrownBy(runnable::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("single-use");
    assertThat(probe.notifications.get()).isEqualTo(1);
  }
}
