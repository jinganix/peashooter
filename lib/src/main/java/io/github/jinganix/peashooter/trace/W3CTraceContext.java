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

import io.github.jinganix.peashooter.internal.KeySanitizer;
import java.util.Objects;

/**
 * W3C Trace Context ({@code traceparent}) parsing and injection.
 *
 * @see <a href="https://www.w3.org/TR/trace-context/">Trace Context</a>
 */
public final class W3CTraceContext {

  private static final String VERSION = "00";

  /**
   * Maximum {@code traceparent} header length accepted by {@link #parse}. A valid {@code 00} value
   * is 55 chars; future versions may append dash-delimited fields, but an attacker-sized header
   * must fail fast before O(N) scanning and copying on the reject path. Per W3C the whole header
   * stays small; 512 matches the {@code tracestate} bound below.
   */
  static final int MAX_TRACEPARENT_CHARS = 512;

  /**
   * Maximum {@code tracestate} header length (W3C Trace Context §3.3: combined header SHOULD be
   * &lt;= 512 chars). Longer values are rejected without parsing.
   */
  static final int MAX_TRACESTATE_CHARS = 512;

  private W3CTraceContext() {}

  /**
   * Parsed {@code traceparent} fields.
   *
   * @param traceId 128-bit trace id (32 hex)
   * @param parentSpanId caller span id from the header (16 hex)
   * @param sampled whether the sampled flag is set
   * @param version traceparent version (2 lower-hex chars, never {@code ff}): carried here because
   *     {@link Span} has no version field, and {@link #inject(Span, boolean)} always re-emits
   *     {@code 00} — without this, a future version in would silently become {@code 00} out with no
   *     record of the downgrade
   */
  public record Context(String traceId, String parentSpanId, boolean sampled, String version) {
    /** Validates the parsed traceparent fields. */
    public Context {
      Objects.requireNonNull(traceId, "traceId");
      Objects.requireNonNull(parentSpanId, "parentSpanId");
      Objects.requireNonNull(version, "version");
      if (!TraceIds.isValidTraceId(traceId)) {
        throw new IllegalArgumentException(
            "invalid traceId in Context: " + KeySanitizer.sanitize(traceId));
      }
      if (!TraceIds.isValidSpanId(parentSpanId)) {
        throw new IllegalArgumentException(
            "invalid parentSpanId in Context: " + KeySanitizer.sanitize(parentSpanId));
      }
      if (version.length() != 2 || !TraceIds.isLowerHex(version) || "ff".equals(version)) {
        throw new IllegalArgumentException(
            "invalid version in Context: " + KeySanitizer.sanitize(version));
      }
    }
  }

  /**
   * Parses a {@code traceparent} header value strictly per the W3C Trace Context spec.
   *
   * <p>No surrounding whitespace is tolerated and uppercase hex is rejected instead of normalized:
   * non-compliant senders must normalize before calling. Future-version forward compatibility still
   * applies (extra trailing fields ignored), but {@code trace-flags} must be exactly 2 characters.
   *
   * @param traceparent header value
   * @return parsed context
   * @throws IllegalArgumentException if the value is invalid
   */
  public static Context parse(String traceparent) {
    Objects.requireNonNull(traceparent, "traceparent");
    if (traceparent.length() > MAX_TRACEPARENT_CHARS) {
      throw new IllegalArgumentException(
          "traceparent too long: len="
              + traceparent.length()
              + " > "
              + MAX_TRACEPARENT_CHARS
              + ": "
              + snippet(traceparent));
    }
    // Index-based parse: field shapes are validated in place via charAt so the happy path
    // allocates only the four strings stored in the Context; intermediate substrings exist
    // solely on the error path (see snippetRange).
    int start = 0;
    int end = traceparent.length();
    int first = indexOfDash(traceparent, start, end);
    int second = first < 0 ? -1 : indexOfDash(traceparent, first + 1, end);
    int third = second < 0 ? -1 : indexOfDash(traceparent, second + 1, end);
    if (first < 0 || second < 0 || third < 0) {
      throw new IllegalArgumentException(
          "traceparent must have at least 4 '-'-delimited fields: " + snippet(traceparent));
    }
    int fourth = indexOfDash(traceparent, third + 1, end);
    int flagsEnd = fourth < 0 ? end : fourth;
    // Version 00 must have exactly 4 fields; future versions may append extras (ignored here).
    if (fourth >= 0 && isVersion00(traceparent, start, first)) {
      throw new IllegalArgumentException(
          "traceparent version 00 must have 4 '-'-delimited fields: " + snippet(traceparent));
    }
    if (!isFixedLowerHexRange(traceparent, start, first, 2)) {
      throw new IllegalArgumentException(
          "invalid version in traceparent: " + snippetRange(traceparent, start, first));
    }
    if (isFfVersion(traceparent, start, first)) {
      throw new IllegalArgumentException(
          "invalid traceparent version: " + snippetRange(traceparent, start, first));
    }
    if (!isValidTraceIdRange(traceparent, first + 1, second)) {
      throw new IllegalArgumentException(
          "invalid trace-id in traceparent: " + snippetRange(traceparent, first + 1, second));
    }
    if (!isValidSpanIdRange(traceparent, second + 1, third)) {
      throw new IllegalArgumentException(
          "invalid parent span id in traceparent: " + snippetRange(traceparent, second + 1, third));
    }
    // W3C keeps trace-flags at exactly 2 lower-hex chars for every version. A future version
    // may append extra fields, but those are '-' delimited and already excluded from the range;
    // an undelimited longer run (e.g. "0100") is a malformed field, not an extension.
    if (!isFixedLowerHexRange(traceparent, third + 1, flagsEnd, 2)) {
      throw new IllegalArgumentException(
          "invalid trace-flags in traceparent: " + snippetRange(traceparent, third + 1, flagsEnd));
    }
    // Flags are validated 2-char lower-hex above: the sampled bit is the LSB of the
    // low nibble. Read it directly instead of Integer.parseInt to avoid radix parsing
    // overhead and its NumberFormatException path on this ingress path.
    char low = traceparent.charAt(flagsEnd - 1);
    int nibble = low <= '9' ? low - '0' : low - 'a' + 10;
    boolean sampled = (nibble & 0x01) == 0x01;
    // Sole happy-path copies: the strings retained by the Context.
    String version = traceparent.substring(start, first);
    String traceId = traceparent.substring(first + 1, second);
    String parentSpanId = traceparent.substring(second + 1, third);
    return new Context(traceId, parentSpanId, sampled, version);
  }

  private static int indexOfDash(String value, int from, int end) {
    for (int i = from; i < end; i++) {
      if (value.charAt(i) == '-') {
        return i;
      }
    }
    return -1;
  }

  private static boolean isLowerHexChar(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
  }

  private static boolean isLowerHexRange(String value, int start, int end) {
    if (end - start <= 0) {
      return false;
    }
    for (int i = start; i < end; i++) {
      if (!isLowerHexChar(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isVersion00(String value, int start, int end) {
    return end - start == 2 && value.charAt(start) == '0' && value.charAt(start + 1) == '0';
  }

  private static boolean isFfVersion(String value, int start, int end) {
    return end - start == 2 && value.charAt(start) == 'f' && value.charAt(start + 1) == 'f';
  }

  /**
   * Single owner of the fixed-length lower-hex field shape shared by the traceparent version and
   * trace-flags fields (both exactly two lower-hex chars).
   */
  private static boolean isFixedLowerHexRange(String value, int start, int end, int length) {
    return end - start == length && isLowerHexRange(value, start, end);
  }

  private static boolean isValidTraceIdRange(String value, int start, int end) {
    if (end - start != 32) {
      return false;
    }
    return isNonZeroLowerHexRange(value, start, end);
  }

  private static boolean isValidSpanIdRange(String value, int start, int end) {
    if (end - start != 16) {
      return false;
    }
    return isNonZeroLowerHexRange(value, start, end);
  }

  private static boolean isNonZeroLowerHexRange(String value, int start, int end) {
    boolean nonZero = false;
    for (int i = start; i < end; i++) {
      char c = value.charAt(i);
      boolean digit = c >= '0' && c <= '9';
      boolean lower = c >= 'a' && c <= 'f';
      if (!digit && !lower) {
        return false;
      }
      if (c != '0') {
        nonZero = true;
      }
    }
    return nonZero;
  }

  /**
   * Sanitized display fragment of an offending value for rejection messages.
   *
   * <p>Truncates before sanitizing so an attacker-controlled header pays O(cap) rather than O(N):
   * sanitizing the full string first would copy megabytes to emit 128 chars. The full length is
   * noted so truncated values stay distinguishable.
   */
  private static String snippet(String value) {
    String raw = String.valueOf(value);
    boolean truncated = raw.length() > 128;
    String head = truncated ? raw.substring(0, 128) : raw;
    String sanitized = KeySanitizer.sanitize(head);
    if (!truncated) {
      return sanitized.isEmpty() ? "<empty>" : sanitized;
    }
    return sanitized + "...(len=" + raw.length() + ")";
  }

  /**
   * Sanitized fragment of {@code value[start, end)} without copying the whole field: at most 128
   * chars are copied for the message. Error path only; the happy path never calls this.
   */
  private static String snippetRange(String value, int start, int end) {
    int from = Math.max(0, start);
    int to = Math.min(value.length(), Math.max(from, end));
    int length = to - from;
    boolean truncated = length > 128;
    int headEnd = truncated ? from + 128 : to;
    String head = value.substring(from, headEnd);
    String sanitized = KeySanitizer.sanitize(head);
    if (!truncated) {
      return sanitized.isEmpty() ? "<empty>" : sanitized;
    }
    return sanitized + "...(len=" + length + ")";
  }

  /**
   * Builds a {@link Span} representing the remote parent described by {@code traceparent}.
   *
   * <p>Use as the parent when creating a local child, e.g. {@code Span.child(tracer,
   * extractParent(h))}. The sampled flag is not carried on {@link Span}: parse the {@link Context}
   * and pass {@code context.sampled()} to {@link #inject(Span, boolean)} to preserve it round-trip.
   *
   * @param traceparent header value
   * @return remote parent span (root of the local chain, carrying upstream ids)
   */
  public static Span extractParent(String traceparent) {
    Context context = parse(traceparent);
    return Span.ofIds(context.traceId(), context.parentSpanId(), null);
  }

  /**
   * Outbound propagation headers: the downgraded {@code traceparent} plus the untouched inbound
   * {@code tracestate} (when present) to forward alongside it.
   *
   * @param traceparent downgraded {@code traceparent} value (version {@code 00})
   * @param tracestate opaque inbound {@code tracestate} to forward as-is, or {@code null} when
   *     absent
   */
  public record Headers(String traceparent, String tracestate) {}

  /**
   * Formats a {@code traceparent} value for outbound propagation from {@code span}, unsampled.
   *
   * <p>Equivalent to {@link #inject(Span, boolean) inject(span, false)}; see there for the
   * version-downgrade and id-validation contract.
   *
   * @param span current span (its span id becomes the parent id in the header)
   * @return header value with {@code 00} flags
   */
  public static String inject(Span span) {
    return inject(span, false);
  }

  /**
   * Formats a {@code traceparent} value for outbound propagation from {@code span}.
   *
   * <p><b>Always downgrades to version {@code 00} and preserves only the {@code sampled} flag.</b>
   * A {@link Context#version()} other than {@code 00} does not survive the round trip, and any
   * non-sampled {@code trace-flags} bits are dropped. Only the W3C {@code sampled} flag is modeled:
   * {@link Span} carries no version field, so a future version cannot be rebuilt from a span
   * without losing the parent link, and raw forwarding of the inbound header would propagate a
   * stale parent id instead of the current span.
   *
   * <p>Both ids must be W3C-valid (32/16 lowercase hex, not all zeros): strict {@link
   * #parse(String)} accepts only lowercase, and the {@link TraceIds#isValidTraceId} / {@link
   * TraceIds#isValidSpanId} checks here require the same.
   *
   * <p>When the inbound {@link Context} is still available, prefer {@link #inject(Context, Span)}
   * instead: it rejects non-{@code 00} versions rather than silently downgrading them.
   *
   * @param span current span (its span id becomes the parent id in the header)
   * @param sampled whether to set the sampled flag
   * @return header value
   */
  public static String inject(Span span, boolean sampled) {
    Objects.requireNonNull(span, "span");
    if (!TraceIds.isValidTraceId(span.getTraceId())) {
      throw new IllegalArgumentException("span trace id is not W3C-compatible");
    }
    if (!TraceIds.isValidSpanId(span.getSpanId())) {
      throw new IllegalArgumentException("span id is not W3C-compatible");
    }
    // Pre-sized: 2 + 1 + 32 + 1 + 16 + 1 + 2 = 55 chars, no builder growth.
    return new StringBuilder(55)
        .append(VERSION)
        .append('-')
        .append(span.getTraceId())
        .append('-')
        .append(span.getSpanId())
        .append('-')
        .append(sampled ? "01" : "00")
        .toString();
  }

  /**
   * Formats a {@code traceparent} value from a parsed inbound {@code context} and the current
   * {@code span}, preserving the sampled flag round-trip.
   *
   * <p>Unlike {@link #inject(Span, boolean)}, this overload never silently downgrades: a {@link
   * Context#version()} other than {@code 00} throws instead of emitting {@code 00}. The library
   * speaks only version {@code 00} and {@link Span} carries no version field, so a future version
   * cannot be rebuilt from a span; callers holding the inbound header must forward the raw header
   * when the version differs instead of re-emitting from the span.
   *
   * @param context parsed inbound context (carries the version and sampled flag)
   * @param span current span (its span id becomes the parent id in the header)
   * @return header value with version {@code 00}
   * @throws IllegalArgumentException when {@code context.version()} is not {@code 00}
   */
  public static String inject(Context context, Span span) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(span, "span");
    requireVersion00(context);
    return inject(span, context.sampled());
  }

  /**
   * Formats outbound propagation headers from a parsed inbound {@code context} and the current
   * {@code span}, carrying the inbound {@code tracestate} through untouched.
   *
   * <p>Version contract matches {@link #inject(Context, Span)}: non-{@code 00} versions throw
   * rather than silently downgrading.
   *
   * @param context parsed inbound context (carries the version and sampled flag)
   * @param span current span (its span id becomes the parent id in the header)
   * @param tracestate opaque inbound {@code tracestate} to forward as-is; {@code null} or blank
   *     means absent and yields a {@code null} tracestate in the result
   * @return headers holding the {@code traceparent} and the passthrough {@code tracestate}
   * @throws IllegalArgumentException when {@code context.version()} is not {@code 00}, or when
   *     {@code tracestate} is present but invalid
   */
  public static Headers inject(Context context, Span span, String tracestate) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(span, "span");
    requireVersion00(context);
    return inject(span, context.sampled(), tracestate);
  }

  /**
   * Single owner of the non-{@code 00} version contract shared by the {@link Context}-based {@link
   * #inject(Context, Span)} overloads.
   *
   * @param context parsed inbound context
   * @throws IllegalArgumentException when {@code context.version()} is not {@code 00}
   */
  private static void requireVersion00(Context context) {
    if (!VERSION.equals(context.version())) {
      throw new IllegalArgumentException(
          "cannot inject non-00 traceparent version "
              + KeySanitizer.sanitize(context.version())
              + ": forward the raw inbound header instead of re-emitting from the span");
    }
  }

  /**
   * Formats outbound propagation headers from {@code span}, carrying the inbound {@code tracestate}
   * through untouched.
   *
   * <p>The {@code traceparent} still downgrades to version {@code 00} with only the {@code sampled}
   * flag preserved (see {@link #inject(Span, boolean)}): this library speaks only version {@code
   * 00}, and extra flag bits have no carrier on {@link Span}. The loss is now explicit — compare
   * {@link Context#version()} against the {@code 00-} prefix — while the opaque {@code tracestate}
   * (which this library never interprets) survives the downgrade verbatim instead of being dropped.
   * Forward both headers: {@code traceparent} always, {@code tracestate} when non-null.
   *
   * @param span current span (its span id becomes the parent id in the header)
   * @param sampled whether to set the sampled flag
   * @param tracestate opaque inbound {@code tracestate} to forward as-is; {@code null} or blank
   *     means absent and yields a {@code null} tracestate in the result
   * @return headers holding the downgraded {@code traceparent} and the passthrough {@code
   *     tracestate} (same instance when present, {@code null} when absent)
   * @throws IllegalArgumentException if {@code tracestate} is present but invalid
   */
  public static Headers inject(Span span, boolean sampled, String tracestate) {
    String traceparent = inject(span, sampled);
    if (tracestate == null || tracestate.isBlank()) {
      return new Headers(traceparent, null);
    }
    if (!isValidTracestate(tracestate)) {
      throw new IllegalArgumentException("invalid tracestate: " + snippet(tracestate));
    }
    return new Headers(traceparent, tracestate);
  }

  /**
   * Whether {@code tracestate} is a valid W3C {@code tracestate} header value.
   *
   * <p>Lenient structural check for the passthrough path: 1-512 chars, comma-separated {@code
   * key=value} members with non-blank keys and values, printable ASCII only (no controls, no {@code
   * ','} / {@code '='} inside values beyond the first {@code '='}). Keys are not restricted to the
   * lowercase registry here: intermediaries must forward what they do not understand.
   *
   * <p>Charset note vs {@link #parse(String)}: {@code traceparent} fields are strict lowercase hex
   * (fixed W3C alphabet), while {@code tracestate} values are opaque vendor data, so this check
   * accepts the full printable ASCII range ({@code 0x20-0x7E} minus the structural separators)
   * instead of reusing the hex parser. Only optional whitespace ({@code ' '}, HTAB) is tolerated
   * around keys/values per the W3C OWS rule; everything else non-printable rejects.
   *
   * @param tracestate candidate header value
   * @return {@code true} if safe to forward as-is
   */
  public static boolean isValidTracestate(String tracestate) {
    if (tracestate == null || tracestate.isEmpty() || tracestate.length() > MAX_TRACESTATE_CHARS) {
      return false;
    }
    // Single pass over the original string: no substring/isBlank/strip temporaries.
    // Member = key '=' value, members separated by ',' with no empties. Only leading and
    // trailing OWS (space/HTAB) around the header and around each key/value is tolerated per
    // the W3C optional-whitespace rule; OWS inside a key or value rejects. All non-OWS chars
    // must be printable ASCII 0x20-0x7E; exactly one '=' per member; trimmed key/value must
    // be non-empty.
    int length = tracestate.length();
    int equalsPos = -1;
    int keyStart = -1;
    int valueStart = -1;
    boolean keyGap = false;
    boolean valueGap = false;
    for (int i = 0; i <= length; i++) {
      char c = i < length ? tracestate.charAt(i) : ',';
      boolean delimiter = i == length || c == ',';
      if (!delimiter) {
        // OWS first: HTAB (0x09) sits below the printable range but stays tolerated per W3C,
        // only at token edges. A gap after a started token marks trailing OWS; any later
        // token char before '=' / delimiter means the OWS was internal -> reject.
        if (c == ' ' || c == '\t') {
          if (equalsPos < 0) {
            if (keyStart >= 0) {
              keyGap = true;
            }
          } else {
            if (valueStart >= 0) {
              valueGap = true;
            }
          }
          continue;
        }
        if (c < 0x20 || c > 0x7E) {
          return false;
        }
        if (c == '=') {
          if (equalsPos >= 0 || keyStart < 0) {
            return false;
          }
          equalsPos = i;
          continue;
        }
        if (equalsPos < 0) {
          if (keyGap) {
            return false;
          }
          if (keyStart < 0) {
            keyStart = i;
          }
        } else {
          if (valueGap) {
            return false;
          }
          if (valueStart < 0) {
            valueStart = i;
          }
        }
        continue;
      }
      // End of member: trimmed key/value must both be non-empty. The starts already point past
      // any leading OWS, so a non-negative start proves the member has content.
      if (equalsPos < 0 || keyStart < 0 || valueStart < 0) {
        return false;
      }
      equalsPos = -1;
      keyStart = -1;
      valueStart = -1;
      keyGap = false;
      valueGap = false;
    }
    return true;
  }
}
