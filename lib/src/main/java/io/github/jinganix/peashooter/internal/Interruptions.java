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

package io.github.jinganix.peashooter.internal;

/**
 * Interruption detection for failure translation.
 *
 * <p>Single owner of the cause-chain walk: a direct {@link InterruptedException} (a checked failure
 * smuggled past {@link Runnable} via sneaky throw) or one wrapped anywhere in the cause chain (e.g.
 * via {@link java.util.concurrent.CompletionException} from unchecked translation or {@link
 * java.util.concurrent.ExecutionException} from future waits) counts as carrying an interruption.
 * Queue, trace-scope, and error-policy paths all delegate here so the semantics cannot drift apart.
 *
 * <p><b>Internal, not public API:</b> this package is an implementation detail and may change
 * without deprecation. It is {@code public} only for in-jar sharing; external code must not depend
 * on it.
 */
public final class Interruptions {

  private Interruptions() {}

  /**
   * Whether {@code failure} carries an interruption anywhere in its cause chain.
   *
   * <p>The walk is depth-bounded (not a visited set): cause chains are short, so the bound
   * terminates adversarial cycles without per-call allocation on this hot path. Only self-causation
   * is rejected by {@link Throwable#initCause}; a two-node cycle (A causes B, B causes A) is
   * constructible with plain JDK calls and invisible to a self-loop check, so an unbounded walk
   * would hang the queue runner or scope template forever.
   *
   * @param failure failure to inspect, may be {@code null}
   * @return {@code true} when {@code failure} or any cause within the bound is an {@link
   *     InterruptedException}
   */
  public static boolean carriesInterrupt(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 128; depth++) {
      if (current instanceof InterruptedException) {
        return true;
      }
      Throwable cause = current.getCause();
      if (cause == current) {
        break;
      }
      current = cause;
    }
    return false;
  }
}
