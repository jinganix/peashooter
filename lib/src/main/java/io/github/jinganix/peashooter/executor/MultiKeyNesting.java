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

package io.github.jinganix.peashooter.executor;

import io.github.jinganix.peashooter.ThrowingSupplier;
import io.github.jinganix.peashooter.internal.Keys;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Multi-key ordering helpers: key normalization and nested single-key acquisition.
 *
 * <p>Keys are sorted in natural {@link String} order and de-duplicated before acquisition, so
 * concurrent callers using opposite key orders cannot deadlock. Nesting folds outermost-first: the
 * first key in {@code ordered} becomes the outermost wrapper. Single-key execution stays with the
 * caller via the {@code single} function, so this class never depends on the executor.
 *
 * <p><b>Isolation level: non-atomic sequential acquisition.</b> Keys are acquired one at a time,
 * outermost first — holding an earlier key while waiting for a later one isolates nothing on the
 * not-yet-held keys: concurrent tasks on those keys may run before this chain acquires them. Only
 * per-key FIFO holds. Callers needing atomicity across keys must use an external lock, not
 * multi-key nesting.
 */
final class MultiKeyNesting {

  private MultiKeyNesting() {}

  /**
   * Maximum distinct keys per multi-key call. Each level holds one pool thread while waiting for
   * the next, so an unbounded collection (e.g. caller-sized input) would hold an unbounded number
   * of threads plus O(N log N) sort work on the calling thread. Fail fast instead of timing out
   * deep in nesting. Sized generously for virtual-thread pools; platform pools hit {@link
   * MultiKeyGuard} sizing first.
   */
  static final int MAX_MULTI_KEYS = 1024;

  /**
   * Single-key checked operation used by {@link #nestChecked}.
   *
   * @param <R> result type
   * @param <E> checked failure type
   */
  @FunctionalInterface
  interface CheckedSingle<R, E extends Throwable> {
    /**
     * Runs {@code inner} while holding {@code key}.
     *
     * @param key ordering key
     * @param inner nested work already wrapping deeper keys
     * @return result
     * @throws E if the work fails
     */
    R apply(String key, ThrowingSupplier<R, E> inner) throws E;
  }

  /**
   * Validates, sorts, and de-duplicates multi-key collections.
   *
   * @param keys ordering keys; no element may be {@code null}, empty, or blank
   * @return sorted de-duplicated keys, never empty
   */
  static List<String> lockKeys(Collection<String> keys) {
    // Single pass over the caller collection: fail-fast collections throw
    // ConcurrentModificationException on concurrent mutation; weakly-consistent ones may still
    // yield a torn view — callers must not mutate the collection during the call. Validation
    // messages keep the indexed form. Sorted ArrayList instead of TreeSet: multi-key calls
    // carry a handful of keys, and a contiguous array sort plus adjacent dedup avoids one
    // red-black node allocation per key; the result is frozen via List.copyOf.
    Objects.requireNonNull(keys, "keys");
    // Bound the copy+sort work before paying it: an oversized caller collection is validated and
    // de-duplicated via a bounded set first (early exit past MAX distinct), then only the bounded
    // distinct set is sorted — never the full oversized input. At most MAX+1 distinct keys are
    // sampled; it is the distinct count that holds pool threads.
    if (keys.size() > MAX_MULTI_KEYS) {
      HashSet<String> distinct = new HashSet<>(MAX_MULTI_KEYS + 1);
      int index = 0;
      for (String key : keys) {
        validateKey(key, index);
        distinct.add(key);
        if (distinct.size() > MAX_MULTI_KEYS) {
          throw tooManyKeys(distinct.size());
        }
        index++;
      }
      // A misreporting collection can claim oversize yet yield nothing: enforce the same
      // non-empty contract as the normal path instead of returning an empty list.
      if (distinct.isEmpty()) {
        throw new IllegalArgumentException("keys must not be empty");
      }
      List<String> sorted = new ArrayList<>(distinct);
      Collections.sort(sorted);
      return List.copyOf(sorted);
    }
    List<String> ordered = new ArrayList<>(Math.min(keys.size(), MAX_MULTI_KEYS + 1));
    int index = 0;
    for (String key : keys) {
      validateKey(key, index);
      ordered.add(key);
      // Do not trust a prior size(): a concurrently growing or misreporting collection must
      // still hit the distinct bound instead of paying an unbounded copy+sort.
      if (ordered.size() > MAX_MULTI_KEYS) {
        throw tooManyKeys(ordered.size());
      }
      index++;
    }
    if (ordered.isEmpty()) {
      throw new IllegalArgumentException("keys must not be empty");
    }
    Collections.sort(ordered);
    int unique = 1;
    for (int i = 1; i < ordered.size(); i++) {
      if (!ordered.get(i).equals(ordered.get(unique - 1))) {
        ordered.set(unique, ordered.get(i));
        unique++;
      }
    }
    // The in-loop size check above already bounds ordered.size() by MAX_MULTI_KEYS, and the
    // de-dup can only shrink it, so unique <= MAX_MULTI_KEYS here.
    return List.copyOf(ordered.subList(0, unique));
  }

  /**
   * Single owner of the per-key validation contract so the oversized and normal paths cannot drift;
   * messages keep the indexed form.
   */
  private static void validateKey(String key, int index) {
    Objects.requireNonNull(key, "keys[" + index + "] must not be null");
    if (key.isBlank()) {
      throw new IllegalArgumentException("keys[" + index + "] must not be empty or blank");
    }
    requireKeyLength(key, index);
  }

  /**
   * Single owner of the per-key length bound so multi-key validation matches {@link
   * Keys#requireKey}: multi-key messages keep the indexed form instead of delegating (which would
   * lose the index).
   */
  private static void requireKeyLength(String key, int index) {
    if (key.length() > Keys.MAX_KEY_CHARS) {
      throw new IllegalArgumentException(
          "keys[" + index + "] too long: len=" + key.length() + " > " + Keys.MAX_KEY_CHARS);
    }
  }

  /** Single owner of the oversize failure so both validation paths report one shape. */
  private static IllegalArgumentException tooManyKeys(int distinct) {
    return new IllegalArgumentException(
        "too many distinct keys: "
            + distinct
            + " > "
            + MAX_MULTI_KEYS
            + "; split the batch into smaller multi-key calls");
  }

  /**
   * Nests per-key runnable acquisitions so keys are acquired outermost-first in {@code ordered}
   * order.
   *
   * @param ordered sorted keys from {@link #lockKeys}
   * @param task innermost work
   * @param single single-key execution holding one key around nested work
   * @return runnable acquiring every key in order
   */
  static Runnable nestSync(
      List<String> ordered, Runnable task, BiConsumer<String, Runnable> single) {
    for (int i = ordered.size() - 1; i >= 0; i--) {
      String key = ordered.get(i);
      Runnable inner = task;
      task = () -> single.accept(key, inner);
    }
    return task;
  }

  /**
   * Nests per-key supplier acquisitions so keys are acquired outermost-first in {@code ordered}
   * order.
   *
   * @param ordered sorted keys from {@link #lockKeys}
   * @param supplier innermost work
   * @param single single-key execution holding one key around nested work
   * @param <R> result type
   * @return supplier acquiring every key in order
   */
  static <R> Supplier<R> nestSupply(
      List<String> ordered, Supplier<R> supplier, BiFunction<String, Supplier<R>, R> single) {
    for (int i = ordered.size() - 1; i >= 0; i--) {
      String key = ordered.get(i);
      Supplier<R> inner = supplier;
      supplier = () -> single.apply(key, inner);
    }
    return supplier;
  }

  /**
   * Nests per-key checked acquisitions so keys are acquired outermost-first in {@code ordered}
   * order.
   *
   * @param ordered sorted keys from {@link #lockKeys}
   * @param supplier innermost work
   * @param single single-key execution holding one key around nested work
   * @param <R> result type
   * @param <E> checked failure type
   * @return supplier acquiring every key in order
   */
  static <R, E extends Throwable> ThrowingSupplier<R, E> nestChecked(
      List<String> ordered, ThrowingSupplier<R, E> supplier, CheckedSingle<R, E> single) {
    for (int i = ordered.size() - 1; i >= 0; i--) {
      String key = ordered.get(i);
      ThrowingSupplier<R, E> inner = supplier;
      supplier = () -> single.apply(key, inner);
    }
    return supplier;
  }
}
