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

import java.util.Objects;

/**
 * Display sanitizer for application-controlled keys in exception and log rendering.
 *
 * <p>Single owner of the key-sanitizing rules. Queue and trace paths use this directly; no
 * interface carries sanitizing helpers.
 *
 * <p><b>Internal, not public API:</b> this package is an implementation detail and may change
 * without deprecation. It is {@code public} only for in-jar sharing; external code must not depend
 * on it.
 */
public final class KeySanitizer {

  private KeySanitizer() {}

  /**
   * Neutralizes log/terminal forging in application-controlled keys for exception and log
   * rendering.
   *
   * <p>Replaces all C0 controls (including TAB and ESC for ANSI sequences, NUL/BEL), DEL, the
   * Unicode line/paragraph breaks NEL/LS/PS, the bidi overrides/embeds/isolates U+202A-U+202E and
   * U+2066-U+2069, the directional marks U+200E/U+200F, the zero-width joiners/spaces U+200B-U+200D
   * and U+2060/U+FEFF, the Mongolian vowel separator U+180E, and the Arabic letter mark U+061C with
   * {@code '_'}. Returns the same instance when clean (common case); copies only when dirty. Use
   * for every message or log label embedding a key; never for map lookups or queue routing, which
   * must keep the exact key.
   *
   * @param key ordering key, must not be {@code null}
   * @return sanitized key for display only
   */
  public static String sanitize(String key) {
    Objects.requireNonNull(key, "key");
    int length = key.length();
    for (int i = 0; i < length; ) {
      int codePoint = key.codePointAt(i);
      if (needsSanitize(codePoint)) {
        StringBuilder sanitized = new StringBuilder(key.length());
        sanitized.append(key, 0, i);
        sanitized.append('_');
        int next = i + Character.charCount(codePoint);
        while (next < length) {
          int cp = key.codePointAt(next);
          if (needsSanitize(cp)) {
            sanitized.append('_');
          } else {
            sanitized.appendCodePoint(cp);
          }
          next += Character.charCount(cp);
        }
        return sanitized.toString();
      }
      i += Character.charCount(codePoint);
    }
    return key;
  }

  private static boolean needsSanitize(int codePoint) {
    if (codePoint < 0x20
        || codePoint == 0x7F
        || (codePoint >= 0x80 && codePoint <= 0x9F)
        || codePoint == 0x061C
        || codePoint == 0x180E
        || codePoint == 0x2028
        || codePoint == 0x2029
        || codePoint == 0x200E
        || codePoint == 0x200F
        || codePoint == 0xFEFF
        || (codePoint >= 0x200B && codePoint <= 0x200D)
        || codePoint == 0x2060
        || (codePoint >= 0x202A && codePoint <= 0x202E)
        || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
      return true;
    }
    if (codePoint <= 0xFFFF) {
      return false;
    }
    // Supplementary planes: musical formatting, tags, and other default-ignorable
    // format controls have no BMP surrogate-level signal; char-level iteration misses
    // them entirely. Sanitize format/control kinds plus the known supplementary
    // default-ignorables so display forging cannot hide above U+FFFF.
    if ((codePoint >= 0x1D173 && codePoint <= 0x1D17A)
        || (codePoint >= 0xE0000 && codePoint <= 0xE0FFF)
        || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
        || (codePoint >= 0x100000 && codePoint <= 0x10FFFD)) {
      return true;
    }
    int type = Character.getType(codePoint);
    return type == Character.FORMAT
        || type == Character.CONTROL
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }
}
