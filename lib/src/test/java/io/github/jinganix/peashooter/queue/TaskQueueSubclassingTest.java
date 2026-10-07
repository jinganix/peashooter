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

package io.github.jinganix.peashooter.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P0-2: external packages must not be able to subclass {@link TaskQueue} to break
 * lock/current/size/head/tail invariants. Accepts either a sealed shell or a public shell with
 * package-private template methods plus final public API (compiler-enforced, not doc-only).
 */
@DisplayName("TaskQueue external subclassing is compiler-blocked")
class TaskQueueSubclassingTest {

  @Test
  @DisplayName("should lock template methods to the package")
  void templateMethodsLockedToPackage() throws Exception {
    if (TaskQueue.class.isSealed()) {
      assertThat(TaskQueue.class.getPermittedSubclasses()).contains(LockableTaskQueue.class);
      return;
    }
    // Public shell + package-private template path: no protected hooks remain.
    assertHookIsPackagePrivate("tryClaimRunnerLocked", Executor.class);
    assertHookIsPackagePrivate("onEnqueueLocked");
    assertHookIsPackagePrivate("onEnqueueFailed");
    assertHookIsPackagePrivate("onEnqueued", boolean.class);
    assertHookIsPackagePrivate("onRunnerFinished");
    assertHookIsPackagePrivate("describeQueue");
    assertHookIsPackagePrivate("notifyDiscarded", Runnable.class, Throwable.class);
    assertHookIsPackagePrivate("run");

    // Public entry points must be final so an external subclass cannot override ordering.
    assertPublicApiIsFinal("execute", Executor.class, Runnable.class);
    assertPublicApiIsFinal("isIdle");
    assertPublicApiIsFinal("hasPending");

    // Lockable re-declares the claim/run path: must be final so an external
    // Lockable subclass cannot reopen the TaskQueue invariant, and must not be widened
    // past package-private so an external subclass cannot invoke monitor-guarded internals.
    assertThat(
            Modifier.isFinal(
                modifiersOf(LockableTaskQueue.class, "tryClaimRunnerLocked", Executor.class)))
        .as("LockableTaskQueue.tryClaimRunnerLocked must be final")
        .isTrue();
    assertThat(Modifier.isFinal(modifiersOf(LockableTaskQueue.class, "run")))
        .as("LockableTaskQueue.run must be final")
        .isTrue();
    assertOverrideNotWidened(LockableTaskQueue.class, "tryClaimRunnerLocked", Executor.class);
    assertOverrideNotWidened(LockableTaskQueue.class, "run");
  }

  private static void assertOverrideNotWidened(Class<?> type, String name, Class<?>... params)
      throws Exception {
    int mod = modifiersOf(type, name, params);
    assertThat(Modifier.isProtected(mod) || Modifier.isPublic(mod))
        .as(type.getSimpleName() + "." + name + " must not widen past package-private")
        .isFalse();
  }

  private static void assertHookIsPackagePrivate(String name, Class<?>... params) throws Exception {
    int mod = modifiersOf(TaskQueue.class, name, params);
    assertThat(Modifier.isProtected(mod))
        .as("TaskQueue." + name + " must not be protected")
        .isFalse();
    assertThat(Modifier.isPublic(mod)).as("TaskQueue." + name + " must not be public").isFalse();
    assertThat(Modifier.isPrivate(mod)).as("TaskQueue." + name + " must not be private").isFalse();
  }

  private static void assertPublicApiIsFinal(String name, Class<?>... params) throws Exception {
    int mod = modifiersOf(TaskQueue.class, name, params);
    assertThat(Modifier.isPublic(mod)).as("TaskQueue." + name + " must stay public").isTrue();
    assertThat(Modifier.isFinal(mod)).as("TaskQueue." + name + " must be final").isTrue();
  }

  private static int modifiersOf(Class<?> type, String name, Class<?>... params) throws Exception {
    Method m = type.getDeclaredMethod(name, params);
    return m.getModifiers();
  }
}
