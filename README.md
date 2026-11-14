[![CI](https://github.com/jinganix/peashooter/actions/workflows/ci.yml/badge.svg)](https://github.com/jinganix/peashooter/actions/workflows/ci.yml)
[![Coverage](https://codecov.io/gh/jinganix/peashooter/graph/badge.svg?branch=master)](https://codecov.io/gh/jinganix/peashooter)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

[中文版本](README.zh.md)

# peashooter

Per-key ordered task execution for Java thread pools — sequential guarantees without global locking, plus nested synchronous calls that avoid self-deadlock (cross-key nesting must acquire keys in one global order).

## Features

- **Ordered execution per key** — tasks sharing the same key run one after another, in submission order.
- **Concurrent across keys** — different keys use the shared thread pool in parallel.
- **Deadlock-safe nesting** — `supply` / `executeSync` detect re-entrant calls on the same key and run inline instead of blocking (fail fast when same-key peers are already queued).
- **Distributed tracing** — built-in trace IDs and parent/child spans for ordered call chains.
- **Low overhead** — lock-free hot path during task execution; per-key queues use Caffeine with idle expiration.

## Requirements

- Java 21+

## Installation

### Maven

```xml
<dependency>
  <groupId>io.github.jinganix.peashooter</groupId>
  <artifactId>peashooter</artifactId>
  <version>0.0.13</version>
</dependency>
```

### Gradle (Groovy)

```groovy
implementation 'io.github.jinganix.peashooter:peashooter:0.0.13'
```

### Gradle (Kotlin)

```kotlin
implementation("io.github.jinganix.peashooter:peashooter:0.0.13")
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

### Nested synchronous calls

Nested synchronous calls on different keys are scheduled through the same mechanism. The inner `supply` runs on the correct queue, and the queue monitor is never held across the nested call — but the outer key's runner (and the thread waiting for the inner call) stay busy until it completes:

```java
int value =
    executor.supply(
        "bar",
        () ->
            executor.supply(
                "foo", () -> executor.supply("bar", () -> 1)));
// value == 1
```

Re-entrant synchronous calls on the **same** key are detected via the current trace span and execute inline when no peer tasks are queued for that key, so they cannot deadlock waiting on themselves. If same-key peers are already queued, such a nested call fails fast with `IllegalStateException` instead of overtaking them (submission order is never silently broken): restructure the call so the nested same-key work runs before peers are waiting. Multi-key overloads (`executeSync(keys, …)`, `supply(keys, …)`) that de-duplicate to two or more distinct keys always enqueue each level, because their global sorted acquisition order must hold; a collection that de-duplicates to one key takes the single-key path above.

**Cross-key nesting order matters.** A nested `executeSync` / `supply` holds the outer key and one pool thread while it waits for the inner key, so nested single-key calls acquire keys in *call* order, not sorted order. Two concurrent chains that nest in opposite orders (`a → b` and `b → a`) can deadlock each other until the sync timeout fires. The multi-key overloads are immune because they sort and de-duplicate every key they hold. Route cross-key work through the multi-key overloads, or acquire nested cross-key syncs in one global order (for example ascending) on every thread, and size the pool for at least one thread per concurrently blocked nesting level.

## API

| Method | Description |
|--------|-------------|
| `executeAsync(key, task)` | Enqueue `task` for `key`; returns without waiting with a pool-backed executor. With a synchronous executor such as `DirectExecutor` it may run the task inline and block when it claims an idle queue; if another runner is active for the key it enqueues behind it and returns. |
| `submitAsync(key, task)` / `supplyAsync(key, supplier)` | Enqueue for `key`; return a future completed after the task runs. |
| `supplyCheckedAsync(key, supplier)` / `supplyCheckedAsync(keys, type, supplier)` | Async variants propagating the declared checked failure via the future. |
| `executeSync(key, task)` | Run `task` on the queue for `key` and block until it completes (default timeout 10 seconds). |
| `executeSync(keys, task)` | Sort `keys` in natural `String` order, de-duplicate, then acquire outermost-first and run `task`. |
| `supply(key, supplier)` | Like `executeSync`, but returns the supplier result. |
| `supply(keys, supplier)` | Multi-key variant of `supply` with the same sorted acquisition. |
| `supplyChecked(key, type, supplier)` / `supplyChecked(keys, type, supplier)` | Sync variants propagating the declared checked failure unwrapped. |
| `submitAsync(keys, task)` / `supplyAsync(keys, supplier)` | Async multi-key variants with the same sorted acquisition. |
| `isIdle(key)` | Whether no tasks are queued and no runner is in flight for `key`. |
| `getReentrantInlineCount()` | Cumulative peer-free nested same-key sync executions run inline. |
| `builder(executor)` | Builder for custom queues, selector, tracer, pool sizing, and timeout. |
| `setTimeout(Duration)` / `getTimeout()` | Configure / read the timeout for synchronous waits. |
| `getTracer()` | Access the tracer for custom span integration. |

Async submissions are fail-fast for the outermost submission: when the queue's executor rejects the work before it runs, the failure propagates to the calling thread, so `executeAsync` throws and `submitAsync` / `supplyAsync` / `supplyCheckedAsync` throw instead of returning their future (deduplicate retries via the key). The future-returning methods contain an outermost selector `Exception` instead: their submission future is completed exceptionally and returned. Rejections at inner multi-key levels never reach the submitting thread: they complete the returned future and are forwarded directly to a `RejectionAware` task.

Advanced wiring is available via constructors that accept a custom [`TaskQueueProvider`](lib/src/main/java/io/github/jinganix/peashooter/TaskQueueProvider.java), [`ExecutorSelector`](lib/src/main/java/io/github/jinganix/peashooter/ExecutorSelector.java), and [`Tracer`](lib/src/main/java/io/github/jinganix/peashooter/Tracer.java).

## Benchmarks

> ⚠️ The tables below are single-run illustrations (n=1) on Apple M1 Pro (10 cores, 16 GB RAM,
> JDK 21 G1). They are allocation- and GC-sensitive: samples taken in one run have differed by
> ~2x on the same host. Do not quote them as guarantees — please re-run locally for your hardware
> with:

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

The baselines push every increment through the same 8-thread pool and serialize only the counter
body on the lock, while `TaskQueue` drains one key on a single runner: both execute the 5,000,000
increments, but strict per-key ordering forfeits the pool's parallelism. The table therefore shows
end-to-end drain latency under the ordering guarantee, not per-operation overhead — expect
`TaskQueue` and the baselines to land within the same order of magnitude.

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
