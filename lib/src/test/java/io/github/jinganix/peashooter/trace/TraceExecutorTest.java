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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jinganix.peashooter.Tracer;
import io.github.jinganix.peashooter.executor.TraceExecutor;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TraceExecutor")
class TraceExecutorTest {

  Tracer tracer = new DefaultTracer();

  Executor delegate = mock(Executor.class);

  TraceExecutor traceExecutor = new TraceExecutor(delegate, tracer);

  @Nested
  @DisplayName("getTracer")
  class GetTracer {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then return")
      void thenReturn() {
        assertThat(traceExecutor.getTracer()).isEqualTo(tracer);
      }
    }
  }

  @Nested
  @DisplayName("getSpan")
  class GetSpan {

    @Nested
    @DisplayName("when called")
    class WhenCalled {

      @Test
      @DisplayName("then return")
      void thenReturn() {
        assertThat(traceExecutor.getSpan()).isEqualTo(tracer.getSpan());
      }
    }
  }

  @Nested
  @DisplayName("execute")
  class Execute {

    @Nested
    @DisplayName("when called by Runnable")
    class WhenCalledByRunnable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Runnable runnable = () -> {};
        traceExecutor.execute(runnable);
        verify(delegate, times(1)).execute(isA(TraceRunnable.class));
      }
    }

    @Nested
    @DisplayName("when called by TraceRunnable")
    class WhenCalledByTraceRunnable {

      @Test
      @DisplayName("then delegate is called")
      void thenDelegateIsCalled() {
        Runnable runnable = new TraceRunnable(tracer, () -> {});
        traceExecutor.execute(runnable);
        verify(delegate, times(1)).execute(runnable);
      }
    }
  }
}
