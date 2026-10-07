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

/** Generator for W3C-compatible trace and span ids. */
public interface TraceIdGenerator {

  /**
   * Generate a 128-bit trace id (32 lowercase hex characters).
   *
   * <p><b>MUST be globally unique:</b> {@link io.github.jinganix.peashooter.trace.SpanIdKey
   * SpanIdKey} compares only trace and span ids, so reusing an id pair for distinct spans makes
   * their keys compare equal and collide as {@link java.util.Map} keys. Never return all zeros or a
   * constant in tests that build more than one span with the same ids.
   *
   * @return trace id, globally unique and W3C-valid
   */
  String nextTraceId();

  /**
   * Generate a 64-bit span id (16 lowercase hex characters).
   *
   * <p>Intentionally abstract: a custom {@code nextTraceId} silently paired with a global span id
   * fallback would split observability, so generators must implement both explicitly.
   *
   * <p><b>MUST be globally unique</b> (same contract as {@link #nextTraceId}): span ids are never
   * reused across distinct spans; see {@link io.github.jinganix.peashooter.trace.SpanIdKey}.
   *
   * @return span id, globally unique and W3C-valid
   */
  String nextSpanId();
}
