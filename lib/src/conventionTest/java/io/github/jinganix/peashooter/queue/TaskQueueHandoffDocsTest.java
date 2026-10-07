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

  private static Path mainSource(String relative) {
    Path base = Path.of(System.getProperty("user.dir"));
    Path direct = base.resolve("src/main/java/" + relative);
    if (Files.exists(direct)) {
      return direct;
    }
    return base.resolve("lib/src/main/java/" + relative);
  }

  private static String readMain(String relative) throws IOException {
    return Files.readString(mainSource(relative));
  }

  @Test
  @DisplayName("tryInlineHandoff carries a single merged Javadoc holding both contracts")
  void tryInlineHandoffHasSingleMergedJavadoc() throws IOException {
    String src = readMain("io/github/jinganix/peashooter/queue/TaskQueue.java");
    String anchor = "private boolean tryInlineHandoff(Executor handoff, Task failedHead) {";
    int methodIdx = src.indexOf(anchor);
    assertThat(methodIdx).as("tryInlineHandoff anchor").isGreaterThanOrEqualTo(0);

    String before = src.substring(0, methodIdx);
    int docStart = before.lastIndexOf("/**");
    assertThat(docStart).as("leading Javadoc start").isGreaterThanOrEqualTo(0);
    String block = before.substring(docStart);

    // Exactly one Javadoc block: no closing "*/" inside the block body itself.
    String inner = block.substring(0, block.lastIndexOf("*/"));
    assertThat(inner).as("single Javadoc block (no nested close)").doesNotContain("*/");

    // Both contracts survive the merge: the trampoline contract ...
    assertThat(block).contains("trampolining synchronous execution");
    // ... and the handshake-box contract that used to live in the second block.
    assertThat(block).contains("HandoffBoxes");
  }

  @Test
  @DisplayName("should carry the 17-line Apache license header like other main files")
  void handoffBoxesHasApacheLicenseHeader() throws IOException {
    String header = readMain("io/github/jinganix/peashooter/queue/TaskQueue.java");
    String target = readMain("io/github/jinganix/peashooter/queue/HandoffBoxes.java");
    String[] expected = header.split("\n", -1);
    String[] actual = target.split("\n", -1);
    assertThat(actual.length).isGreaterThanOrEqualTo(17);
    for (int i = 0; i < 17; i++) {
      assertThat(actual[i]).as("header line " + (i + 1)).isEqualTo(expected[i]);
    }
  }
}
