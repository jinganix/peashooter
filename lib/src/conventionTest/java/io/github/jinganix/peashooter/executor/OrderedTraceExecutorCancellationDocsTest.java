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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OrderedTraceExecutor cancellation docs")
class OrderedTraceExecutorCancellationDocsTest {

  @Test
  @DisplayName("should document the non-interrupting cancel mode actually used")
  void shouldDocumentNonInterruptingCancelModeActuallyUsed() throws Exception {
    // Given the sync-timeout path cancels with SubmissionRouter's cancel(false) (a
    // CompletableFuture ignores mayInterruptIfRunning, so running work is never interrupted).
    // Detail lives with the delegate since P2-6 sank facade Javadoc to contracts.
    String source = Files.readString(sourceFile());

    // When / Then the delegate must not claim a cancel(true) attempt, which conventionally
    // implies interrupting running work and contradicts the documented non-interruption contract.
    assertThat(source)
        .as("sync-timeout cancellation must be documented as cancel(false), not cancel(true)")
        .doesNotContain("cancel(true)");
    assertThat(source).contains("cancel(false)");
  }

  private static Path sourceFile() {
    Path base = Path.of(System.getProperty("user.dir"));
    Path direct =
        base.resolve(
            "lib/src/main/java/io/github/jinganix/peashooter/executor/SubmissionRouter.java");
    if (Files.exists(direct)) {
      return direct;
    }
    return base.resolve(
        "src/main/java/io/github/jinganix/peashooter/executor/SubmissionRouter.java");
  }
}
