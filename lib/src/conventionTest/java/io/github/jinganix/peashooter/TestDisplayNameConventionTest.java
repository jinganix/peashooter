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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Test display name convention")
class TestDisplayNameConventionTest {

  /**
   * Matches a {@code @DisplayName} whose text starts with the camelCase method-name form {@code
   * shouldXxx} instead of a space-separated BDD sentence ({@code should xxx ... when ...}).
   */
  private static final Pattern CAMEL_CASE_SHOULD = Pattern.compile("@DisplayName\\(\"should[A-Z]");

  /** Any single-line {@code @DisplayName} literal, member- or type-level. */
  private static final Pattern DISPLAY_NAME = Pattern.compile("@DisplayName\\(\"(.*?)\"\\)");

  /** A type declaration line, so a type-level DisplayName (a class name) is not flagged. */
  private static final Pattern TYPE_DECLARATION =
      Pattern.compile("\\b(class|interface|enum|record)\\s+[A-Za-z_]");

  @Test
  @DisplayName("should use BDD sentences rather than camelCase method names")
  void shouldUseBddSentencesRatherThanCamelCaseMethodNames() throws IOException {
    // Given the test sources
    Path testRoot = testRoot();

    // When scanning every test file Then no method-level DisplayName may use camelCase shouldXxx
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(testRoot)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          Matcher matcher = CAMEL_CASE_SHOULD.matcher(lines.get(i));
          if (matcher.find()) {
            violations.add(testRoot.relativize(file) + ":" + (i + 1) + " " + lines.get(i).trim());
          }
        }
      }
    }
    assertThat(violations)
        .as("DisplayName must be a BDD sentence (should ... when ...), not a method name")
        .isEmpty();
  }

  @Test
  @DisplayName("should start every member-level DisplayName with a lowercase letter")
  void shouldStartEveryMemberLevelDisplayNameWithLowercaseLetter() throws IOException {
    // Given the test sources
    Path testRoot = testRoot();

    // When scanning every test file Then no member-level DisplayName may start uppercase:
    // type-level DisplayNames are class names (allowed uppercase), member-level are BDD sentences.
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(testRoot)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          Matcher matcher = DISPLAY_NAME.matcher(lines.get(i));
          if (!matcher.find() || isTypeLevelDisplayName(lines, i)) {
            continue;
          }
          String text = matcher.group(1);
          if (text.isEmpty() || !Character.isLowerCase(text.charAt(0))) {
            violations.add(testRoot.relativize(file) + ":" + (i + 1) + " " + text);
          }
        }
      }
    }
    assertThat(violations)
        .as("member-level DisplayName must start lowercase (class-level may be a type name)")
        .isEmpty();
  }

  /**
   * Whether the annotation at {@code index} decorates a type: its next non-blank, non-annotation
   * line declares a class (or interface/enum/record). Type-level DisplayNames carry class names and
   * are exempt from the lowercase rule, which applies to test methods and {@code @Nested} members.
   */
  private static boolean isTypeLevelDisplayName(List<String> lines, int index) {
    for (int i = index + 1; i < lines.size(); i++) {
      String trimmed = lines.get(i).trim();
      if (trimmed.isEmpty() || trimmed.startsWith("@")) {
        continue;
      }
      return TYPE_DECLARATION.matcher(trimmed).find();
    }
    return false;
  }

  private static Path testRoot() {
    Path base = Path.of(System.getProperty("user.dir"));
    Path direct = base.resolve("src/test");
    return Files.exists(direct) ? direct : base.resolve("lib/src/test");
  }
}
