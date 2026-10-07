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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executor variants for parameterized tests, owned per holder.
 *
 * <p>Each instance owns private pools: closing one holder never interrupts in-flight work of
 * another test class sharing the same JVM. Hold one instance per test class (for example a {@code
 * static} field) and close it from an {@code @AfterAll} method. Never share pools across test
 * classes through a static: one class's {@code AfterAll} would break every class still running on
 * them.
 */
public final class ExecutorFixture implements AutoCloseable {

  private final Map<String, Executor> executors = create();

  private static final ThreadFactory DAEMON_FACTORY =
      new ThreadFactory() {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
          Thread thread = new Thread(runnable, "test-pool-" + sequence.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        }
      };

  /**
   * Returns this holder's executors, creating them once with the holder. The map is unmodifiable so
   * no caller can corrupt the holder's pools for other tests.
   *
   * @return owned executors keyed by variant name
   */
  public Map<String, Executor> executors() {
    return executors;
  }

  /**
   * Shuts down every owned pool (releasing virtual-thread carriers as well as platform threads).
   * Idempotent: closing without live pools is a no-op. Only this holder's pools are touched, so
   * other holders' in-flight tasks keep running.
   */
  @Override
  public void close() {
    for (Executor executor : executors.values()) {
      if (executor instanceof ExecutorService service) {
        service.shutdownNow();
      }
    }
  }

  private static Map<String, Executor> create() {
    Map<String, Executor> executors = new LinkedHashMap<>();
    // Virtual threads are daemon by design; platform pools use the daemon factory below so
    // a leaked pool can never pin the test JVM after a hanging or failing test.
    executors.put("VirtualThreadPerTaskExecutor", Executors.newVirtualThreadPerTaskExecutor());
    executors.put("DirectExecutor", DirectExecutor.INSTANCE);
    executors.put("SingleThreadExecutor", Executors.newSingleThreadExecutor(DAEMON_FACTORY));
    executors.put("CachedThreadPool", Executors.newCachedThreadPool(DAEMON_FACTORY));
    executors.put("FixedThreadPool(2)", Executors.newFixedThreadPool(2, DAEMON_FACTORY));
    executors.put("FixedThreadPool(10)", Executors.newFixedThreadPool(10, DAEMON_FACTORY));
    return Collections.unmodifiableMap(executors);
  }
}
