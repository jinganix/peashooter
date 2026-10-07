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

/**
 * Peashooter call-tasksequencing library.
 *
 * <p>Exports the public API packages only. The {@code internal} package is intentionally not
 * exported or opened: it stays {@code public} solely for sharing inside this jar, and modular
 * consumers cannot depend on it.
 */
module io.github.jinganix.peashooter {
  requires com.github.benmanes.caffeine;
  requires org.slf4j;

  exports io.github.jinganix.peashooter;
  exports io.github.jinganix.peashooter.executor;
  exports io.github.jinganix.peashooter.queue;
  exports io.github.jinganix.peashooter.trace;
}
