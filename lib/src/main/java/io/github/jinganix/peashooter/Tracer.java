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

/**
 * Tracer to trace task call chain.
 *
 * <p>Convenience combination of the three narrow facets ({@link SpanAccessor}, {@link
 * TraceIdGenerator}, {@link TraceCallback}) for call sites that genuinely need all three (span
 * creation plus scoped execution). New code that needs only one facet must inject that narrow
 * interface instead: storage-only bridges take {@link SpanAccessor}, id-only factories take {@link
 * TraceIdGenerator} (see {@link io.github.jinganix.peashooter.trace.Span#child}), callback-only
 * observers take {@link TraceCallback}, and composition stays explicit via {@link
 * io.github.jinganix.peashooter.trace.DelegatingTracer}.
 *
 * <p>TraceScope owns the single save/restore around callbacks; callbacks only observe.
 */
public interface Tracer extends SpanAccessor, TraceIdGenerator, TraceCallback {}
