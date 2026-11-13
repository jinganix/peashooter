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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueue handoff docs")
class TaskQueueHandoffDocsTest {

  private static Path mainSource() {
    Path base = Path.of(System.getProperty("user.dir"));
    Path direct = base.resolve("src/main/java");
    if (Files.exists(direct)) {
      return direct;
    }
    return base.resolve("lib/src/main/java");
  }

  @Test
  @DisplayName("should keep a single merged Javadoc on tryInlineHandoff")
  void shouldKeepSingleMergedJavadocOnTryInlineHandoff() throws IOException {
    // Given the handoff trampoline source
    String src =
        Files.readString(
            mainSource().resolve("io/github/jinganix/peashooter/queue/TaskQueue.java"));
    String anchor = "private boolean tryInlineHandoff(Executor handoff, Task failedHead) {";
    int methodIdx = src.indexOf(anchor);
    assertThat(methodIdx).as("tryInlineHandoff anchor").isGreaterThanOrEqualTo(0);

    // When reading the Javadoc directly above it Then it is exactly one block, not two stacked
    // blocks (the split form was merged into a single owning block).
    String before = src.substring(0, methodIdx);
    int docStart = before.lastIndexOf("/**");
    assertThat(docStart).as("leading Javadoc start").isGreaterThanOrEqualTo(0);
    String block = before.substring(docStart);
    String inner = block.substring(0, block.lastIndexOf("*/"));
    assertThat(inner).as("single Javadoc block (no nested close)").doesNotContain("*/");
  }
}
