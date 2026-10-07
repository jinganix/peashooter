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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceWaitSerialization")
class TraceWaitSerializationTest {

  @Test
  @DisplayName("should reject Java serialization of timeout failures")
  void shouldRejectJavaSerializationOfTimeoutFailures() {
    TraceTimeoutException ex =
        new TraceTimeoutException(
            "slow", new TimeoutException("t"), "key", new CompletableFuture<>());

    assertThatThrownBy(
            () -> {
              try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
                out.writeObject(ex);
              }
            })
        .isInstanceOf(NotSerializableException.class)
        .hasMessageContaining("does not support Java serialization");
  }

  @Test
  @DisplayName("should reject Java serialization of interruption failures")
  void shouldRejectJavaSerializationOfInterruptionFailures() {
    TraceInterruptedException ex =
        new TraceInterruptedException(
            "interrupted", new InterruptedException("i"), "key", new CompletableFuture<>());

    assertThatThrownBy(
            () -> {
              try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
                out.writeObject(ex);
              }
            })
        .isInstanceOf(NotSerializableException.class)
        .hasMessageContaining("does not support Java serialization");
  }

  @Test
  @DisplayName("should reject Java deserialization of timeout failures")
  void shouldRejectJavaDeserializationOfTimeoutFailures() throws Exception {
    byte[] legacy =
        legacyStreamBytes("io.github.jinganix.peashooter.executor.TraceTimeoutException", 1L);

    assertThatThrownBy(
            () -> {
              try (java.io.ObjectInputStream in =
                  new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(legacy))) {
                in.readObject();
              }
            })
        .isInstanceOf(java.io.InvalidObjectException.class)
        .hasMessageContaining("does not support Java serialization");
  }

  @Test
  @DisplayName("should reject Java deserialization of interruption failures")
  void shouldRejectJavaDeserializationOfInterruptionFailures() throws Exception {
    byte[] legacy =
        legacyStreamBytes("io.github.jinganix.peashooter.executor.TraceInterruptedException", 1L);

    assertThatThrownBy(
            () -> {
              try (java.io.ObjectInputStream in =
                  new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(legacy))) {
                in.readObject();
              }
            })
        .isInstanceOf(java.io.InvalidObjectException.class)
        .hasMessageContaining("does not support Java serialization");
  }

  /**
   * Hand-builds a serialization stream for a wait failure as an older version would have written
   * it: class descriptors from the concrete type down to {@link Throwable} with no classdata (the
   * guard throws before any field is read, so values are never consumed).
   */
  private static byte[] legacyStreamBytes(String concreteClass, long concreteSuid)
      throws java.io.IOException {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
    out.writeShort(0xACED);
    out.writeShort(5);
    out.writeByte(0x73);
    writeClassDesc(
        out, concreteClass, concreteSuid, true, new String[][] {{"key", "Ljava/lang/String;"}});
    writeClassDesc(
        out,
        "io.github.jinganix.peashooter.executor.TraceWaitException",
        1L,
        false,
        new String[0][0]);
    writeClassDesc(
        out, "java.lang.RuntimeException", -7034897190745766939L, false, new String[0][0]);
    writeClassDesc(out, "java.lang.Exception", -3387516993124229948L, false, new String[0][0]);
    writeClassDesc(out, "java.lang.Throwable", -3042686055658047285L, false, new String[0][0]);
    out.writeByte(0x70);
    out.flush();
    return bytes.toByteArray();
  }

  private static void writeClassDesc(
      java.io.DataOutputStream out,
      String className,
      long suid,
      boolean hasWriteMethod,
      String[][] fields)
      throws java.io.IOException {
    out.writeByte(0x72);
    out.writeUTF(className);
    out.writeLong(suid);
    out.writeByte(0x02 | (hasWriteMethod ? 0x01 : 0));
    out.writeShort(fields.length);
    for (String[] field : fields) {
      out.writeByte('L');
      out.writeUTF(field[0]);
      out.writeByte(0x74);
      out.writeUTF(field[1]);
    }
    out.writeByte(0x78);
  }

  @Test
  @DisplayName("should never expose a null key or future on live failures")
  void shouldNeverExposeNullKeyOrFutureOnLiveFailures() {
    CompletableFuture<?> timeoutFuture = new CompletableFuture<>();
    CompletableFuture<?> interruptFuture = new CompletableFuture<>();
    TraceTimeoutException timeout =
        new TraceTimeoutException("slow", new TimeoutException("t"), "key", timeoutFuture);
    TraceInterruptedException interrupted =
        new TraceInterruptedException(
            "interrupted", new InterruptedException("i"), "key", interruptFuture);

    assertThat(timeout.getKey()).isEqualTo("key");
    assertThat(timeout.getFuture()).isSameAs(timeoutFuture);
    assertThat(interrupted.getKey()).isEqualTo("key");
    assertThat(interrupted.getFuture()).isSameAs(interruptFuture);
  }
}
