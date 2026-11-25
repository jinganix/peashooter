[![CI](https://github.com/jinganix/peashooter/actions/workflows/ci.yml/badge.svg)](https://github.com/jinganix/peashooter/actions/workflows/ci.yml)
[![Coverage](https://codecov.io/gh/jinganix/peashooter/graph/badge.svg?branch=master)](https://codecov.io/gh/jinganix/peashooter)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

[English Version](README.md)

# peashooter

**peashooter** 是一个轻量 Java 库，为**线程池提供按 key 有序的任务执行**（也常称为 keyed serial executor / ordered executor）：相同 key 的任务按提交顺序依次执行，不同 key 的任务在共享线程池中并行运行——无需全局锁即可保证顺序，并支持嵌套同步调用以避免自死锁。

> 面向 AI 的摘要：[llms.txt](llms.txt) 提供可被 LLM 引用的标准事实、API 摘要与 FAQ。

## 目录

- [特性](#特性)
- [适用场景](#适用场景)
- [环境要求](#环境要求)
- [安装](#安装)
- [快速开始](#快速开始)
- [工作原理](#工作原理)
- [API](#api)
- [方案对比](#方案对比)
- [FAQ](#faq)
- [基准测试](#基准测试)
- [贡献](#贡献)

## 特性

- **按 key 有序执行** —— 相同 key 的任务按提交顺序依次执行。
- **跨 key 并发** —— 不同 key 的任务在共享线程池中并行运行。
- **嵌套调用不死锁** —— `supply` / `executeSync` 会检测同一 key 上的重入调用并内联执行，避免阻塞（该 key 已有排队任务时快速失败）。
- **分布式追踪（仅透传）** —— 内置 trace ID 与父子 span，便于追踪有序调用链。Trace 头可跨进程透传，排序本身只在单个 JVM 内生效。
- **低开销** —— 任务执行热路径无锁；按 key 队列基于 Caffeine，按空闲时间过期。

## 适用场景

当你需要在共享线程池上实现**按实体串行化**时使用 peashooter：

- 按用户 / 按账户的请求排序（同一用户的余额更新不能并发竞争）；
- DDD / 事件驱动系统中的按聚合根排序（同一聚合的事件按序应用）；
- 网关与 IM 系统中的按设备 / 按会话消息处理；
- 替代脆弱的“每 key 一线程”或 `synchronized` 锁映射方案。

**不适用：** 全局全序（用单个队列即可）、严格实时调度、跨进程（分布式）排序——peashooter 只保证单个 JVM 内的有序，跨进程排序需要分布式锁或队列。`src/test` 下（`redisson/setup`）的 Redis `RedisLockableTaskQueue` 只是测试参考实现，不随产品发布；通用的 `queue/LockableTaskQueue` 是产品 API，正常发布。

## 环境要求

- Java 21+

## 安装

### Maven

```xml
<dependency>
  <groupId>io.github.jinganix.peashooter</groupId>
  <artifactId>peashooter</artifactId>
  <version>0.0.15</version>
</dependency>
```

### Gradle (Groovy)

```groovy
implementation 'io.github.jinganix.peashooter:peashooter:0.0.15'
```

### Gradle (Kotlin)

```kotlin
implementation("io.github.jinganix.peashooter:peashooter:0.0.15")
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

// 提交顺序得到保留：[1, 2, 3, 4]。遍历时持有同一把锁：即使是同步 list，多线程可能修改时遍历仍需加锁。
synchronized (values) {
  System.out.println("Values: [" + String.join(", ", values) + "]");
}
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

### 嵌套同步调用

不同 key 上的嵌套同步调用通过同一机制调度。内层 `supply` 在正确的队列上运行，嵌套期间不会持有队列监视器——但外层 key 的 runner（以及等待内层调用的线程）会一直占用到内层调用结束：

```java
int value =
    executor.supply(
        "bar",
        () ->
            executor.supply(
                "foo", () -> executor.supply("bar", () -> 1)));
// value == 1
```

**同一** key 上的重入同步调用会通过当前 trace span 检测：该 key 没有排队任务时内联执行，因此不会死锁等待自身；若该 key 已有排队任务，则抛出 `IllegalStateException` 快速失败，而不是越过排队任务执行（不会悄悄破坏提交顺序），此时需要调整调用结构，让同一 key 的嵌套工作在出现排队任务之前完成。多 key 重载（`executeSync(keys, …)`、`supply(keys, …)`）在去重后有两个及以上 key 时，每一层始终入队，以保证全局排序获取顺序；去重后只剩一个 key 的集合走上面的单 key 路径。

**跨 key 嵌套的顺序很重要。** 嵌套的 `executeSync` / `supply` 在等待内层 key 时会一直持有外层 key 和一个线程池线程，因此嵌套的单 key 调用按**调用顺序**获取 key，而不是排序顺序。两条并发调用链若以相反顺序嵌套（`a → b` 与 `b → a`），会互相死锁，直到同步超时触发。多 key 重载不会出现该问题：它们会对持有的每个 key 排序并去重。跨 key 的工作请走多 key 重载，或让所有线程上的嵌套跨 key 同步调用按同一个全局顺序（例如升序）获取 key，并按“每个同时被阻塞的嵌套层一个线程”来配置线程池。

## API

| 方法 | 说明 |
|------|------|
| `executeAsync(key, task)` | 将 `task` 入队到 `key` 对应队列；线程池执行器下立即返回。使用 `DirectExecutor` 等同步执行器时，若抢占到空闲队列会内联执行并阻塞；若该 key 已有运行中的 runner，则排队其后并返回。任务失败仅记日志；需观察失败请用 `submitAsync`。 |
| `executeAsync(keys, task)` | 多 key fire-and-forget 版本，采用相同的排序获取顺序；future 被丢弃，任务失败仅记日志。 |
| `submitAsync(key, task)` / `supplyAsync(key, supplier)` | 入队到 `key`；返回任务运行后完成的 future。 |
| `supplyCheckedAsync(key, supplier)` / `supplyCheckedAsync(keys, type, supplier)` | 异步版本，声明的 checked 异常经由 future 传播。 |
| `executeSync(key, task)` | 在 `key` 的队列上运行 `task` 并阻塞至完成（默认超时 10 秒）。 |
| `executeSync(keys, task)` | 将 `keys` 按自然 `String` 排序去重后由外向内依次获取，再运行 `task`。 |
| `supply(key, supplier)` | 类似 `executeSync`，但返回 supplier 的结果。 |
| `supply(keys, supplier)` | `supply` 的多 key 版本，采用相同的排序获取顺序。 |
| `supplyChecked(key, type, supplier)` / `supplyChecked(keys, type, supplier)` | 同步版本，声明的 checked 异常直接抛出而非包装。 |
| `submitAsync(keys, task)` / `supplyAsync(keys, supplier)` | 异步多 key 版本，采用相同的排序获取顺序。 |
| `isIdle(key)` | `key` 是否无排队任务且无运行中的 runner（尽力快照；不要假设该 key 保持空闲）。 |
| `getReentrantInlineCount()` | 累计的无 peer 嵌套同 key 同步内联执行次数。 |
| `builder(executor)` | 构建器，用于自定义队列、selector、tracer、池大小与超时。 |
| `builder(selector)` | 构建器变体，无需 backing 池：自定义 selector 决定调度时使用。 |
| `setTimeout(Duration)` / `getTimeout()` | 配置 / 读取同步等待的超时时间，以及多 key 异步链每一层的等待上限。 |
| `getTracer()` | 获取 tracer，用于自定义 span 集成。 |

异步提交对外层提交为快速失败：队列的执行器在任务运行前拒绝时，失败会向调用线程传播，因此 `executeAsync` 会抛出，`submitAsync` / `supplyAsync` / `supplyCheckedAsync` 也会抛出而不返回 future（重试请按 key 去重）。带 future 的方法会容纳最外层的 selector `Exception`：其提交 future 以异常完成并被返回。多 key 内层的拒绝不会到达提交线程：它们完成返回的 future，并直接转发给 `RejectionAware` 任务。

可通过接受自定义 [`TaskQueueProvider`](lib/src/main/java/io/github/jinganix/peashooter/TaskQueueProvider.java)、[`ExecutorSelector`](lib/src/main/java/io/github/jinganix/peashooter/ExecutorSelector.java) 和 [`Tracer`](lib/src/main/java/io/github/jinganix/peashooter/Tracer.java) 的构造函数进行高级配置。

## 方案对比

| 方案 | 排序保证 | 并行度 | 嵌套调用安全 |
|------|---------|--------|--------------|
| `synchronized` / `ReentrantLock`（每 key 一锁） | 靠手写保证，易出错（锁映射泄漏、锁顺序） | 池线程阻塞在锁上 | 非可重入或顺序不对会自死锁 |
| 每 key 一个单线程 executor | 每 key FIFO | key 多时线程爆炸 | 跨 executor 嵌套可死锁 |
| 全局锁 / 单队列 | 全局全序（多数场景过强） | 无，一次只跑一个任务 | 易死锁 |
| **peashooter** | 按 key FIFO，由队列强制保证 | 共享线程池，跨 key 并行 | 同 key 重入内联执行；多 key 排序获取 |

## FAQ

**peashooter 是线程池吗？**
不是。它运行在你自己的 `ExecutorService` 之上，只负责排序——线程池的大小、拒绝策略与生命周期仍由你控制。

**跨线程提交能保证提交顺序吗？**
执行顺序以任务到达队列的先后为准，而非源码顺序。单线程提交时与程序顺序完全一致。

**同 key 嵌套同步调用遇到排队任务会怎样？**
抛出 `IllegalStateException`，而不会越过排队任务。请调整调用结构，让同 key 的嵌套工作在出现排队任务之前完成。

**如何避免跨 key 死锁？**
让所有线程上的嵌套跨 key 同步调用按同一个全局顺序（如升序）获取 key，或使用多 key 重载（`supply(keys, …)`，内部自动排序去重）。线程池请按“每个同时被阻塞的嵌套层一个线程”配置。

**还需要线程安全集合吗？**
需要。peashooter 只保证任务有序，不会让你的数据结构自动线程安全（混用多 key 或在队列线程之外读取时尤其如此）。

**同步默认超时是多少？**
10 秒，可通过 `setTimeout(Duration)` 或 builder 修改。

## 基准测试

> ⚠️ 下表仅为单次示意（n=1，Apple M1 Pro，10 核 16 GB 内存，JDK 21 G1）。该基准对分配与 GC 敏感：同一台机器同一次运行内的多次采样可相差约 2 倍，不可作为性能保证，请本地重跑：

```bash
skip_benchmark= ./gradlew :lib:test --tests "*Benchmark*"
```

（CI 默认导出 `skip_benchmark=true` 会跳过这些测试——如上显式置空才跑。Redis 基准还需要可达的 Redis，见其源码。）

### TaskQueue（[源码](lib/src/test/java/io/github/jinganix/peashooter/queue/TaskQueueBenchmarkTest.java)）

5,000,000 次计数器自增，单线程队列消费（1 次预热 + 3 次计时输出 P95，见测试）。单次示意（n=1，M1 Pro），请本地重跑：

| 实现 | 耗时（n=1 单次示意，请本地重跑） |
|------|----------------------------------|
| `TaskQueue` | 620 ms |
| `synchronized` | 2336 ms |
| `ReentrantLock` | 2412 ms |

两个基线把每次自增都提交到同一个 8 线程池，只用锁串行化计数器本身；`TaskQueue` 则在单个 runner 上消费同一个 key。两者都执行 5,000,000 次自增，但严格按 key 排序会放弃线程池的并行度，因此该表展示的是“排序保证下的端到端消费延迟”，而不是单次操作开销——`TaskQueue` 与两个基线应落在同一数量级。

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
