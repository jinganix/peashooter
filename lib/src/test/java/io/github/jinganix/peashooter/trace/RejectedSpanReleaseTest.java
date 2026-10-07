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

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Rejected span release")
class RejectedSpanReleaseTest {
  @Test
  @DisplayName("should drop span reference when a READY instance is rejected")
  void shouldDropSpanWhenReadyRejected() throws Exception {
    DefaultTracer tracer = new DefaultTracer();
    OrderedTraceRunnable runnable = OrderedTraceRunnable.forKey(tracer, "key", false, () -> {});
    runnable.createSpan();
    runnable.rejected(new RuntimeException("discarded"));

    Field slotField = OrderedTraceRunnable.class.getDeclaredField("slot");
    slotField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<Object> slot = (AtomicReference<Object>) slotField.get(runnable);
    Object slotValue = slot.get();
    Field spanField = slotValue.getClass().getDeclaredField("span");
    spanField.setAccessible(true);
    assertThat(spanField.get(slotValue)).isNull();
  }
}
