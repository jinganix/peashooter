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

import io.github.jinganix.peashooter.Tracer;
import java.util.UUID;

/** Default implementation for {@link Tracer}. */
public class DefaultTracer implements Tracer {

  private static final ThreadLocal<Span> SPAN = new ThreadLocal<>();

  /** Constructor. */
  public DefaultTracer() {}

  @Override
  public Span getSpan() {
    return SPAN.get();
  }

  @Override
  public void setSpan(Span span) {
    SPAN.set(span);
  }

  @Override
  public void clearSpan() {
    SPAN.remove();
  }

  @Override
  public String nextId() {
    return UUID.randomUUID().toString();
  }

  @Override
  public void beforeCall(Span span) {}

  @Override
  public void afterCall(Span span, Exception e) {
    if (span.isRoot()) {
      clearSpan();
    }
  }
}
