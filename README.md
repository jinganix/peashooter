[![CI](https://github.com/jinganix/peashooter/actions/workflows/ci.yml/badge.svg)](https://github.com/jinganix/peashooter/actions/workflows/ci.yml)
[![Coverage Status](https://coveralls.io/repos/github/jinganix/peashooter/badge.svg?branch=master)](https://coveralls.io/github/jinganix/peashooter?branch=master)
[![License](http://img.shields.io/:license-apache-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

# peashooter

Call tasks sequentially and prevent deadlocks

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
