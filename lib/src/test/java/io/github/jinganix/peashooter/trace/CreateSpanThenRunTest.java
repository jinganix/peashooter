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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CreateSpanThenRun")
class CreateSpanThenRunTest {

  @Test
  @DisplayName("should run normally after createSpan reusing the same span")
  void shouldRunNormallyAfterCreateSpanReusingTheSameSpan() {
    DefaultTracer tracer = new DefaultTracer();
    AtomicBoolean ran = new AtomicBoolean(false);
    AtomicReference<Span> observed = new AtomicReference<>();
    OrderedTraceRunnable runnable =
        OrderedTraceRunnable.forKey(
            tracer,
            "key",
            true,
            () -> {
              ran.set(true);
              observed.set(tracer.getSpan());
            });

    Span created = runnable.createSpan();
    runnable.run();

    assertThat(ran.get()).isTrue();
    assertThat(observed.get()).isSameAs(created);
  }
}
