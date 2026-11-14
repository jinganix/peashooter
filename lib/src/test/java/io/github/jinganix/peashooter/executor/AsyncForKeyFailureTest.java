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

import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.queue.RejectionAware;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Async forKey failure contract")
class AsyncForKeyFailureTest {

  static final class AwareSupplier implements Supplier<String>, RejectionAware {
    final AtomicReference<Throwable> seen = new AtomicReference<>();

    @Override
    public String get() {
      return "value";
    }

    @Override
    public void rejected(Throwable cause) {
      seen.set(cause);
    }
  }

  @Test
  @DisplayName("should notify an aware supplier when the executor rejects the submission")
  void shouldNotifyAwareSupplierWhenExecutorRejectsSubmission() {
    // Given a rejecting executor and a supplier that observes rejections
    RejectedExecutionException rejection = new RejectedExecutionException("saturated");
    Executor rejecting =
        command -> {
          throw rejection;
        };
    OrderedTraceExecutor executor = OrderedTraceExecutor.builder(rejecting).build();
    AwareSupplier supplier = new AwareSupplier();

    // When the submission is rejected Then the failure also surfaces to the caller
    assertThatThrownBy(() -> executor.supplyAsync("k", supplier)).isSameAs(rejection);

    // And the supplier observes the rejection through the future-callback chain
    assertThat(supplier.seen.get()).isSameAs(rejection);
  }

  @Test
  @DisplayName("should return a completed future when span capture fails with an exception")
  void shouldReturnCompletedFutureWhenSpanCaptureFailsWithException() throws Exception {
    Tracer broken =
        new DefaultTracer() {
          @Override
          public io.github.jinganix.peashooter.trace.Span getSpan() {
            throw new IllegalStateException("span boom");
          }
        };
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider(),
            new DefaultExecutorSelector(new TraceExecutor(Runnable::run, broken)),
            broken);

    CompletableFuture<Void> future = executor.submitAsync("k", () -> {});
    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("should stay loud when span capture fails with an error")
  void shouldStayLoudWhenSpanCaptureFailsWithError() {
    AssertionError boom = new AssertionError("span error");
    Tracer broken =
        new DefaultTracer() {
          @Override
          public io.github.jinganix.peashooter.trace.Span getSpan() {
            throw boom;
          }
        };
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            new io.github.jinganix.peashooter.queue.CaffeineTaskQueueProvider(),
            new DefaultExecutorSelector(new TraceExecutor(Runnable::run, broken)),
            broken);

    assertThatThrownBy(() -> executor.submitAsync("k", () -> {})).isSameAs(boom);
  }
}
