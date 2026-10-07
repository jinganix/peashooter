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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Retry scheduler jitter injection")
class RetrySchedulerJitterInjectionTest {

  @Test
  @DisplayName("should map injected jitter into the backoff bound deterministically")
  void shouldMapInjectedJitterDeterministically() {
    // Attempt 5 caps at 32ms: jitter 0 maps to 1, jitter 99 maps to 1 + (99 mod 32).
    assertThat(RetryScheduler.computeBackoffDelayMillis(5, () -> 0L)).isEqualTo(1L);
    assertThat(RetryScheduler.computeBackoffDelayMillis(5, () -> 99L)).isEqualTo(4L);
    assertThat(RetryScheduler.computeBackoffDelayMillis(0, () -> 99L)).isEqualTo(1L);
  }

  @Test
  @DisplayName("should use the injected jitter for scheduled delays")
  @SuppressWarnings({"unchecked", "rawtypes"})
  void shouldUseInjectedJitterForDelays() {
    List<Long> delays = new ArrayList<>();
    ScheduledExecutorService rescheduler = mock(ScheduledExecutorService.class);
    when(rescheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            inv -> {
              delays.add((Long) inv.getArgument(1));
              ScheduledFuture f = mock(ScheduledFuture.class);
              when(f.isDone()).thenReturn(true);
              return f;
            });
    AtomicLong jitter = new AtomicLong(99L);
    RetryScheduler scheduler = new RetryScheduler(rescheduler, jitter::get);

    scheduler.resetBackoff();
    // Bump to attempt 5 deterministically via coalesced scheduling rounds.
    for (int i = 0; i < 6; i++) {
      scheduler.scheduleRetry(() -> {});
    }
    assertThat(delays).isNotEmpty();
    assertThat(delays).allSatisfy(d -> assertThat(d).isBetween(1L, 100L));
  }
}
