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

package io.github.jinganix.peashooter.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.executor.DirectExecutor;
import io.github.jinganix.peashooter.executor.OrderedTraceExecutor;
import io.github.jinganix.peashooter.queue.TaskQueue;
import io.github.jinganix.peashooter.trace.DefaultTracer;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryTaskQueueProvider")
class InMemoryTaskQueueProviderTest {

  @Test
  @DisplayName("should return stable instance per key without creating on probe")
  void shouldReturnStableInstancePerKey() {
    // Given
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> new TaskQueue());

    // When
    TaskQueue first = provider.getForSubmit("a");
    TaskQueue second = provider.getForSubmit("a");
    TaskQueue other = provider.getForSubmit("b");

    // Then same key maps to the same instance, probes never materialize entries
    assertThat(second).isSameAs(first);
    assertThat(other).isNotSameAs(first);
    assertThat(provider.isIdle("a")).isTrue();
    assertThat(provider.isIdle("missing")).isTrue();
  }

  @Test
  @DisplayName("should reject invalid keys and nulls like every provider")
  void shouldRejectInvalidKeys() {
    // Given
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> new TaskQueue());

    // When / Then
    assertThatThrownBy(() -> provider.getForSubmit(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> provider.getForSubmit("  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.isIdle(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> provider.isIdle("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.abortSubmit("a", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should release the fence on abort while keeping the live entry")
  void shouldReleaseFenceOnAbortWhileKeepingLiveEntry() {
    // Given a fenced submission (e.g. before a throwing selector)
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> new TaskQueue());
    TaskQueue queue = provider.getForSubmit("a");

    // When aborting the abandoned submission
    provider.abortSubmit("a", queue);

    // Then the fence is released but the live entry is untouched
    assertThat(provider.pendingSubmits("a")).isZero();
    assertThat(provider.isIdle("a")).isTrue();
    assertThat(provider.getForSubmit("a")).isSameAs(queue);
  }

  @Test
  @DisplayName("should reject a null factory result with a clear message")
  void shouldRejectNullFactoryResult() {
    // Given a factory that returns null
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> null);

    // When / Then the failure names the key instead of a bare map NPE
    assertThatThrownBy(() -> provider.getForSubmit("a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("a");
  }

  @Test
  @DisplayName("should count one fence per submit and release exactly one per abort")
  void shouldCountFencePerSubmit() {
    // Given two outstanding submit fences for the same key
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> new TaskQueue());
    TaskQueue queue = provider.getForSubmit("a");
    provider.getForSubmit("a");

    // When releasing one fence Then the other still holds (count, not boolean)
    assertThat(provider.pendingSubmits("a")).isEqualTo(2);
    provider.abortSubmit("a", queue);
    assertThat(provider.pendingSubmits("a")).isEqualTo(1);

    // And releasing the second fence clears it without replacing the entry
    provider.abortSubmit("a", queue);
    assertThat(provider.pendingSubmits("a")).isZero();
    assertThat(provider.getForSubmit("a")).isSameAs(queue);
  }

  @Test
  @DisplayName("should keep the entry live without an execute release when submission is enqueued")
  void shouldKeepEntryLiveWithoutExecuteReleaseWhenSubmissionIsEnqueued() {
    // Given a fenced submission over the non-evicting double
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> new TaskQueue());
    TaskQueue queue = provider.getForSubmit("a");

    // When the submission is enqueued Then no release hook runs (nothing can evict
    // the entry), so the fence stays until an explicit abort
    queue.execute(DirectExecutor.INSTANCE, () -> {});

    assertThat(provider.pendingSubmits("a")).isEqualTo(1);
    assertThat(provider.getForSubmit("a")).isSameAs(queue);
  }

  @Test
  @DisplayName("should keep the fence exact when an inline task throws Error after enqueue")
  void shouldKeepFenceExactWhenInlineTaskThrowsError() {
    // Given a fenced submission run inline
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> new TaskQueue());
    TaskQueue queue = provider.getForSubmit("a");
    AssertionError fatal = new AssertionError("task boom");

    // When the inline task throws Error (enqueued, runner died fail-open)
    assertThatThrownBy(
            () ->
                queue.execute(
                    DirectExecutor.INSTANCE,
                    () -> {
                      throw fatal;
                    }))
        .isSameAs(fatal);

    // Then the failure path added no fence and the live entry is untouched
    assertThat(provider.pendingSubmits("a")).isEqualTo(1);
    assertThat(provider.getForSubmit("a")).isSameAs(queue);
  }

  @Test
  @DisplayName("should ignore abort for a foreign instance without touching the live fence")
  void shouldIgnoreAbortForForeignInstance() {
    // Given a live entry with one outstanding fence
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> new TaskQueue());
    TaskQueue queue = provider.getForSubmit("a");

    // When aborting with a detached instance Then the live fence is untouched
    provider.abortSubmit("a", new TaskQueue());
    assertThat(provider.pendingSubmits("a")).isEqualTo(1);

    // And aborting with the live instance still releases exactly one fence
    provider.abortSubmit("a", queue);
    assertThat(provider.pendingSubmits("a")).isZero();
  }

  @Test
  @DisplayName("should return the factory delegate directly without an inheriting wrapper")
  void shouldReturnDelegateDirectlyWhenFactoryCreatesQueue() {
    // Given a factory recording the instance it builds
    AtomicReference<TaskQueue> created = new AtomicReference<>();
    InMemoryTaskQueueProvider provider =
        new InMemoryTaskQueueProvider(
            key -> {
              TaskQueue queue = new TaskQueue();
              created.set(queue);
              return queue;
            });

    // When handing out the queue for submit Then it must be the delegate itself:
    // a subclass wrapper would waste its own inherited deque/monitor while all
    // work runs on the delegate, misleading readers about which state is live
    TaskQueue queue = provider.getForSubmit("a");

    assertThat(queue).isSameAs(created.get());
  }

  @Test
  @DisplayName("should hold no TaskQueue subclass wrapper when fenced")
  void shouldHoldNoTaskQueueSubclassWrapperWhenFenced() {
    // When inspecting the provider shape Then no inner type may extend TaskQueue:
    // fence tracking is an external count on the entry, never a decorating subclass
    for (Class<?> inner : InMemoryTaskQueueProvider.class.getDeclaredClasses()) {
      assertThat(TaskQueue.class.isAssignableFrom(inner)).as(inner.getName()).isFalse();
    }
  }

  @Test
  @DisplayName("should keep fence semantics consistent with production when submitting")
  void shouldKeepFenceSemanticsConsistentWithProductionWhenSubmitting() {
    // Given the in-memory double over a plain queue
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> new TaskQueue());

    // When a fenced submission reaches execute Then the queue drains and stays
    // the stable per-key instance, exactly like the production provider
    TaskQueue queue = provider.getForSubmit("a");
    queue.execute(DirectExecutor.INSTANCE, () -> {});

    assertThat(provider.isIdle("a")).isTrue();
    assertThat(provider.getForSubmit("a")).isSameAs(queue);

    // And a foreign abort still releases nothing, so stale callers never disturb live state
    provider.abortSubmit("a", new TaskQueue());
    assertThat(provider.getForSubmit("a")).isSameAs(queue);
  }

  @Test
  @DisplayName("should release the fence when the executor selector throws")
  void shouldReleaseFenceWhenSelectorThrows() {
    // Given an executor whose selector always throws, over the in-memory provider
    InMemoryTaskQueueProvider provider = new InMemoryTaskQueueProvider(key -> new TaskQueue());
    Tracer tracer = new DefaultTracer();
    OrderedTraceExecutor executor =
        new OrderedTraceExecutor(
            provider,
            (queue, sync) -> {
              throw new IllegalStateException("boom");
            },
            tracer);

    // When submitting Then the selector failure propagates
    assertThatThrownBy(() -> executor.executeAsync("a", () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("boom");

    // And the submit fence was released instead of leaking (production abortSubmit path)
    assertThat(provider.pendingSubmits("a")).isZero();
  }
}
