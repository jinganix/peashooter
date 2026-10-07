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

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Sneaky-throw propagation")
class SneakyThrowPropagationTest {

  static class CustomChecked extends Exception {
    CustomChecked(String message) {
      super(message);
    }
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
    throw (E) t;
  }

  @Test
  @DisplayName(
      "should wrap a foreign checked failure when supplyChecked smuggles an undeclared type")
  void shouldWrapForeignCheckedWhenSupplyCheckedSmugglesUndeclaredType() {
    // Given a checked supply declaring CustomChecked but smuggling a foreign IOException
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(),
            new DefaultExecutorSelector(
                new TraceExecutor(newSingleThreadExecutor(), new DefaultTracer())),
            new DefaultTracer());
    java.io.IOException foreign = new java.io.IOException("foreign boom");

    // When the supplier smuggles a checked type unrelated to E
    // Then it must surface wrapped as unchecked, never masquerading under E's static type
    assertThatThrownBy(
            () ->
                executor.<String, CustomChecked>supplyChecked(
                    "k",
                    CustomChecked.class,
                    () -> {
                      sneakyThrow(foreign);
                      return null;
                    }))
        .isInstanceOf(CompletionException.class)
        .hasCause(foreign);
  }

  @Test
  @DisplayName("should unwrap the declared failure when supplyChecked throws its declared type")
  void shouldUnwrapDeclaredFailureWhenSupplyCheckedThrowsDeclaredType() {
    // Given a checked supply declaring CustomChecked
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new CaffeineTaskQueueProvider(),
            new DefaultExecutorSelector(
                new TraceExecutor(newSingleThreadExecutor(), new DefaultTracer())),
            new DefaultTracer());
    CustomChecked declared = new CustomChecked("declared boom");

    // When the supplier throws its declared type Then it propagates unwrapped with static type
    assertThatThrownBy(
            () ->
                executor.<String, CustomChecked>supplyChecked(
                    "k",
                    CustomChecked.class,
                    () -> {
                      throw declared;
                    }))
        .isSameAs(declared);
    assertThat(declared.getSuppressed()).isEmpty();
  }
}
