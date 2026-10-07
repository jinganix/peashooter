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
 *
 * <p>Ids use {@link java.util.concurrent.ThreadLocalRandom} for speed on the hot path. They are
 * unique enough for tracing and sampling but not unpredictable: do not use them as security tokens,
 * secrets, or unguessable capability keys. Provide a custom {@link
 * io.github.jinganix.peashooter.TraceIdGenerator} backed by {@link java.security.SecureRandom} when
 * unpredictability is required.
 */
public final class TraceIds {

  private static final char[] LOWER_HEX_DIGITS = "0123456789abcdef".toCharArray();

  private TraceIds() {}

  /**
   * Generates a 128-bit trace id as 32 lowercase hex characters.
   *
   * @return W3C-compatible trace id
   */
  public static String nextTraceId() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    long high = random.nextLong();
    long low = random.nextLong();
    if (high == 0L && low == 0L) {
      // Vanishingly rare all-zero draw is the only invalid outcome: fix deterministically
      // instead of looping, so generation never allocates a second id pair.
      low = 1L;
    }
    char[] chars = new char[32];
    toLowerHex(high, chars, 0);
    toLowerHex(low, chars, 16);
    return new String(chars);
  }

  /**
   * Generates a 64-bit span id as 16 lowercase hex characters.
   *
   * @return W3C-compatible span id
   */
  public static String nextSpanId() {
    long value = ThreadLocalRandom.current().nextLong();
    if (value == 0L) {
      value = 1L;
    }
    char[] chars = new char[16];
    toLowerHex(value, chars, 0);
    return new String(chars);
  }

  private static void toLowerHex(long value, char[] chars, int offset) {
    for (int i = 0; i < 16; i++) {
      chars[offset + i] = LOWER_HEX_DIGITS[(int) ((value >>> (60 - (i * 4))) & 0xF)];
    }
  }

  /**
   * Whether {@code traceId} is a valid W3C trace id (32 lowercase hex, not all zeros).
   *
   * <p>Single scan: hex shape and non-zero are checked in one pass instead of {@code isLowerHex}
   * plus an {@code equals} comparison.
   *
   * @param traceId candidate trace id
   * @return {@code true} if valid
   */
  public static boolean isValidTraceId(String traceId) {
    return isValidId(traceId, 32);
  }

  /**
   * Whether {@code spanId} is a valid W3C span id (16 lowercase hex, not all zeros).
   *
   * <p>Single scan like {@link #isValidTraceId}.
   *
   * @param spanId candidate span id
   * @return {@code true} if valid
   */
  public static boolean isValidSpanId(String spanId) {
    return isValidId(spanId, 16);
  }

  private static boolean isValidId(String value, int length) {
    if (value == null || value.length() != length) {
      return false;
    }
    boolean nonZero = false;
    for (int i = 0; i < length; i++) {
      char c = value.charAt(i);
      boolean digit = c >= '0' && c <= '9';
      boolean lower = c >= 'a' && c <= 'f';
      if (!digit && !lower) {
        return false;
      }
      if (c != '0') {
        nonZero = true;
      }
    }
    return nonZero;
  }

  /**
   * Whether {@code value} is non-empty lowercase hex.
   *
   * <p>{@code null} and empty both report {@code false}: callers validating nullable ingress ids
   * get a single predicate without a separate null check.
   *
   * @param value candidate value
   * @return {@code true} if non-empty lowercase hex
   */
  public static boolean isLowerHex(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
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
