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

import static io.github.jinganix.peashooter.utils.TestUtils.awaitCountDown;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("TaskQueue rejection logging")
class TaskQueueDiscardLogTest {

  private Logger queueLogger;
  private ListAppender<ILoggingEvent> appender;
  private Level savedLevel;

  @BeforeEach
  void attachAppender() {
    queueLogger = (Logger) LoggerFactory.getLogger(TaskQueue.class);
    savedLevel = queueLogger.getLevel();
    queueLogger.setLevel(Level.DEBUG);
    // Detach pre-existing appenders? Keep them, just add ours.
    appender = new ListAppender<>();
    appender.setContext(queueLogger.getLoggerContext());
    appender.start();
    queueLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    queueLogger.detachAppender(appender);
    appender.stop();
    queueLogger.setLevel(savedLevel);
  }

  private List<ILoggingEvent> rejectionEvents() {
    List<ILoggingEvent> out = new ArrayList<>();
    for (ILoggingEvent e : appender.list) {
      String msg = String.valueOf(e.getFormattedMessage());
      if (msg.contains("Rejected") || msg.contains("Rejection callback")) {
        out.add(e);
      }
    }
    return out;
  }

  @Test
  @DisplayName("large backlog rejection must not log ERROR and must preserve survivors")
  void largeBacklogRejectionMustPreserveSurvivors() throws Exception {
    // Given a runner parked on a blocking head with a large backlog behind a saturated handoff
    TaskQueue queue = new TaskQueue();
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    Executor blocking =
        command -> {
          runnerStarted.countDown();
          try {
            releaseRunner.await();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
          }
          command.run();
        };
    Thread runner = new Thread(() -> queue.execute(blocking, () -> {}));
    runner.setDaemon(true);
    runner.start();
    if (!runnerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
      throw new IllegalStateException("runner did not start");
    }

    int survivors = 200;
    AtomicInteger completed = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(survivors);
    Executor worker = command -> new Thread(command).start();
    Executor saturated =
        command -> {
          throw new RejectedExecutionException("saturated");
        };
    queue.execute(saturated, () -> {});
    for (int i = 0; i < survivors; i++) {
      queue.execute(
          worker,
          () -> {
            completed.incrementAndGet();
            done.countDown();
          });
    }
    appender.list.clear();

    // When the head releases and the drain reaches the saturated handoff
    releaseRunner.countDown();
    awaitCountDown(done);

    // Then only the saturated head is rejected while every survivor still drains
    assertThat(completed.get()).isEqualTo(survivors);
    assertThat(queue.isIdle()).isTrue();

    // And rejection summaries never log at ERROR
    List<ILoggingEvent> events = rejectionEvents();
    assertThat(events).isNotEmpty();
    for (ILoggingEvent e : events) {
      assertThat(e.getLevel().toInt())
          .as("rejection path must not log at ERROR: %s", e.getFormattedMessage())
          .isLessThan(Level.ERROR.toInt());
    }
  }

  @Test
  @DisplayName("rejection callback failure must not starve survivors")
  void rejectionCallbackFailureMustNotStarveSurvivors() throws Exception {
    // Given a parked runner with a hostile head (callback throws) followed by survivors
    TaskQueue queue = new TaskQueue();
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    Executor blocking =
        command -> {
          runnerStarted.countDown();
          try {
            releaseRunner.await();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
          }
          command.run();
        };
    Thread runner = new Thread(() -> queue.execute(blocking, () -> {}));
    runner.setDaemon(true);
    runner.start();
    if (!runnerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
      throw new IllegalStateException("runner did not start");
    }

    Executor saturated =
        command -> {
          throw new RejectedExecutionException("saturated");
        };
    queue.execute(saturated, new ThrowingAware());
    int survivors = 10;
    CountDownLatch done = new CountDownLatch(survivors);
    Executor worker = command -> new Thread(command).start();
    for (int i = 0; i < survivors; i++) {
      queue.execute(worker, done::countDown);
    }
    appender.list.clear();

    // When the drain rejects the hostile head Then survivors still drain
    releaseRunner.countDown();
    awaitCountDown(done);
    assertThat(queue.isIdle()).isTrue();

    // And the callback failure is contained to a WARN, never an ERROR
    List<ILoggingEvent> events = rejectionEvents();
    assertThat(events).isNotEmpty();
    for (ILoggingEvent e : events) {
      assertThat(e.getLevel().toInt())
          .as("callback failure must not log at ERROR: %s", e.getFormattedMessage())
          .isLessThan(Level.ERROR.toInt());
    }
  }

  /** Rejection-aware task whose callback always throws (simulates user bug under saturation). */
  static final class ThrowingAware implements Runnable, RejectionAware {
    @Override
    public void run() {}

    @Override
    public void rejected(Throwable cause) {
      throw new RuntimeException("callback boom");
    }
  }
}
