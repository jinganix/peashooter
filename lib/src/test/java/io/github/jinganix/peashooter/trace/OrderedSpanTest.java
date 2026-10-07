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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import io.github.jinganix.peashooter.TraceIdGenerator;
import io.github.jinganix.peashooter.internal.KeySanitizer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

@DisplayName("OrderedSpan")
class OrderedSpanTest {

  static TraceIdGenerator fixedGenerator() {
    DefaultTracer tracer = new DefaultTracer();
    return new TraceIdGenerator() {
      @Override
      public String nextTraceId() {
        return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
      }

      @Override
      public String nextSpanId() {
        return tracer.nextSpanId();
      }
    };
  }

  @Test
  @DisplayName("should keep an explicit trace id when provided")
  void shouldKeepAnExplicitTraceIdWhenProvided() {
    // When
    OrderedSpan span = OrderedSpan.child(fixedGenerator(), null, "key", true);

    // Then
    assertThat(span.getTraceId()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  }

  @Test
  @DisplayName("should force an explicit trace id even when parent is set")
  void shouldForceAnExplicitTraceIdEvenWhenParentIsSet() {
    // Given
    DefaultTracer tracer = new DefaultTracer();
    Span parent = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);

    // When
    OrderedSpan span =
        OrderedSpan.continueTrace("cccccccccccccccccccccccccccccccc", tracer, parent, "key", true);

    // Then
    assertThat(span.getTraceId()).isEqualTo("cccccccccccccccccccccccccccccccc");
    assertThat(span.getParent()).isEqualTo(parent);
    assertThat(span.getSpanId()).matches("[0-9a-f]{16}");
    assertThat(OrderedSpan.invokedBy(span, "key")).isTrue();
  }

  @Test
  @DisplayName("should reject invalid ids in ordered factories")
  void shouldRejectInvalidIdsInOrderedFactories() {
    DefaultTracer tracer = new DefaultTracer();
    Span parent = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);

    assertThatThrownBy(() -> OrderedSpan.continueTrace("trace", tracer, parent, "key", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("traceId");
    assertThatThrownBy(() -> OrderedSpan.continueTrace(null, tracer, parent, "key", true))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should validate each generated id exactly once on the hot span path")
  void shouldValidateEachGeneratedIdExactlyOnceOnTheHotSpanPath() {
    // Given W3C id validation is observable at the TraceIds boundary
    TraceIdGenerator generator =
        new TraceIdGenerator() {
          @Override
          public String nextTraceId() {
            return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
          }

          @Override
          public String nextSpanId() {
            return "bbbbbbbbbbbbbbbb";
          }
        };
    AtomicInteger traceIdValidations = new AtomicInteger();
    AtomicInteger spanIdValidations = new AtomicInteger();
    try (MockedStatic<TraceIds> mocked = mockStatic(TraceIds.class)) {
      mocked
          .when(() -> TraceIds.isValidTraceId(anyString()))
          .thenAnswer(
              invocation -> {
                traceIdValidations.incrementAndGet();
                return true;
              });
      mocked
          .when(() -> TraceIds.isValidSpanId(anyString()))
          .thenAnswer(
              invocation -> {
                spanIdValidations.incrementAndGet();
                return true;
              });

      // When one ordered span is created on the hot path
      OrderedSpan.childForValidatedKey(generator, null, "key", true);

      // Then the Span constructor is the single owner of id validation: factories must not
      // re-validate the same ids (redundant scans on every task's span creation).
      assertThat(traceIdValidations).hasValue(1);
      assertThat(spanIdValidations).hasValue(1);
    }
  }

  @Test
  @DisplayName("should return false when span is null")
  void shouldReturnFalseWhenSpanIsNull() {
    // When / Then
    assertThat(OrderedSpan.invokedBy(null, "key")).isFalse();
  }

  @Test
  @DisplayName("should expose key and sync flag")
  void shouldExposeKeyAndSyncFlag() {
    // Given
    OrderedSpan sync = OrderedSpan.child(fixedGenerator(), null, "my-key", true);
    OrderedSpan async = OrderedSpan.child(fixedGenerator(), null, "my-key", false);

    // When / Then
    assertThat(sync.getKey()).isEqualTo("my-key");
    assertThat(sync.isSync()).isTrue();
    assertThat(async.isSync()).isFalse();
  }

  @Test
  @DisplayName("should render key and sync flag in toString")
  void shouldRenderKeyAndSyncFlagInToString() {
    // Given
    OrderedSpan span = OrderedSpan.child(fixedGenerator(), null, "my-key", true);

    // When / Then the rendering carries ids, key, and sync for log correlation
    assertThat(span.toString())
        .contains("traceId=")
        .contains("spanId=")
        .contains("key=my-key")
        .contains("sync=true");
  }

  @Test
  @DisplayName("should sanitize control characters in toString")
  void shouldSanitizeControlCharactersInToString() {
    // Given a key embedding newline and ESC (ANSI escape) for log forging
    String key = "a\nb\u001Bc";
    OrderedSpan span = OrderedSpan.child(fixedGenerator(), null, key, true);

    // When rendering for logs
    String rendered = span.toString();

    // Then raw controls must not survive; rendering uses sanitize like error paths
    assertThat(rendered).doesNotContain("\n", "\u001B");
    assertThat(rendered).contains("key=" + KeySanitizer.sanitize(key));
  }

  @Test
  @DisplayName("should use identity equality even across keys")
  void shouldUseIdentityEqualityEvenAcrossKeys() {
    // Given a generator reusing one id pair: id reuse across submissions is forbidden by
    // the single-use guard; the shared ids below only exercise that spans stay distinct
    // while their SpanIdKey lookups coincide
    TraceIdGenerator reusedIds =
        new TraceIdGenerator() {
          @Override
          public String nextTraceId() {
            return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
          }

          @Override
          public String nextSpanId() {
            return "bbbbbbbbbbbbbbbb";
          }
        };
    OrderedSpan keyedSync = OrderedSpan.child(reusedIds, null, "key-a", true);
    OrderedSpan keyedAsync = OrderedSpan.child(reusedIds, null, "key-b", false);
    Span plain = Span.ofIds("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", null);
    TraceIdGenerator otherIds =
        new TraceIdGenerator() {
          @Override
          public String nextTraceId() {
            return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
          }

          @Override
          public String nextSpanId() {
            return "cccccccccccccccc";
          }
        };
    OrderedSpan different = OrderedSpan.child(otherIds, null, "key-a", true);

    // When / Then spans use identity equality: distinct instances are never equal,
    // while SpanIdKey carries the by-ids lookup
    assertThat(keyedSync).isNotEqualTo(keyedAsync);
    assertThat(keyedSync).isNotEqualTo(plain);
    assertThat(plain).isNotEqualTo(keyedSync);
    java.util.Map<SpanIdKey, String> map = new java.util.HashMap<>();
    map.put(SpanIdKey.of(keyedSync), "v");
    assertThat(map.get(SpanIdKey.of(keyedAsync))).isEqualTo("v");
    assertThat(map.get(SpanIdKey.of(plain))).isEqualTo("v");
    assertThat(keyedSync).isNotEqualTo(different);
    assertThat(SpanIdKey.of(keyedSync)).isNotEqualTo(SpanIdKey.of(different));
  }

  @Test
  @DisplayName("should reject null and blank keys on factories")
  void shouldRejectNullAndBlankKeysOnFactories() {
    // Given
    DefaultTracer tracer = new DefaultTracer();

    // When / Then both factories fail fast like the constructors did
    assertThatThrownBy(() -> OrderedSpan.child(tracer, null, "", true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderedSpan.child(tracer, null, null, true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                OrderedSpan.continueTrace(
                    "cccccccccccccccccccccccccccccccc", tracer, null, " ", true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> invokedByScenarios() {
    return Stream.of(
        Arguments.of(
            "should treat as reentrant when only sync span matches key",
            List.of(SpanArg.sync("foo")),
            SpanArg.sync("foo")),
        Arguments.of(
            "should treat the async span on the same key as reentrant",
            List.of(SpanArg.async("foo")),
            SpanArg.sync("foo")),
        Arguments.of(
            "should treat as sync when async then sync chain ends on key",
            List.of(SpanArg.async("foo"), SpanArg.sync("foo")),
            SpanArg.sync("foo")),
        Arguments.of(
            "should treat as async when async spans do not end on key",
            List.of(SpanArg.async("foo"), SpanArg.async("bar")),
            SpanArg.async("foo")),
        Arguments.of(
            "should treat as async when sync parent has async child for key",
            List.of(SpanArg.sync("foo"), SpanArg.async("bar")),
            SpanArg.async("foo")),
        Arguments.of(
            "should treat as sync when async then sync on different keys",
            List.of(SpanArg.async("foo"), SpanArg.sync("bar")),
            SpanArg.sync("foo")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invokedByScenarios")
  @DisplayName("should classify invocation as sync or async from span chain")
  void shouldClassifyInvocationAsSyncOrAsyncFromSpanChain(
      String scenario, List<SpanArg> args, SpanArg expected) {
    // Given
    OrderedSpan span = null;
    DefaultTracer tracer = new DefaultTracer();
    for (SpanArg arg : args) {
      span = OrderedSpan.child(tracer, span, arg.key, arg.sync);
    }

    // When / Then
    assertThat(OrderedSpan.invokedBy(span, expected.key)).isEqualTo(expected.sync);
  }

  static class SpanArg {
    String key;
    boolean sync;

    SpanArg(String key, boolean sync) {
      this.key = key;
      this.sync = sync;
    }

    static SpanArg sync(String key) {
      return new SpanArg(key, true);
    }

    static SpanArg async(String key) {
      return new SpanArg(key, false);
    }
  }
}
