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

package io.github.jinganix.peashooter.trace;

import io.github.jinganix.peashooter.TraceIdGenerator;

/** Trace task call chain. */
public class Span {

  private final Span parent;

  private final String traceId;

  /**
   * Constructor.
   *
   * @param traceId trace id
   * @param parent {@link Span}
   */
  public Span(String traceId, Span parent) {
    this.parent = parent;
    this.traceId = traceId;
  }

  /**
   * Constructor.
   *
   * @param traceIdGenerator {@link TraceIdGenerator}
   * @param parent {@link Span}
   */
  public Span(TraceIdGenerator traceIdGenerator, Span parent) {
    this.parent = parent;
    this.traceId = parent == null ? traceIdGenerator.nextId() : parent.traceId;
  }

  /**
   * Get the trace id.
   *
   * @return trace id
   */
  public String getTraceId() {
    return traceId;
  }

  /**
   * Get the parent {@link Span}.
   *
   * @return parent {@link Span}
   */
  public Span getParent() {
    return parent;
  }

  /**
   * Check if the root {@link Span}.
   *
   * @return true if the root.
   */
  public boolean isRoot() {
    return parent == null;
  }
}
