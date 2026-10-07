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

import io.github.jinganix.peashooter.trace.Span;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Package-private single-use {@link SpanAccessor.Scope}: same-thread close only. Top-level (not
 * nested) because interface member types are implicitly public and cannot be privatized; only the
 * {@link SpanAccessor.Scope} interface is public API.
 */
final class SpanScope implements SpanAccessor.Scope {
  private final SpanAccessor owner;
  private final Span previous;
  private final Thread ownerThread;
  private final AtomicBoolean closed = new AtomicBoolean();

  SpanScope(SpanAccessor owner, Span previous) {
    this.owner = owner;
    this.previous = previous;
    this.ownerThread = Thread.currentThread();
  }

  @Override
  public void close() {
    // Already closed: no-op on any thread so a second close never clobbers newer state.
    if (closed.get()) {
      return;
    }
    // Foreign close must throw without consuming the scope, otherwise the owner
    // could never restore `previous` and pooled threads would leak spans.
    if (Thread.currentThread() != ownerThread) {
      throw new IllegalStateException("SpanScope must be closed on the owning thread");
    }
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (previous == null) {
      owner.clearSpan();
    } else {
      owner.setSpan(previous);
    }
  }
}
