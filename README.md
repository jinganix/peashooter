[![CI](https://github.com/jinganix/peashooter/actions/workflows/ci.yml/badge.svg)](https://github.com/jinganix/peashooter/actions/workflows/ci.yml)
[![Coverage](https://codecov.io/gh/jinganix/peashooter/graph/badge.svg?branch=master)](https://codecov.io/gh/jinganix/peashooter)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

[中文版本](README.zh.md)

# peashooter

Per-key ordered task execution for Java thread pools — sequential guarantees without global locking, plus safe nested synchronous calls that avoid deadlocks.

## Features

- **Ordered execution per key** — tasks sharing the same key run one after another, in submission order.
- **Concurrent across keys** — different keys use the shared thread pool in parallel.
- **Deadlock-safe nesting** — `supply` / `executeSync` detect re-entrant calls on the same key and run inline instead of blocking (fail fast when same-key peers are already queued).
- **Distributed tracing** — built-in trace IDs and parent/child spans for ordered call chains.
- **Low overhead** — lock-free hot path during task execution; per-key queues use Caffeine with access-based expiry.

## Requirements

- Java 21+

## Installation

### Maven

```xml
<dependency>
  <groupId>io.github.jinganix.peashooter</groupId>
  <artifactId>peashooter</artifactId>
  <version>0.0.10</version>
</dependency>
```

### Gradle (Groovy)

```groovy
implementation 'io.github.jinganix.peashooter:peashooter:0.0.10'
```

### Gradle (Kotlin)

```kotlin
implementation("io.github.jinganix.peashooter:peashooter:0.0.10")
```

## Quick start

```java
ExecutorService pool = Executors.newFixedThreadPool(8);
OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);

// Same-key tasks run serially, but use a synchronized container: callers mixing
// keys or reading from another thread still need a thread-safe collection.
List<String> values = Collections.synchronizedList(new ArrayList<>());
executor.executeAsync("user-1", () -> values.add("1"));
executor.supply("user-1", () -> { values.add("2"); return null; });
executor.executeAsync("user-1", () -> values.add("3"));
executor.executeSync("user-1", () -> values.add("4"));

// Submission order is preserved: [1, 2, 3, 4]
System.out.println("Values: [" + String.join(", ", values) + "]");
```

## How it works

### Per-key ordering

`OrderedTraceExecutor` maintains one [`TaskQueue`](lib/src/main/java/io/github/jinganix/peashooter/queue/TaskQueue.java) per key. Submitters take a short lock to enqueue work; the worker runs tasks for that key strictly in FIFO order.

When three tasks are submitted sequentially for the same key:

```java
executor.executeAsync("foo", task1);  // L1
executor.executeAsync("foo", task2);  // L2
executor.executeAsync("foo", task3);  // L3
```

Execution for key `"foo"` follows enqueue order — **task1 → task2 → task3**. Later tasks never start until the previous one finishes. Concurrent submissions from different threads still run in the order they reach the queue, not in source-code order.

Different keys are independent — `"foo"` and `"bar"` can run at the same time on the pool.

### Deadlock-free synchronous calls

Nested synchronous calls on different keys are scheduled through the same mechanism. The inner `supply` runs on the correct queue without holding locks across keys:

```java
int value =
    executor.supply(
        "foo",
        () ->
            executor.supply(
                "bar", () -> executor.supply("foo", () -> 1)));
// value == 1
```

Re-entrant synchronous calls on the **same** key are detected via the current trace span and execute inline when no peer tasks are queued for that key, so they cannot deadlock waiting on themselves. If same-key peers are already queued, such a nested call fails fast with `IllegalStateException` instead of overtaking them (submission order is never silently broken): restructure the call so the nested same-key work runs before peers are waiting. Multi-key overloads (`executeSync(keys, …)`, `supply(keys, …)`) that de-duplicate to two or more distinct keys always enqueue each level, because their global sorted acquisition order must hold; a collection that de-duplicates to one key takes the single-key path above.

## API

| Method | Description |
|--------|-------------|
| `executeAsync(key, task)` | Enqueue `task` for `key`; returns immediately. |
| `executeSync(key, task)` | Run `task` on the queue for `key` and block until it completes (default timeout 10 seconds). |
| `executeSync(keys, task)` | Sort `keys` in natural `String` order, de-duplicate, then acquire outermost-first and run `task`. |
| `supply(key, supplier)` | Like `executeSync`, but returns the supplier result. |
| `supply(keys, supplier)` | Multi-key variant of `supply` with the same sorted acquisition. |
| `setTimeout(Duration)` / `getTimeout()` | Configure / read the timeout for synchronous waits. |
| `getTracer()` | Access the tracer for custom span integration. |

Advanced wiring is available via constructors that accept a custom [`TaskQueueProvider`](lib/src/main/java/io/github/jinganix/peashooter/TaskQueueProvider.java), [`ExecutorSelector`](lib/src/main/java/io/github/jinganix/peashooter/ExecutorSelector.java), and [`Tracer`](lib/src/main/java/io/github/jinganix/peashooter/Tracer.java).

## Benchmarks

> ⚠️ The tables below are single-run illustrations (n=1) on Apple M1 Pro (10 cores, 16 GB RAM,
> JDK 21 G1; expect ±10–20% run-to-run variance under load). Do not quote them as guarantees —
> please re-run locally for your hardware with:

```bash
./gradlew :lib:test --tests "*Benchmark*"
```

### TaskQueue ([source](lib/src/test/java/io/github/jinganix/peashooter/queue/TaskQueueBenchmarkTest.java))

5,000,000 counter increments, single-threaded queue drain (1 warm-up + 3 timed runs, reporting
P95; see test). Single-run illustration only (n=1, M1 Pro) — please re-run locally:

| Implementation | Time (n=1, M1 Pro, illustrative — re-run locally) |
|----------------|---------------------------------------------------|
| `TaskQueue` | ~620 ms |
| `synchronized` | ~2336 ms |
| `ReentrantLock` | ~2412 ms |

The benchmark reports P95 over 3 timed runs against both baselines and logs a `WARN` when P95
reaches `>= 2x` of either one. Throughput is a triage signal, not a hard wall-clock gate: a strict
gate flakes on loaded CI hardware, so re-run locally and treat a sustained ~2x slowdown as a
regression to investigate.

### Redis lockable queue ([source](lib/src/test/java/io/github/jinganix/peashooter/redisson/RedisLockableQueueBenchmarkTest.java))

500 tasks (requires Redis; see test setup, n=1, M1 Pro):

| Implementation | Time (n=1) |
|----------------|------------|
| `RedisLockableTaskQueue` | ~84 ms |
| Per-task Redis lock | ~1268 ms |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to report issues, run tests, and submit changes.

```bash
./gradlew build
```
