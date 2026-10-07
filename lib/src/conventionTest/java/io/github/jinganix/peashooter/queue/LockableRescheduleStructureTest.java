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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Lockable reschedule structure")
class LockableRescheduleStructureTest {

  @Test
  @DisplayName("should discard only the head and repoint without forwarding shims")
  void shouldDiscardOnlyHeadAndRepointWithoutForwardingShims() {
    boolean hasNoArgDiscardShim =
        java.util.Arrays.stream(LockableTaskQueue.class.getDeclaredMethods())
            .anyMatch(
                m -> m.getName().equals("discardHeadAndRepoint") && m.getParameterCount() == 0);
    boolean hasRescheduleShim =
        java.util.Arrays.stream(LockableTaskQueue.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("rescheduleWithDiscard"));
    boolean hasSchedule =
        java.util.Arrays.stream(LockableTaskQueue.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("scheduleWithHeadDiscard"));
    assertThat(hasNoArgDiscardShim)
        .as("forwarding discardHeadAndRepoint shim must be gone")
        .isFalse();
    assertThat(hasRescheduleShim)
        .as("forwarding rescheduleWithDiscard shim must be gone")
        .isFalse();
    assertThat(hasSchedule).as("expected scheduleWithHeadDiscard helper").isTrue();
  }

  @Test
  @DisplayName("should schedule retries without a no-op catch")
  void shouldScheduleRetriesWithoutNoOpCatch() throws Exception {
    // The retry scheduler call must not be wrapped in a catch that only rethrows.
    java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir"));
    java.nio.file.Path candidate =
        root.resolve(
            "lib/src/main/java/io/github/jinganix/peashooter/queue/LockableTaskQueue.java");
    if (!java.nio.file.Files.exists(candidate)) {
      candidate =
          root.resolve("src/main/java/io/github/jinganix/peashooter/queue/LockableTaskQueue.java");
    }
    String source = java.nio.file.Files.readString(candidate);
    // Normalize whitespace first: the guarded shape is "a catch whose whole body rethrows the
    // caught variable", independent of formatting, indentation, or the variable name.
    String normalized = source.replaceAll("\\s+", " ");
    boolean hasBareRethrow =
        java.util.regex.Pattern.compile(
                "catch\\s*\\(\\s*(?:[\\w$.]+\\s*\\|\\s*)*([\\w$.]+)\\s+(\\w+)\\s*\\)"
                    + "\\s*\\{\\s*throw\\s+\\2\\s*;\\s*}")
            .matcher(normalized)
            .find();
    assertThat(hasBareRethrow).as("no-op catch should be removed").isFalse();
  }

  @Test
  @DisplayName("should not carry unused executor parameters or forwarding wrappers")
  void shouldNotCarryUnusedExecutorParametersOrForwardingWrappers() {
    boolean rescheduleNoArgs =
        java.util.Arrays.stream(LockableTaskQueue.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("rescheduleRunner") && m.getParameterCount() == 0);
    boolean scheduleNoArgs =
        java.util.Arrays.stream(LockableTaskQueue.class.getDeclaredMethods())
            .anyMatch(
                m -> m.getName().equals("scheduleWithHeadDiscard") && m.getParameterCount() == 0);
    boolean lockBatchUnlocking =
        java.util.Arrays.stream(LockBatch.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("isUnlocking"));
    assertThat(rescheduleNoArgs).as("rescheduleRunner must not take an ignored executor").isTrue();
    assertThat(scheduleNoArgs)
        .as("scheduleWithHeadDiscard must not take an ignored executor")
        .isTrue();
    assertThat(lockBatchUnlocking)
        .as("LockBatch.isUnlocking forwarding wrapper must be gone")
        .isFalse();
  }
}
