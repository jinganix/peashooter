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
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the JaCoCo gate shape: the {@code 0.9} threshold is enforced on the aggregate bundle only.
 *
 * <p>A per-class {@code LINE} ratio rule makes {@code :lib:check} unattainable for thin delegating
 * and defensive classes whose intentional cold branches are not exercised (e.g. the re-entrant
 * handoff cold path), so {@code ./gradlew build} can never pass. The aggregation module already
 * uses the aggregate-only shape. Only the threshold wiring is asserted here; the numbers stay in
 * the convention plugin.
 */
@DisplayName("Coverage gate config")
class CoverageGateConfigTest {

  private static final Pattern PER_CLASS_RULE = Pattern.compile("element\\s*=\\s*\"CLASS\"");

  @Test
  @DisplayName("should enforce the minimum on the aggregate bundle only, not per class")
  void shouldEnforceMinimumOnAggregateBundleOnly() throws IOException {
    // Given the shared Java convention plugin
    String build = Files.readString(conventionPlugin());

    // When / Then it keeps the aggregate gate but must not impose a per-class line-coverage rule
    assertThat(build).contains("jacocoTestCoverageVerification");
    assertThat(build).contains("minimum = BigDecimal.valueOf(props.jacocoMinCoverage)");
    assertThat(PER_CLASS_RULE.matcher(build).find())
        .as("a per-class coverage rule makes :lib:check unattainable for intentional cold branches")
        .isFalse();
  }

  private static Path conventionPlugin() {
    Path base = Path.of(System.getProperty("user.dir"));
    Path direct = base.resolve("buildSrc/src/main/kotlin/java.common.gradle.kts");
    if (Files.exists(direct)) {
      return direct;
    }
    return base.resolve("../buildSrc/src/main/kotlin/java.common.gradle.kts");
  }
}
