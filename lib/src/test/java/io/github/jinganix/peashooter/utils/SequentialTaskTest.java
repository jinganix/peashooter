/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * https://github.com/jinganix/peashooter
 */

package io.github.jinganix.peashooter.utils;

import static java.util.concurrent.CompletableFuture.runAsync;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.TestUtils;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SequentialTask")
public class SequentialTaskTest {

  @Nested
  @DisplayName("when run concurrently")
  class WhenRunConcurrently {

    @Test
    @DisplayName("then throw exception")
    void thenThrowException() {
      AtomicBoolean lock = new AtomicBoolean(false);
      CompletableFuture<?> future =
          CompletableFuture.allOf(
              runAsync(new SequentialTask(lock, () -> TestUtils.sleep(1000))),
              runAsync(new SequentialTask(lock, () -> TestUtils.sleep(1000))));
      assertThatThrownBy(future::join)
          .isInstanceOf(CompletionException.class)
          .rootCause()
          .isInstanceOf(RuntimeException.class)
          .hasMessage("Task is running concurrently");
    }
  }
}
