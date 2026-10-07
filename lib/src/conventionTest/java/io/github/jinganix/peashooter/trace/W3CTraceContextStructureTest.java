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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("W3C trace context structure")
class W3CTraceContextStructureTest {

  @Test
  @DisplayName("should validate tracestate members without a redundant member-start tracker")
  void shouldValidateTracestateMembersWithoutRedundantMemberStartTracker() throws Exception {
    // The member-end check was unreachable: the non-empty key/value guard already holds the
    // invariants, so the tracker was write-only dead state. Guard against reintroduction.
    String source = Files.readString(sourceFile());
    assertThat(source)
        .as("dead member-start tracker must be removed from the tracestate validator")
        .doesNotContain("memberStart");
  }

  @Test
  @DisplayName("should validate tracestate members without an unreachable character helper")
  void shouldValidateTracestateMembersWithoutUnreachableCharacterHelper() throws Exception {
    // isTracestateChar was only called after space/HTAB, controls, '=', and ',' were already
    // handled, so it could never reject and its branch was unreachable. Guard against
    // reintroduction.
    String source = Files.readString(sourceFile());
    assertThat(source)
        .as("dead tracestate character helper must be removed from the tracestate validator")
        .doesNotContain("isTracestateChar");
  }

  @Test
  @DisplayName("should validate fixed lower-hex fields through one shared predicate")
  void shouldValidateFixedLowerHexFieldsThroughOneSharedPredicate() throws Exception {
    // isVersionShape and isFlagsShape were identical twins; a change to one would silently leave
    // the other. Guard against reintroducing the duplicate.
    String source = Files.readString(sourceFile());
    assertThat(source)
        .as("version/flags shape must use one shared predicate")
        .doesNotContain("isVersionShape")
        .doesNotContain("isFlagsShape");
  }

  private static Path sourceFile() {
    Path base = Path.of(System.getProperty("user.dir"));
    Path direct =
        base.resolve("lib/src/main/java/io/github/jinganix/peashooter/trace/W3CTraceContext.java");
    if (Files.exists(direct)) {
      return direct;
    }
    return base.resolve("src/main/java/io/github/jinganix/peashooter/trace/W3CTraceContext.java");
  }
}
