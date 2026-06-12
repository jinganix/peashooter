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

import io.github.jinganix.peashooter.trace.TraceIds;

/** Generator for W3C-compatible trace and span ids. */
public interface TraceIdGenerator {

  /**
   * Generate a 128-bit trace id (32 lowercase hex characters).
   *
   * @return trace id
   */
  String nextId();

  /**
   * Generate a 64-bit span id (16 lowercase hex characters).
   *
   * @return span id
   */
  default String nextSpanId() {
    return TraceIds.nextSpanId();
  }
}
