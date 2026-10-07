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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Sync-wait timeout policy for {@link OrderedTraceExecutor}.
 *
 * <p>Owns the volatile timeout nanos, default/saturation constants, and deadline creation so the
 * facade never touches time math directly. All state stays in one volatile long read.
 */
final class TimeoutPolicy {

  /** Default sync wait: 10 seconds. Named so the default is documented in one place. */
  static final long DEFAULT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);

  /** Largest duration exactly representable in nanos; at or beyond it waits saturate. */
  static final Duration MAX_NANOS_DURATION = Duration.ofNanos(Long.MAX_VALUE);

  /**
   * Sync call timeout in nanoseconds, held as a single {@code volatile long} so reads and the
   * concurrent {@link #setTimeout(Duration)} writer can never observe a torn or mixed snapshot.
   */
  private volatile long timeoutNanos = DEFAULT_TIMEOUT_NANOS;

  /**
   * Sets the maximum wait for sync calls. Published as a single {@code volatile long} (nanoseconds)
   * so in-flight sync calls always observe one consistent value.
   */
  void setTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    if (timeout.compareTo(MAX_NANOS_DURATION) >= 0) {
      this.timeoutNanos = Long.MAX_VALUE;
      return;
    }
    this.timeoutNanos = timeout.toNanos();
  }

  /** Returns the currently configured sync wait timeout. */
  Duration getTimeout() {
    return Duration.ofNanos(timeoutNanos);
  }

  /** Current wait in nanos (single volatile read). */
  long waitNanos() {
    return timeoutNanos;
  }
}
