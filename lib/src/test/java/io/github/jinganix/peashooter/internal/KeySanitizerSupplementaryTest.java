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

package io.github.jinganix.peashooter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KeySanitizer supplementary")
class KeySanitizerSupplementaryTest {

  @Test
  @DisplayName("should sanitize supplementary plane format controls by code point")
  void shouldSanitizeSupplementaryFormatControls() {
    // U+1D173 MUSICAL SYMBOL BEGIN BEAM (Cf) and U+E0001 LANGUAGE TAG (Cf).
    String beam = new String(Character.toChars(0x1D173));
    String tag = new String(Character.toChars(0xE0001));

    assertThat(KeySanitizer.sanitize("a" + beam + "b")).isEqualTo("a_b");
    assertThat(KeySanitizer.sanitize("a" + tag + "b")).isEqualTo("a_b");
    assertThat(KeySanitizer.sanitize(beam)).isEqualTo("_");
  }

  @Test
  @DisplayName("should keep clean supplementary characters as is")
  void shouldKeepCleanSupplementary() {
    // U+1F600 GRINNING FACE (So): not a control/format, must pass through.
    String emoji = new String(Character.toChars(0x1F600));
    String clean = "order-" + emoji;

    assertThat(KeySanitizer.sanitize(clean)).isSameAs(clean);
  }
}
