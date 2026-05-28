# Java code conventions

> Read this document before editing `lib/src/main/**`.  
> Tests: [TEST_CONVENTIONS.md](TEST_CONVENTIONS.md)

---

## 1. Control flow — always block + newline

**Do not** put the body on the same line as the condition. Every `if`, `else if`, `else`, `for`, `while`, and `do` body must use braces and start on its own line.

```java
// Bad — body on the same line
if (foo) return bar;
if (foo) doSomething();

// Bad — single-line block
if (foo) { return bar; }

// Good
if (foo) {
  return bar;
}
```

Applies to early returns, throws, and single-statement branches alike. No braceless one-liners.

---

## 2. Lint, format, and build

After editing `lib/src/main/**`, verify build from repository root. Prefer auto-format first:

```bash
./gradlew spotlessApply   # auto-fix Java formatting — run first when Spotless fails
./gradlew build           # compile + Spotless check + tests
```

When Spotless or compile errors remain after `spotlessApply`, fix them manually and re-run build.
