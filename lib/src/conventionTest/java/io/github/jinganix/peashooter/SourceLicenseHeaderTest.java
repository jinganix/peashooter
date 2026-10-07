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

package io.github.jinganix.peashooter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Source license header")
class SourceLicenseHeaderTest {

  private static final int HEADER_LINES = 17;

  @Test
  @DisplayName("should carry the Apache license header on every source file")
  void shouldCarryApacheLicenseHeaderOnEverySourceFile() throws IOException {
    // Given the canonical header, taken from a file known to carry it
    Path sourceRoot = sourceRoot();
    String[] expected =
        Files.readString(
                sourceRoot.resolve("main/java/io/github/jinganix/peashooter/queue/TaskQueue.java"))
            .split("\n", -1);

    // When scanning every Java source file Then each must start with the same header
    List<String> missing = new ArrayList<>();
    try (Stream<Path> files = Files.walk(sourceRoot)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        String[] actual = Files.readString(file).split("\n", -1);
        if (actual.length < HEADER_LINES) {
          missing.add(sourceRoot.relativize(file).toString());
          continue;
        }
        for (int i = 0; i < HEADER_LINES; i++) {
          if (!actual[i].equals(expected[i])) {
            missing.add(sourceRoot.relativize(file).toString());
            break;
          }
        }
      }
    }
    assertThat(missing).as("files missing the Apache license header").isEmpty();
  }

  private static Path sourceRoot() {
    Path base = Path.of(System.getProperty("user.dir"));
    Path direct = base.resolve("src");
    return Files.exists(direct) ? direct : base.resolve("lib/src");
  }
}
