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
 * Ordering-key validation for queue and trace paths.
 *
 * <p>Single owner of the non-null/non-blank key contract so {@code trace} never depends on the
 * queue abstraction ({@code TaskQueueProvider}) for hot-path validation.
 *
 * <p><b>Internal, not public API:</b> this package is an implementation detail and may change
 * without deprecation. It is {@code public} only because queue, executor, and trace packages share
 * it within the same jar; external code must not depend on it.
 */
public final class Keys {

  private Keys() {}

  /**
   * Maximum ordering-key length. Keys become Caffeine map keys, sort inputs, and log labels: an
   * unbounded key (e.g. MB-sized caller input) would amplify across every path. Sized generously
   * for identifier-style keys; larger payloads belong in task bodies, not ordering keys.
   */
  public static final int MAX_KEY_CHARS = 1024;

  /**
   * Validates an ordering key: must not be {@code null}, empty, or blank, and must fit {@link
   * #MAX_KEY_CHARS}.
   *
   * @param key ordering key
   * @return {@code key} when valid
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws IllegalArgumentException if {@code key} is empty, blank, or too long
   */
  public static String requireKey(String key) {
    Objects.requireNonNull(key, "key");
    // Length first: O(1) fail-fast before the O(n) blank scan on hostile oversized input.
    if (key.length() > MAX_KEY_CHARS) {
      throw new IllegalArgumentException(
          "key too long: len=" + key.length() + " > " + MAX_KEY_CHARS);
    }
    if (key.isBlank()) {
      throw new IllegalArgumentException("key must not be empty or blank");
    }
    return key;
  }
}
