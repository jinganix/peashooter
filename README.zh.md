[![CI](https://github.com/jinganix/peashooter/actions/workflows/ci.yml/badge.svg)](https://github.com/jinganix/peashooter/actions/workflows/ci.yml)
[![Coverage](https://codecov.io/gh/jinganix/peashooter/graph/badge.svg?branch=master)](https://codecov.io/gh/jinganix/peashooter)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

[English Version](README.md)

# peashooter

面向 Java 线程池的按 key 有序任务执行 —— 无需全局锁即可保证同一 key 上的顺序，并安全支持嵌套同步调用以避免死锁。

## 特性

- **按 key 有序执行** —— 相同 key 的任务按提交顺序依次执行。
- **跨 key 并发** —— 不同 key 的任务在共享线程池中并行运行。
- **嵌套调用不死锁** —— `supply` / `executeSync` 会检测同一 key 上的重入调用并内联执行，避免阻塞（该 key 已有排队任务时快速失败）。
- **分布式追踪** —— 内置 trace ID 与父子 span，便于追踪有序调用链。
- **低开销** —— 任务执行热路径无锁；按 key 队列基于 Caffeine，支持按访问时间过期。

## 环境要求

- Java 21+

## 安装

### Maven

```xml
<dependency>
  <groupId>io.github.jinganix.peashooter</groupId>
  <artifactId>peashooter</artifactId>
  <version>0.0.11</version>
</dependency>
```

### Gradle (Groovy)

```groovy
implementation 'io.github.jinganix.peashooter:peashooter:0.0.11'
```

### Gradle (Kotlin)

```kotlin
implementation("io.github.jinganix.peashooter:peashooter:0.0.11")
```

## 快速开始

```java
ExecutorService pool = Executors.newFixedThreadPool(8);
OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);

// 同一 key 的任务串行执行，但仍请使用同步容器：混用多 key 或跨线程读取时需要线程安全集合。
List<String> values = Collections.synchronizedList(new ArrayList<>());
executor.executeAsync("user-1", () -> values.add("1"));
executor.supply("user-1", () -> { values.add("2"); return null; });
executor.executeAsync("user-1", () -> values.add("3"));
executor.executeSync("user-1", () -> values.add("4"));

// 提交顺序得到保留：[1, 2, 3, 4]
System.out.println("Values: [" + String.join(", ", values) + "]");
```

## 工作原理

### 按 key 排序

`OrderedTraceExecutor` 为每个 key 维护一个 [`TaskQueue`](lib/src/main/java/io/github/jinganix/peashooter/queue/TaskQueue.java)。提交方短暂加锁入队；工作线程对该 key 上的任务严格按 FIFO 顺序执行。

当同一 key 的三个任务按顺序提交时：

```java
executor.executeAsync("foo", task1);  // L1
executor.executeAsync("foo", task2);  // L2
executor.executeAsync("foo", task3);  // L3
```

key `"foo"` 上的执行顺序遵循入队顺序 —— **task1 → task2 → task3**。后续任务在前一个完成之前不会开始。不同线程并发提交时，执行顺序以到达队列的先后为准，而非源码顺序。

不同 key 相互独立 —— `"foo"` 与 `"bar"` 可以在线程池中同时运行。

### 无死锁的同步调用

不同 key 上的嵌套同步调用通过同一机制调度。内层 `supply` 在正确的队列上运行，不会跨 key 持有锁：

```java
int value =
    executor.supply(
        "foo",
        () ->
            executor.supply(
                "bar", () -> executor.supply("foo", () -> 1)));
// value == 1
```

**同一** key 上的重入同步调用会通过当前 trace span 检测：该 key 没有排队任务时内联执行，因此不会死锁等待自身；若该 key 已有排队任务，则抛出 `IllegalStateException` 快速失败，而不是越过排队任务执行（不会悄悄破坏提交顺序），此时需要调整调用结构，让同一 key 的嵌套工作在出现排队任务之前完成。多 key 重载（`executeSync(keys, …)`、`supply(keys, …)`）在去重后有两个及以上 key 时，每一层始终入队，以保证全局排序获取顺序；去重后只剩一个 key 的集合走上面的单 key 路径。

## API

| 方法 | 说明 |
|------|------|
| `executeAsync(key, task)` | 将 `task` 入队到 `key` 对应队列；立即返回。 |
| `executeSync(key, task)` | 在 `key` 的队列上运行 `task` 并阻塞至完成（默认超时 10 秒）。 |
| `executeSync(keys, task)` | 将 `keys` 按自然 `String` 排序去重后由外向内依次获取，再运行 `task`。 |
| `supply(key, supplier)` | 类似 `executeSync`，但返回 supplier 的结果。 |
| `supply(keys, supplier)` | `supply` 的多 key 版本，采用相同的排序获取顺序。 |
| `setTimeout(Duration)` / `getTimeout()` | 配置 / 读取同步等待的超时时间。 |
| `getTracer()` | 获取 tracer，用于自定义 span 集成。 |

可通过接受自定义 [`TaskQueueProvider`](lib/src/main/java/io/github/jinganix/peashooter/TaskQueueProvider.java)、[`ExecutorSelector`](lib/src/main/java/io/github/jinganix/peashooter/ExecutorSelector.java) 和 [`Tracer`](lib/src/main/java/io/github/jinganix/peashooter/Tracer.java) 的构造函数进行高级配置。

## 基准测试

> ⚠️ 下表仅为单次示意（n=1，Apple M1 Pro，10 核 16 GB 内存；负载下有 ±10–20% 波动），不可作为性能保证，请本地重跑：

```bash
./gradlew :lib:test --tests "*Benchmark*"
```

### TaskQueue（[源码](lib/src/test/java/io/github/jinganix/peashooter/queue/TaskQueueBenchmarkTest.java)）

5,000,000 次计数器自增，单线程队列消费（1 次预热 + 3 次计时输出 P95，见测试）。单次示意（n=1，M1 Pro），请本地重跑：

| 实现 | 耗时（n=1 单次示意，请本地重跑） |
|------|----------------------------------|
| `TaskQueue` | 620 ms |
| `synchronized` | 2336 ms |
| `ReentrantLock` | 2412 ms |

基准测试输出 3 次计时运行的 P95 并与两个基线对比，当 P95 达到任一基线的 `>= 2x` 时记录 `WARN`。吞吐量是排查信号，而非硬性墙钟门限：严格门限在负载较高的 CI 上会抖动，请本地重跑，并将持续约 2x 的退化视为需要排查的回归。

### Redis 可锁队列（[源码](lib/src/test/java/io/github/jinganix/peashooter/redisson/RedisLockableQueueBenchmarkTest.java)）

500 个任务（需要 Redis；见测试配置）：

| 实现 | 耗时 |
|------|------|
| `RedisLockableTaskQueue` | 84 ms |
| 每任务 Redis 锁 | 1268 ms |

## 贡献

请参阅 [CONTRIBUTING.md](CONTRIBUTING.md) 了解如何报告问题、运行测试和提交变更。

```bash
./gradlew build
```
