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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Trace scope template")
class TraceScopeTemplateTest {

  @Test
  @DisplayName("should unify scope handling behind a single template with an error policy")
  void shouldUnifyScopeHandlingBehindSingleTemplateWithErrorPolicy() {
    boolean policyExists;
    try {
      Class.forName("io.github.jinganix.peashooter.trace.ErrorPolicy");
      policyExists = true;
    } catch (ClassNotFoundException e) {
      policyExists = false;
    }
    assertThat(policyExists).as("expected ErrorPolicy enum").isTrue();

    // Split is by failure contract only: one unchecked template plus one checked template sharing
    // the ScopeRequest parameter object and the InstallMode policy (no per-entry-point
    // executeCallable/executeSpan duplicates, no boolean-leniency seven-arg execute).
    assertThat(hasMethod("executeUnchecked")).as("unchecked template").isTrue();
    assertThat(hasMethod("tracedRunUnchecked")).as("unchecked run").isTrue();
    assertThat(hasMethod("tracedRunChecked")).as("checked run").isTrue();
    assertThat(hasMethod("executeCallable"))
        .as("duplicate callable template should be gone")
        .isFalse();
    assertThat(hasMethod("executeSpan")).as("duplicate span template should be gone").isFalse();
    assertThat(hasSevenArgBooleanExecute())
        .as("seven-arg boolean execute should be gone")
        .isFalse();

    // Parameter object validates the span-source invariant once at construction.
    boolean requestExists;
    try {
      Class.forName("io.github.jinganix.peashooter.trace.TraceScope$ScopeRequest");
      requestExists = true;
    } catch (ClassNotFoundException e) {
      requestExists = false;
    }
    assertThat(requestExists).as("expected ScopeRequest parameter object").isTrue();
    boolean modeExists;
    try {
      Class.forName("io.github.jinganix.peashooter.trace.TraceScope$InstallMode");
      modeExists = true;
    } catch (ClassNotFoundException e) {
      modeExists = false;
    }
    assertThat(modeExists).as("expected InstallMode policy enum").isTrue();
  }

  private boolean hasSevenArgBooleanExecute() {
    return java.util.Arrays.stream(TraceScope.class.getDeclaredMethods())
        .anyMatch(
            m ->
                m.getName().equals("execute")
                    && m.getParameterCount() == 7
                    && java.util.Arrays.stream(m.getParameterTypes())
                        .anyMatch(t -> t == boolean.class));
  }

  private boolean hasMethod(String name) {
    return java.util.Arrays.stream(TraceScope.class.getDeclaredMethods())
        .anyMatch(m -> m.getName().equals(name));
  }
}
