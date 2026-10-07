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

import io.github.jinganix.peashooter.trace.DefaultTracer;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReentrancyGate saturation")
class ReentrancyGateSaturationTest {

  @Test
  @DisplayName("should saturate at Long.MAX_VALUE instead of wrapping")
  void shouldSaturateInsteadOfWrap() throws Exception {
    ReentrancyGate gate = new ReentrancyGate();
    DefaultTracer tracer = new DefaultTracer();
    Field field = ReentrancyGate.class.getDeclaredField("reentrantInlineCount");
    field.setAccessible(true);
    AtomicLong counter = (AtomicLong) field.get(gate);
    counter.set(Long.MAX_VALUE - 1);

    gate.runReentrantSync(tracer, "k", () -> {});
    gate.runReentrantSync(tracer, "k", () -> {});

    assertThat(gate.getReentrantInlineCount()).isEqualTo(Long.MAX_VALUE);
  }
}
