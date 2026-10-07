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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Queue encapsulation")
class QueueEncapsulationTest {

  @Test
  @DisplayName("should keep queue state private behind a dedicated monitor")
  void shouldKeepQueueStatePrivateBehindDedicatedMonitor() throws Exception {
    for (String name : new String[] {"head", "tail", "size", "current", "runner"}) {
      Field f = TaskQueue.class.getDeclaredField(name);
      assertThat(Modifier.isPrivate(f.getModifiers()))
          .as("field %s must be private", name)
          .isTrue();
    }
    Field lock = TaskQueue.class.getDeclaredField("lock");
    assertThat(lock.getType()).isEqualTo(Object.class);
    assertThat(Modifier.isPrivate(lock.getModifiers())).isTrue();
    assertThat(Modifier.isFinal(lock.getModifiers())).isTrue();
  }

  @Test
  @DisplayName("should collaborate with subclasses through template methods only")
  void shouldCollaborateThroughTemplateMethodsOnly() {
    for (Field f : TaskQueue.class.getDeclaredFields()) {
      if (Modifier.isProtected(f.getModifiers())) {
        throw new AssertionError(
            "protected field " + f.getName() + " must not remain; use template methods");
      }
    }
    assertThat(hasMethod("pollNext")).isTrue();
    assertThat(hasMethod("discardHeadAndRepoint")).isTrue();
  }

  @Test
  @DisplayName("should not retain unused internal queue helpers")
  void shouldNotRetainUnusedInternalQueueHelpers() {
    assertThat(hasMethod("discardHeadAnyAndRepoint")).isFalse();
    assertThat(hasMethod("peekHead")).isFalse();
    assertThat(hasMethod("runRunner")).isFalse();
  }

  private boolean hasMethod(String name) {
    return java.util.Arrays.stream(TaskQueue.class.getDeclaredMethods())
        .anyMatch(m -> m.getName().equals(name));
  }
}
