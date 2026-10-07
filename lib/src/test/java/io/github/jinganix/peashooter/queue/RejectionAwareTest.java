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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RejectionAware")
class RejectionAwareTest {

  static final class AwareRunnable implements Runnable, RejectionAware {
    final AtomicReference<Throwable> seen = new AtomicReference<>();

    @Override
    public void run() {}

    @Override
    public void rejected(Throwable cause) {
      seen.set(cause);
    }
  }

  @Test
  @DisplayName("should dispatch any Throwable to aware delegates")
  void shouldDispatchAnyThrowableToAwareDelegates() {
    AwareRunnable aware = new AwareRunnable();

    RejectionAware.dispatch((Runnable) aware, new RuntimeException("rejected"));
    assertThat(aware.seen.get()).isInstanceOf(RuntimeException.class);

    RejectionAware.dispatch((Runnable) aware, new AssertionError("boom"));
    assertThat(aware.seen.get()).isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("should reject null cause when dispatching")
  void shouldRejectNullCauseWhenDispatching() {
    AwareRunnable aware = new AwareRunnable();

    assertThatThrownBy(() -> RejectionAware.dispatch((Runnable) aware, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("cause");
  }

  @Test
  @DisplayName("should report whether delegates were notified when dispatching")
  void shouldReportWhetherDelegatesWereNotifiedWhenDispatching() {
    assertThat(RejectionAware.dispatch((Runnable) () -> {}, new RuntimeException("rejected")))
        .isFalse();
    assertThat(
            RejectionAware.dispatch(
                (java.util.function.Supplier<String>) () -> "v", new RuntimeException("rejected")))
        .isFalse();
    assertThat(
            RejectionAware.dispatch(
                (io.github.jinganix.peashooter.ThrowingSupplier<String, RuntimeException>)
                    () -> "v",
                new RuntimeException("rejected")))
        .isFalse();
    assertThatThrownBy(() -> RejectionAware.dispatch((Runnable) null, new RuntimeException("x")))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("delegate");
  }
}
