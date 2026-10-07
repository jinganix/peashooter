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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Executor ownership and failure context")
class ExecutorOwnershipAndContextTest {

  @Test
  @DisplayName("should leave the backing service running without owning its lifecycle")
  void shouldLeaveBackingServiceRunningWithoutOwningItsLifecycle() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);
    try {
      assertThat(pool.isShutdown()).isFalse();
      assertThat(pool.submit(() -> "ok").get(10, TimeUnit.SECONDS)).isEqualTo("ok");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("should preserve execution context when a checked wait loses its cause")
  void shouldPreserveExecutionContextWhenCheckedWaitLosesCause() throws Exception {
    OrderedTraceExecutor executor = new OrderedTraceExecutor(Runnable::run);
    CompletableFuture<String> future =
        new CompletableFuture<>() {
          @Override
          public String get(long timeout, TimeUnit unit) throws ExecutionException {
            throw new ExecutionException((Throwable) null);
          }

          @Override
          public String get() throws ExecutionException {
            throw new ExecutionException((Throwable) null);
          }
        };
    Method enqueueChecked =
        SubmissionRouter.class.getDeclaredMethod(
            "enqueueChecked",
            String.class,
            Runnable.class,
            CompletableFuture.class,
            Class.class,
            long.class,
            long.class);
    enqueueChecked.setAccessible(true);
    java.lang.reflect.Field submissionsField =
        OrderedTraceExecutor.class.getDeclaredField("submissions");
    submissionsField.setAccessible(true);
    Object submissions = submissionsField.get(executor);
    long waitNanos = TimeUnit.SECONDS.toNanos(10);
    long deadlineNanos = SyncWait.deadlineOf(waitNanos);

    Throwable thrown =
        catchThrowable(
            () -> {
              try {
                enqueueChecked.invoke(
                    submissions,
                    "k",
                    (Runnable) () -> {},
                    future,
                    java.io.IOException.class,
                    waitNanos,
                    deadlineNanos);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });

    assertThat(thrown).isInstanceOf(CompletionException.class);
    assertThat(thrown.getCause()).isInstanceOf(ExecutionException.class);
  }

  @Test
  @DisplayName("should reject a null future when building a timeout failure")
  void shouldRejectNullFutureWhenBuildingTimeoutFailure() {
    TimeoutException cause = new TimeoutException("timed out");
    assertThatThrownBy(() -> SyncWait.timeoutFor("k", 1L, System.nanoTime(), cause, null))
        .isInstanceOf(NullPointerException.class);
  }
}
