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

package io.github.jinganix.peashooter;

/** Tracer to trace task call chain. */
public interface Tracer extends TraceIdGenerator, TraceCallback {

  /**
   * Get a {@link Span}.
   *
   * @return {@link Span}
   */
  Span getSpan();

  /**
   * Set a {@link Span}.
   *
   * @param span {@link Span}
   */
  void setSpan(Span span);

  /** Clear the stored {@link Span}. */
  void clearSpan();
}
