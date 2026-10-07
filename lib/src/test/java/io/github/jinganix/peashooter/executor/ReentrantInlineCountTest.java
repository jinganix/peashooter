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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Reentrant inline count")
class ReentrantInlineCountTest {

  private static Path mainSource(String relative) {
    Path base = Path.of(System.getProperty("user.dir"));
    Path direct = base.resolve("src/main/java/" + relative);
    if (Files.exists(direct)) {
      return direct;
    }
    return base.resolve("lib/src/main/java/" + relative);
  }

  private static OrderedTraceExecutor newExecutor() {
    DefaultTracer tracer = new DefaultTracer();
    TraceExecutor traceExecutor = new TraceExecutor(DirectExecutor.INSTANCE, tracer);
    DefaultExecutorSelector selector = new DefaultExecutorSelector(traceExecutor, false);
    return new OrderedTraceExecutor(new CaffeineTaskQueueProvider(), selector, tracer);
  }

  @Test
  @DisplayName("reentrantInlineCount skips depth-exceeding fail-fast on all three sync paths")
  void reentrantInlineCountSkipsDepthExceedingFailFast() throws Exception {
    OrderedTraceExecutor executor = newExecutor();
    AtomicLong beforeRun = new AtomicLong();
    AtomicLong afterRun = new AtomicLong();
    AtomicLong beforeSupply = new AtomicLong();
    AtomicLong afterSupply = new AtomicLong();
    AtomicLong beforeChecked = new AtomicLong();
    AtomicLong afterChecked = new AtomicLong();

    // Real nesting to the depth budget (no preset hook): each nested same-key sync with no
    // queued peers runs inline, so recursing past the budget trips the same fail-fast gate.
    nestToBudget(
        executor, 0, beforeRun, afterRun, beforeSupply, afterSupply, beforeChecked, afterChecked);

    assertThat(afterRun.get())
        .as("over-budget fail-fast must not count (executeSync)")
        .isEqualTo(beforeRun.get());
    assertThat(afterSupply.get())
        .as("over-budget fail-fast must not count (supply)")
        .isEqualTo(beforeSupply.get());
    assertThat(afterChecked.get())
        .as("over-budget fail-fast must not count (supplyChecked)")
        .isEqualTo(beforeChecked.get());
  }

  private static void nestToBudget(
      OrderedTraceExecutor executor,
      int level,
      AtomicLong beforeRun,
      AtomicLong afterRun,
      AtomicLong beforeSupply,
      AtomicLong afterSupply,
      AtomicLong beforeChecked,
      AtomicLong afterChecked) {
    if (level <= ReentrancyGate.MAX_REENTRANT_DEPTH) {
      executor.executeSync(
          "k",
          () ->
              nestToBudget(
                  executor,
                  level + 1,
                  beforeRun,
                  afterRun,
                  beforeSupply,
                  afterSupply,
                  beforeChecked,
                  afterChecked));
      return;
    }
    beforeRun.set(executor.getReentrantInlineCount());
    assertThatThrownBy(() -> executor.executeSync("k", () -> {}))
        .isInstanceOf(IllegalStateException.class);
    afterRun.set(executor.getReentrantInlineCount());

    beforeSupply.set(executor.getReentrantInlineCount());
    assertThatThrownBy(() -> executor.supply("k", () -> null))
        .isInstanceOf(IllegalStateException.class);
    afterSupply.set(executor.getReentrantInlineCount());

    beforeChecked.set(executor.getReentrantInlineCount());
    assertThatThrownBy(() -> executor.supplyChecked("k", IOException.class, () -> null))
        .isInstanceOf(IllegalStateException.class);
    afterChecked.set(executor.getReentrantInlineCount());
  }

  @Test
  @DisplayName("idle hint is debug-logged, not counted")
  void idleHintIsDebugLoggedNotCounted() throws IOException {
    Path path = mainSource("io/github/jinganix/peashooter/executor/DefaultExecutorSelector.java");
    String src = Files.readString(path);
    assertThat(src).contains("debug");
    assertThat(src).doesNotContain("getInlineHintCount");
  }
}
