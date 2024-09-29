[![CI](https://github.com/jinganix/peashooter/actions/workflows/ci.yml/badge.svg)](https://github.com/jinganix/peashooter/actions/workflows/ci.yml)
[![Coverage Status](https://coveralls.io/repos/github/jinganix/peashooter/badge.svg?branch=master)](https://coveralls.io/github/jinganix/peashooter?branch=master)
[![License](http://img.shields.io/:license-apache-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

# peashooter

Call tasks sequentially and prevent deadlocks

## Description

### Call tasks sequentially

We have an executor:
```java
OrderedTraceExecutor executor = new OrderedTraceExecutor(Executors.newFixedThreadPool(8));
```

The following three lines of code (named L1, L2, L3) have the same key "foo". When they are executed in different threads in the order L2 -> L3 -> L1, we can guarantee that:
- Tasks will be executed in the order they are called in the code, with the execution order being task2 -> task3 -> task1.
- Tasks will not be executed sequentially, task3 will only execute after task2 has completed.
```java
executor.executeAsync("foo", task1);
executor.executeAsync("foo", task2);
executor.executeAsync("foo", task3);
```

### Prevent deadlocks

We have an executor:
```java
OrderedTraceExecutor executor = new OrderedTraceExecutor(Executors.newFixedThreadPool(8));
```

Using `executor.supply` to synchronously execute a `Supplier`, the following code will not deadlock, and the value will be assigned to 1:
```java
int value = executor.supply("foo", () -> executor.supply("bar", () -> executor.supply("foo", () -> 1)));
```

## Benchmark

Perform benchmark using following machine:
```
Model Name: MacBook Pro
Model Identifier: MacBookPro18,1
Chip: Apple M1 Pro
Total Number of Cores: 10 (8 performance and 2 efficiency)
Memory: 16 GB
System Firmware Version: 11881.1.1
OS Loader Version: 10151.140.19.700.2
Activation Lock Status: Disabled
```

### [TaskQueue Benchmark](lib/src/test/java/io/github/jinganix/peashooter/queue/TaskQueueBenchmarkTest.java)

Execute 5,000,000 counting tasks and measure the execution time.

- [TaskQueue](lib/src/main/java/io/github/jinganix/peashooter/queue/TaskQueue.java): 620ms
- synchronized: 2336ms
- ReentrantLock: 2412ms

### [RedisLockableQueue Benchmark](lib/src/test/java/io/github/jinganix/peashooter/redisson/RedisLockableQueueBenchmarkTest.java)

- [RedisLockableTaskQueue](lib/src/test/java/io/github/jinganix/peashooter/redisson/setup/RedisLockableTaskQueue.java): 84ms
- Lock per task: 1268ms

## Installation

### Maven

```xml
<dependency>
  <groupId>io.github.jinganix.peashooter</groupId>
  <artifactId>peashooter</artifactId>
  <version>0.0.5</version>
</dependency>
```

### Gradle (Groovy)

```groovy
implementation 'io.github.jinganix.peashooter:peashooter:0.0.5'
```

### Gradle (Kotlin)

```kotlin
implementation("io.github.jinganix.peashooter:peashooter:0.0.5")
```

## Basic usage

```java
OrderedTraceExecutor executor = new OrderedTraceExecutor(Executors.newFixedThreadPool(8));
List<String> values = new ArrayList<>();
executor.executeAsync("foo", () -> values.add("1"));
executor.supply("foo", () -> values.add("2"));
executor.executeAsync("foo", () -> values.add("3"));
executor.executeSync("foo", () -> values.add("4"));
System.out.println("Values: [" + String.join(", ", values) + "]"); // Values: [1, 2, 3, 4]
```

## Contributing

If you are interested in reporting/fixing issues and contributing directly to the code base, please see [CONTRIBUTING.md](CONTRIBUTING.md) for more information on what we're looking for and how to get started.
