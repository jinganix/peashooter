# Testing conventions

> Read this document before editing tests under `lib/src/test/**`.  
> Java style: [CODE_CONVENTIONS.md](CODE_CONVENTIONS.md)  
> Run tests from repository root: `./gradlew :lib:test`

---

## 1. Scope

- Test classes with real logic (`TaskQueue`, `LockableTaskQueue`, `OrderedTraceExecutor`, `Tracer`, Redis-backed queue providers, and other branching code).
- Skip pure model/mapping/pass-through code without branches.
- `*BenchmarkTest` classes are performance comparisons, not part of standard coverage expectations.

---

## 2. Behavior-first organization

Applies to **all** test types (unit, integration, benchmark):

- Organize tests around **observable behavior** (outcome + condition), not around mirroring source method names (`run`, `execute`, `supply`).
- Prefer flat `@Test` methods on the outer class. Use `@Nested` only when a group shares setup or clearly scopes one behavior slice.
- At most **one** `@Nested` level. Do not nest further for sub-scenarios.
- `@Nested` `@DisplayName` and test names use BDD wording (`when ...`, `should ... when ...`), not Java method names as the primary structure.
- Do **not** require one `@Nested` class per public method on the class under test.

---

## 3. Layout and naming

- Test package mirrors source package under `lib/src/test/java/io/github/jinganix/peashooter/`.
- Test class: `{ClassName}Test`.
- Prefer concise BDD format for `@DisplayName`: `should ... when ...`.
- Keep `@DisplayName` text lowercase at the start (do not start with uppercase).
- Test method names: `shouldReturn{Outcome}When{Condition}` (or `shouldThrow...When...`).

Do not use `Given ... -> should ...` in `@DisplayName`; use `should ... when ...` only. When touching older tests, migrate names and structure toward the BDD format above.

---

## 4. Unit tests

For core library classes (`queue/*`, `executor/*`, `trace/*`):

- Focus on **observable outcomes** through the public API (execution order, return values, exceptions, lock/unlock counts, span state).
- Keep tests flat by default; add one `@Nested` group only when several cases share the same Given setup.
- Group by behavior (for example `when lock cannot be acquired`, `when executor rejects work`), not by Java method name alone.
- Keep `// Given`, `// When`, `// Then` comments.
- Cover representative branches per behavior, including at least one failure path and one success path when the code branches.

For classes with a single public entry point and little branching (for example `DirectExecutor`):

- Put all tests directly on the outer test class (no `@Nested`).

Example:

```java
@DisplayName("TaskQueue")
class TaskQueueTest {

  @Test
  @DisplayName("should run tasks sequentially when two tasks are submitted")
  void shouldRunTasksSequentiallyWhenTwoTasksAreSubmitted() throws InterruptedException {
    // Given
    TaskQueue taskQueue = new TaskQueue();

    // When
    taskQueue.execute(createExecutor(), () -> sleep(100));
    AtomicReference<Long> elapsed = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    taskQueue.execute(
        createExecutor(),
        () -> {
          elapsed.set(System.currentTimeMillis() - startMillis);
          latch.countDown();
        });
    latch.await();

    // Then
    assertThat(elapsed.get()).isGreaterThanOrEqualTo(100);
  }
}
```

---

## 5. Integration tests (Redis)

For tests under `redisson/` that exercise distributed queue behavior:

- Annotate the class with `@ExtendWith(RedisExtension.class)`.
- In `@BeforeEach`, reset external state (for example `client.getKeys().flushall()`).
- Keep Redis wiring in `redisson/setup/` (`RedisExtension`, `RedisClient`, `RedisTaskQueueProvider`, test doubles).
- Organize by user-visible behavior (ordering, multi-key execution, provider selection), not by one `@Nested` per API method.
- Prefer `@Autowired`-free direct construction of `OrderedTraceExecutor` and collaborators, matching existing setup helpers.

Example:

```java
@DisplayName("RedisTaskQueue")
@ExtendWith(RedisExtension.class)
class RedisTaskQueueTest {

  private final OrderedTraceExecutor traceExecutor = createExecutor();
  private final RedissonClient client = RedisClient.client;

  @BeforeEach
  void setup() {
    client.getKeys().flushall();
  }

  @Test
  @DisplayName("should persist task result when one task is executed")
  void shouldPersistTaskResultWhenOneTaskIsExecuted() {
    // Given
    RList<TestItem> list = client.getList("list");

    // When
    TestItem value =
        traceExecutor.supply(
            "a",
            () -> {
              TestItem item = new TestItem(0, 1).setMillis(System.currentTimeMillis());
              list.add(item);
              return item;
            });

    // Then
    assertThat(list.get(0)).usingRecursiveComparison().isEqualTo(value);
  }
}
```

---

## 6. Parameterized tests

Use `@ParameterizedTest` when several cases differ only by inputs or collaborators (executor variants, queue providers, timeout values):

- Put the `@MethodSource` / `@ArgumentsSource` provider directly above the related parameterized test.
- Name each case with concise BDD wording (`should ... when ...`).
- Prefer shared providers such as `ExecutorForTests` instead of duplicating setup.

---

## 7. Benchmark tests

For `*BenchmarkTest` classes:

- Keep them isolated from normal `./gradlew :lib:test` expectations when possible (they may require Redis or long runtimes).
- Document measured environment and how to run locally in the test class or README.
- Do not treat benchmark timing assertions as regression gates unless explicitly stabilized.

---

## 8. Test infrastructure

| Location | Role |
|----------|------|
| `utils/TestUtils` | `sleep`, `uncheckedRun`, `uncheckedCall` for test ergonomics |
| `utils/SequentialTask` | Helpers for ordered execution scenarios |
| `executor/ExecutorForTests` | Shared executor variants for parameterized tests |
| `redisson/setup/RedisExtension` | Starts or reuses Redis (Testcontainers; skipped when `redis-host` env is set) |
| `redisson/setup/RedisClient` | Shared Redisson client for integration tests |
| `queue/LockableTaskQueueProvider` | In-memory lockable queue test double |

Shared helpers belong in the package that owns the concern. Do not copy Redis startup or latch boilerplate into every test class.

---

## 9. Assertions and helpers

- Use AssertJ (`assertThat`, `assertThatThrownBy`, `assertThatCode`).
- Use Mockito for spies/mocks/verification when isolating collaborators.
- Prefer `usingRecursiveComparison()` for value objects with nested fields.
- Use `CountDownLatch` / `Awaitility` for async timing; avoid fixed sleeps except when measuring elapsed time is the assertion.

---

## 10. Commands

```bash
./gradlew spotlessApply   # auto-fix Java formatting — run first when Spotless fails
./gradlew :lib:test
./gradlew build
./gradlew :lib:test --tests "io.github.jinganix.peashooter.queue.TaskQueueTest"
./gradlew :lib:test --tests "*Benchmark*"
```

After editing tests, run **build** (Spotless + compile + tests). Prefer `spotlessApply` before manual formatting fixes.

---

## 11. Quick checklist

- Behavior-first: names and structure reflect outcomes/conditions, not a 1:1 map to source methods.
- Unit tests: branch coverage via observable behavior; flat by default.
- Redis integration tests: `@ExtendWith(RedisExtension.class)`, reset state in `@BeforeEach`, setup code in `redisson/setup/`.
- Parameterized tests: provider above the test; BDD case descriptions.
- Prefer short BDD descriptions: `should ... when ...`.
- Use AssertJ + Mockito; shared helpers instead of duplicated setup.
