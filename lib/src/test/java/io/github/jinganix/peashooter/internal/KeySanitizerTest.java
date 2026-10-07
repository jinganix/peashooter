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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KeySanitizer")
class KeySanitizerTest {

  @Test
  @DisplayName("should return the same instance when the key is clean")
  void shouldReturnSameInstanceWhenKeyIsClean() {
    String clean = "order-42";

    assertThat(KeySanitizer.sanitize(clean)).isSameAs(clean);
  }

  @Test
  @DisplayName("should neutralize control and formatting characters for display")
  void shouldNeutralizeControlAndFormattingCharactersForDisplay() {
    assertThat(KeySanitizer.sanitize("a\nb")).isEqualTo("a_b");
    assertThat(KeySanitizer.sanitize("a\u001Bb")).isEqualTo("a_b");
    assertThat(KeySanitizer.sanitize("a\u202Eb")).isEqualTo("a_b");
    assertThat(KeySanitizer.sanitize("a\uFEFFb")).isEqualTo("a_b");
  }

  @Test
  @DisplayName("should reject null keys")
  void shouldRejectNullKeys() {
    assertThatThrownBy(() -> KeySanitizer.sanitize(null)).isInstanceOf(NullPointerException.class);
  }
}
