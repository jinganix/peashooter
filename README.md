[![CI](https://github.com/jinganix/peashooter/actions/workflows/ci.yml/badge.svg)](https://github.com/jinganix/peashooter/actions/workflows/ci.yml)
[![Coverage](https://codecov.io/gh/jinganix/peashooter/graph/badge.svg?branch=master)](https://codecov.io/gh/jinganix/peashooter)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

[中文版本](README.zh.md)

# peashooter

Per-key ordered task execution for Java thread pools — sequential guarantees without global locking, plus safe nested synchronous calls that avoid deadlocks.

## Features

- **Ordered execution per key** — tasks sharing the same key run one after another, in submission order.
- **Concurrent across keys** — different keys use the shared thread pool in parallel.
- **Deadlock-safe nesting** — `supply` / `executeSync` detect re-entrant calls on the same key and run inline instead of blocking.
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
  <version>0.0.8</version>
</dependency>
```

### Gradle (Groovy)

```groovy
implementation 'io.github.jinganix.peashooter:peashooter:0.0.8'
```

### Gradle (Kotlin)

```kotlin
implementation("io.github.jinganix.peashooter:peashooter:0.0.8")
```

## Quick start

```java
ExecutorService pool = Executors.newFixedThreadPool(8);
OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);

List<String> values = new ArrayList<>();
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

Suppose three tasks are submitted from different threads in this **code order**:

```java
executor.executeAsync("foo", task1);  // L1
executor.executeAsync("foo", task2);  // L2
executor.executeAsync("foo", task3);  // L3
```

Regardless of which thread wins the race to enqueue, execution for key `"foo"` is always **task1 → task2 → task3**. Later tasks never start until the previous one finishes.

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

Re-entrant synchronous calls on the **same** key are detected via the current trace span and execute inline, so they cannot deadlock waiting on themselves.

## API

| Method | Description |
|--------|-------------|
| `executeAsync(key, task)` | Enqueue `task` for `key`; returns immediately. |
| `executeSync(key, task)` | Run `task` on the queue for `key` and block until it completes (default timeout 10 seconds). |
| `executeSync(keys, task)` | Acquire multiple keys in iteration order, then run `task`. |
| `supply(key, supplier)` | Like `executeSync`, but returns the supplier result. |
| `supply(keys, supplier)` | Multi-key variant of `supply`. |
| `setTimeout(timeout, unit)` | Configure timeout for synchronous waits. |
| `getTracer()` | Access the tracer for custom span integration. |

Advanced wiring is available via constructors that accept a custom [`TaskQueueProvider`](lib/src/main/java/io/github/jinganix/peashooter/TaskQueueProvider.java), [`ExecutorSelector`](lib/src/main/java/io/github/jinganix/peashooter/ExecutorSelector.java), and [`Tracer`](lib/src/main/java/io/github/jinganix/peashooter/Tracer.java).

## Benchmarks

Measured on Apple M1 Pro (10 cores, 16 GB RAM). Run locally with:

```bash
./gradlew :lib:test --tests "*Benchmark*"
```

### TaskQueue ([source](lib/src/test/java/io/github/jinganix/peashooter/queue/TaskQueueBenchmarkTest.java))

5,000,000 counter increments, single-threaded queue drain:

| Implementation | Time |
|----------------|------|
| `TaskQueue` | 620 ms |
| `synchronized` | 2336 ms |
| `ReentrantLock` | 2412 ms |

### Redis lockable queue ([source](lib/src/test/java/io/github/jinganix/peashooter/redisson/RedisLockableQueueBenchmarkTest.java))

500 tasks (requires Redis; see test setup):

| Implementation | Time |
|----------------|------|
| `RedisLockableTaskQueue` | 84 ms |
| Per-task Redis lock | 1268 ms |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to report issues, run tests, and submit changes.

```bash
./gradlew build
```
