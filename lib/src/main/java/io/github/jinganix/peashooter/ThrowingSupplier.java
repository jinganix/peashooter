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

import java.util.function.Supplier;

/**
 * {@link Supplier} variant that may throw a checked failure.
 *
 * <p>Use with {@link
 * io.github.jinganix.peashooter.executor.OrderedTraceExecutor#supplyChecked(String, Class,
 * ThrowingSupplier)} to propagate typed checked failures instead of the bare {@link
 * RuntimeException} wrapping applied by {@code supply} (which takes a plain {@link Supplier}).
 *
 * @param <R> result type
 * @param <E> checked failure type
 */
@FunctionalInterface
public interface ThrowingSupplier<R, E extends Throwable> {

  /**
   * Computes a result.
   *
   * @return result
   * @throws E on failure
   */
  R get() throws E;
}
