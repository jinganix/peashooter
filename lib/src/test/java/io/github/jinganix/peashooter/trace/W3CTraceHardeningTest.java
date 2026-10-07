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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.internal.KeySanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("W3C trace header hardening")
class W3CTraceHardeningTest {

  @Test
  @DisplayName("parse rejects oversized traceparent fast")
  void rejectsOversizedTraceparent() {
    String huge = "00-" + "a".repeat(2000) + "-b16b716b2ad9e3c8-01";
    assertThatThrownBy(() -> W3CTraceContext.parse(huge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too long");
  }

  @Test
  @DisplayName("should neutralize zero-width chars in KeySanitizer")
  void sanitizesZeroWidth() {
    assertThat(KeySanitizer.sanitize("a\u2028b")).isEqualTo("a_b");
    assertThat(KeySanitizer.sanitize("a\u200Bb")).isEqualTo("a_b");
    assertThat(KeySanitizer.sanitize("a\u200Db")).isEqualTo("a_b");
    assertThat(KeySanitizer.sanitize("a\u2060b")).isEqualTo("a_b");
  }
}
