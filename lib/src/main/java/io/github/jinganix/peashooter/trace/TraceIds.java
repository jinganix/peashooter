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

package io.github.jinganix.peashooter.trace;

import java.util.concurrent.ThreadLocalRandom;

/**
 * W3C Trace Context compatible id generation ({@code trace-id}: 128-bit, {@code span-id}: 64-bit).
 */
public final class TraceIds {

  private static final String INVALID_ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";

  private static final String INVALID_ALL_ZERO_SPAN_ID = "0000000000000000";

  private TraceIds() {}

  /**
   * Generates a 128-bit trace id as 32 lowercase hex characters.
   *
   * @return W3C-compatible trace id
   */
  public static String nextTraceId() {
    while (true) {
      long high = ThreadLocalRandom.current().nextLong();
      long low = ThreadLocalRandom.current().nextLong();
      if (high != 0L || low != 0L) {
        return String.format("%016x%016x", high, low);
      }
    }
  }

  /**
   * Generates a 64-bit span id as 16 lowercase hex characters.
   *
   * @return W3C-compatible span id
   */
  public static String nextSpanId() {
    while (true) {
      long value = ThreadLocalRandom.current().nextLong();
      if (value != 0L) {
        return String.format("%016x", value);
      }
    }
  }

  /**
   * Whether {@code traceId} is a valid W3C trace id (32 lowercase hex, not all zeros).
   *
   * @param traceId candidate trace id
   * @return {@code true} if valid
   */
  public static boolean isValidTraceId(String traceId) {
    return traceId != null
        && traceId.length() == 32
        && isLowerHex(traceId)
        && !INVALID_ALL_ZERO_TRACE_ID.contentEquals(traceId);
  }

  /**
   * Whether {@code spanId} is a valid W3C span id (16 lowercase hex, not all zeros).
   *
   * @param spanId candidate span id
   * @return {@code true} if valid
   */
  public static boolean isValidSpanId(String spanId) {
    return spanId != null
        && spanId.length() == 16
        && isLowerHex(spanId)
        && !INVALID_ALL_ZERO_SPAN_ID.contentEquals(spanId);
  }

  static String normalizeHex(String value) {
    return value.toLowerCase();
  }

  private static boolean isLowerHex(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean digit = c >= '0' && c <= '9';
      boolean lower = c >= 'a' && c <= 'f';
      if (!digit && !lower) {
        return false;
      }
    }
    return true;
  }
}
