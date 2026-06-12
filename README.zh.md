[![CI](https://github.com/jinganix/peashooter/actions/workflows/ci.yml/badge.svg)](https://github.com/jinganix/peashooter/actions/workflows/ci.yml)
[![Coverage](https://codecov.io/gh/jinganix/peashooter/graph/badge.svg?branch=master)](https://codecov.io/gh/jinganix/peashooter)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

[English Version](README.md)

# peashooter

面向 Java 线程池的按 key 有序任务执行 —— 无需全局锁即可保证同一 key 上的顺序，并安全支持嵌套同步调用以避免死锁。

## 特性

- **按 key 有序执行** —— 相同 key 的任务按提交顺序依次执行。
- **跨 key 并发** —— 不同 key 的任务在共享线程池中并行运行。
- **嵌套调用不死锁** —— `supply` / `executeSync` 会检测同一 key 上的重入调用并内联执行，避免阻塞。
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

## 快速开始

```java
ExecutorService pool = Executors.newFixedThreadPool(8);
OrderedTraceExecutor executor = new OrderedTraceExecutor(pool);

List<String> values = new ArrayList<>();
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

假设从不同线程按以下**代码顺序**提交三个任务：

```java
executor.executeAsync("foo", task1);  // L1
executor.executeAsync("foo", task2);  // L2
executor.executeAsync("foo", task3);  // L3
```

无论哪个线程先完成入队，key `"foo"` 上的执行顺序始终是 **task1 → task2 → task3**。后续任务在前一个完成之前不会开始。

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

**同一** key 上的重入同步调用会通过当前 trace span 检测并内联执行，因此不会死锁等待自身。

## API

| 方法 | 说明 |
|------|------|
| `executeAsync(key, task)` | 将 `task` 入队到 `key` 对应队列；立即返回。 |
| `executeSync(key, task)` | 在 `key` 的队列上运行 `task` 并阻塞至完成（默认超时 10 秒）。 |
| `executeSync(keys, task)` | 按迭代顺序获取多个 key，再运行 `task`。 |
| `supply(key, supplier)` | 类似 `executeSync`，但返回 supplier 的结果。 |
| `supply(keys, supplier)` | `supply` 的多 key 版本。 |
| `setTimeout(timeout, unit)` | 配置同步等待的超时时间。 |
| `getTracer()` | 获取 tracer，用于自定义 span 集成。 |

可通过接受自定义 [`TaskQueueProvider`](lib/src/main/java/io/github/jinganix/peashooter/TaskQueueProvider.java)、[`ExecutorSelector`](lib/src/main/java/io/github/jinganix/peashooter/ExecutorSelector.java) 和 [`Tracer`](lib/src/main/java/io/github/jinganix/peashooter/Tracer.java) 的构造函数进行高级配置。

## 基准测试

在 Apple M1 Pro（10 核，16 GB 内存）上测得。本地运行：

```bash
./gradlew :lib:test --tests "*Benchmark*"
```

### TaskQueue（[源码](lib/src/test/java/io/github/jinganix/peashooter/queue/TaskQueueBenchmarkTest.java)）

5,000,000 次计数器自增，单线程队列消费：

| 实现 | 耗时 |
|------|------|
| `TaskQueue` | 620 ms |
| `synchronized` | 2336 ms |
| `ReentrantLock` | 2412 ms |

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
