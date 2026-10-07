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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Sync execution failure translation")
class SyncWaitExecutionFailureTranslationTest {

  private static OrderedTraceExecutor newExecutor() {
    return new OrderedTraceExecutor(DirectExecutor.INSTANCE);
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
    throw (E) failure;
  }

  @Test
  @DisplayName("should rethrow runtime cause as-is")
  void shouldRethrowRuntimeCauseAsIs() {
    OrderedTraceExecutor executor = newExecutor();
    IllegalStateException runtime = new IllegalStateException("boom");

    assertThatThrownBy(
            () ->
                executor.executeSync(
                    "k",
                    () -> {
                      throw runtime;
                    }))
        .isSameAs(runtime);
  }

  @Test
  @DisplayName("should rethrow error cause as-is")
  void shouldRethrowErrorCauseAsIs() {
    OrderedTraceExecutor executor = newExecutor();
    AssertionError error = new AssertionError("fail");

    assertThatThrownBy(
            () ->
                executor.executeSync(
                    "k",
                    () -> {
                      throw error;
                    }))
        .isSameAs(error);
  }

  @Test
  @DisplayName("should wrap checked cause in completion exception")
  void shouldWrapCheckedCauseInCompletionException() {
    OrderedTraceExecutor executor = newExecutor();
    Exception checked = new Exception("checked");

    assertThatThrownBy(
            () ->
                executor.supply(
                    "k",
                    () -> {
                      sneakyThrow(checked);
                      return null;
                    }))
        .isInstanceOf(CompletionException.class)
        .hasCause(checked);
  }

  @Test
  @DisplayName("should propagate witnessed checked type without wrapping")
  void shouldPropagateWitnessedCheckedTypeWithoutWrapping() {
    OrderedTraceExecutor executor = newExecutor();
    IOException witnessed = new IOException("witnessed");

    assertThatThrownBy(
            () ->
                executor.supplyChecked(
                    "k",
                    IOException.class,
                    () -> {
                      throw witnessed;
                    }))
        .isSameAs(witnessed);
    assertThat(executor.isIdle("k")).isTrue();
  }
}
