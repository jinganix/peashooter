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

package io.github.jinganix.peashooter.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jinganix.peashooter.ExecutorSelector;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Ordered executor decomposition")
class OrderedExecutorDecompositionTest {

  @Test
  @DisplayName("should delegate each concern to a dedicated collaborator field")
  void shouldDelegateEachConcernToDedicatedCollaboratorField() {
    // Given the facade's declared fields
    List<Class<?>> fields = declaredFieldTypes();

    // Then timeout, reentrancy, sizing, and submission handling each live behind a collaborator
    assertThat(fields)
        .as("facade must delegate instead of inlining these concerns")
        .contains(
            TimeoutPolicy.class, ReentrancyGate.class, MultiKeyGuard.class, SubmissionRouter.class);
  }

  @Test
  @DisplayName("should keep executor selection state out of the facade")
  void shouldKeepExecutorSelectionStateOutOfFacade() {
    // Given the facade's declared fields When checking stored state Then selection stays with the
    // submitter: the facade only passes the selector through during construction.
    assertThat(declaredFieldTypes())
        .as("executor selection must stay in SubmissionRouter, not on the facade")
        .doesNotContain(ExecutorSelector.class);
  }

  private static List<Class<?>> declaredFieldTypes() {
    return Arrays.stream(OrderedTraceExecutor.class.getDeclaredFields())
        .map(Field::getType)
        .toList();
  }
}
