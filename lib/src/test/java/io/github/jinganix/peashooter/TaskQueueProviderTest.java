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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.internal.KeySanitizer;
import io.github.jinganix.peashooter.internal.Keys;
import io.github.jinganix.peashooter.queue.TaskQueue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskQueueProvider")
class TaskQueueProviderTest {

  static final class MapProvider implements TaskQueueProvider {
    final Map<String, TaskQueue> queues = new ConcurrentHashMap<>();

    @Override
    public boolean isIdle(String key) {
      Keys.requireKey(key);
      TaskQueue queue = queues.get(key);
      return queue == null || queue.isIdle();
    }

    @Override
    public boolean hasPending(String key) {
      Keys.requireKey(key);
      TaskQueue queue = queues.get(key);
      return queue != null && queue.hasPending();
    }

    @Override
    public TaskQueue getForSubmit(String key) {
      Keys.requireKey(key);
      return queues.computeIfAbsent(key, k -> new TaskQueue());
    }

    @Override
    public void abortSubmit(String key, TaskQueue queue) {
      // No fencing in this provider: nothing to release.
    }
  }

  @Test
  @DisplayName("should not materialize entries on isIdle")
  void shouldNotMaterializeEntriesOnGetIfPresent() {
    // Given a provider that never saw the key
    MapProvider provider = new MapProvider();

    // When / Then a read-only lookup creates nothing
    assertThat(provider.isIdle("absent")).isTrue();
    assertThat(provider.queues).isEmpty();
  }

  @Test
  @DisplayName("should return stable instance on getForSubmit")
  void shouldReturnStableInstanceOnGetForSubmit() {
    // Given
    MapProvider provider = new MapProvider();

    // When / Then submissions observe the same queue and idle probes see it
    TaskQueue first = provider.getForSubmit("key");
    assertThat(provider.getForSubmit("key")).isSameAs(first);
    assertThat(provider.isIdle("key")).isTrue();
  }

  @Test
  @DisplayName("should sanitize bidi controls when key contains them")
  void shouldSanitizeBidiControlsWhenKeyContainsThem() {
    // Given a key embedding bidi overrides/isolates and invisible marks
    String key =
        "a\u202Ab\u202Ec\u2066d\u2069e\u200Ef\u200Fg\uFEFFh\u202Di\u202Bj\u202Ck\u2067l\u2068m";

    // When sanitizing for display
    String sanitized = KeySanitizer.sanitize(key);

    // Then every bidi/isolate/mark is neutralized
    assertThat(sanitized).isEqualTo("a_b_c_d_e_f_g_h_i_j_k_l_m");
  }

  @Test
  @DisplayName("should require explicit hasPending choice")
  void shouldRequireExplicitHasPendingChoice() {
    // Given a provider that explicitly chooses fail-closed (no inherited default exists)
    TaskQueueProvider provider =
        new TaskQueueProvider() {
          final Map<String, TaskQueue> queues = new ConcurrentHashMap<>();

          @Override
          public boolean isIdle(String key) {
            Keys.requireKey(key);
            TaskQueue queue = queues.get(key);
            return queue == null || queue.isIdle();
          }

          @Override
          public boolean hasPending(String key) {
            // Explicit fail-closed: forfeit peer-free inline nesting rather than risk overtake.
            return true;
          }

          @Override
          public TaskQueue getForSubmit(String key) {
            Keys.requireKey(key);
            return queues.computeIfAbsent(key, k -> new TaskQueue());
          }

          @Override
          public void abortSubmit(String key, TaskQueue queue) {}
        };

    // When / Then the explicit choice assumes peers so nested sync fails instead of overtaking
    assertThat(provider.hasPending("key")).isTrue();
    // And the contract forces the choice at compile time: no default method remains.
    assertThat(
            java.util.Arrays.stream(TaskQueueProvider.class.getMethods())
                .filter(m -> m.getName().equals("hasPending"))
                .findFirst()
                .orElseThrow()
                .isDefault())
        .isFalse();
  }

  @Test
  @DisplayName("should reject null and blank keys")
  void shouldRejectNullAndBlankKeys() {
    MapProvider provider = new MapProvider();

    assertThatThrownBy(() -> provider.getForSubmit(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> provider.isIdle(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> provider.getForSubmit(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.isIdle("")).isInstanceOf(IllegalArgumentException.class);
  }
}
