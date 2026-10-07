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

package io.github.jinganix.peashooter;

import io.github.jinganix.peashooter.trace.Span;

/** Traced task callback. */
public interface TraceCallback {

  /**
   * Observation only: must not touch thread state. TraceScope owns the single save/restore around
   * this callback; any pollution is restored with a warning before the delegate runs.
   *
   * @param span {@link Span}
   */
  void beforeCall(Span span);

  /**
   * Observation only: must not touch thread state. TraceScope owns the single save/restore around
   * this callback. Outcome is reported as-is: null on success, task failure otherwise.
   *
   * @param span {@link Span}
   * @param e task outcome, {@code null} on success
   */
  void afterCall(Span span, Throwable e);
}
