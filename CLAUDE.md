# Bosk Project

## Overview

A Java library for state management in distributed systems.
Aims to reduce the behaviour gap between local development and production.

- **Build tool**: Gradle wrapper
- **Java version**: Latest for test code; latest LTS for published libraries
- **Published to**: Maven Central
- **Testing**: JUnit 5, Testcontainers, JMH, GitHub actions for CI
- **Logging**: SLF4J. Tests use Logback
- **Persistence and replication**: MongoDB (primary), SQL (experimental)

## Project Structure

Subprojects starting with `bosk-` are published libraries that contain their own `README.md` files briefly explaining what they are.
`boson` is also a published library.
`lib-testing` is not published.
`example-hello` is an example Spring Boot application referenced in documentation and javadocs.

## Common Commands

The usual Gradle commands, plus:

```bash
./gradlew smoke          # Fast-running tests (excludes @Slow tests)
./gradlew spotlessApply  # Apply code formatting
```

- **Testcontainers tests**: Requires Docker Desktop to be running (used by bosk-mongo, bosk-sql, etc.). Do not configure a remote Docker host. If Docker isn't running, the agent should ask the user to start it.
- Agents have trouble using PowerShell; use cmd instead.

## Architecture concepts

- **State Tree**: Immutable user-supplied in-memory tree structure composed mostly of records
- **ReadSession**: Coarse-grained snapshot-at-start semantics (e.g. one per HTTP request)
- **Drivers**: Pluggable layers for processing state updates; drivers form a _stack_, or chain, with updates passed along from one to the next until they reach the in-memory state tree
- **Hooks**: Callbacks for state change notifications
- **References**: Type-safe "pointers" into the state tree

## Key Classes/APIs

- `Bosk<R>` - Main class managing the state tree with root node of type R
- `BoskDriver` - Interface for state modifications
- `ReadSession` - Thread-local immutable snapshot of state
- `Reference<T>` - Type-safe accessor for a value of type T in the tree

## Coding patterns

### General
- We take warnings seriously. If a build issues a warning, it should be fixed at the earliest convenience.
- To the extent possible, we separate complex logic from side effects to facilitate unit testing.
- When something can't always work, we prefer it _never_ to work rather than _sometimes_ to work.
  - The overarching goal of Bosk is to reduce the behaviour gap between local development and production. If your code works, you probably did things right.

### Code ordering
- Generally, in a class, instance fields come first, followed by constructors, then methods in use-before-declaration order
- Static final constants go at the bottom, including any logger

### Formatting

- Tabs for indentation, except in formats like Markdown and YAML where tabs and spaces are not equivalent.
- No wildcard imports.
- Always use curly braces for conditionals and loops.
  - Conditional guarded statements should be on their own line to facilitate breakpoints
- Prefer if-then-else over early returns (to make subsequent refactoring easier) except in specific situations:
  - If there's an especially simple case, like errors or "already computed" one-liner cases, those can return early to avoid mixing with complex logic

### Modules

We use the Java Platform Module System (JPMS) with `module-info.java` in published subprojects.
The module names follow the same conventions as package names.

### Exceptions

Consider `RuntimeException` to be abstract and throw the appropriate subtype: often `IllegalStateException` but consider whether others are more appropriate.

We use checked exceptions to help avoid bugs, except where they'd place undue burden on the user.
Our internal exceptions are usually checked so the compiler can ensure we handle them.
Also, some methods (like `BoskDriver.initialState`) throw checked exceptions
because those methods are typically called in initialization code that is invoked by a dependency injection framework,
where `throws` clauses have no real downside.

We put exceptions for a module into a sub-package that ends with `.exceptions`
so they don't clutter up other packages.

### Javadocs

We use javadocs extensively, including in `module-info.java` and `package-info.java` files.
Avoid documenting words using themselves, like `@param settings  the settings`;
instead, consider someone who has seen the name but still has a question,
and try to answer that question in the javadocs.

Javadocs contain most of the info.
They focus on conveying design principles, navigating concepts in a hierarchical manner (module, package, class, method),
and giving practical usage advice and best practices.
They are concise but complete; do not assume the reader has access to the README.md file.
Put less formal things, like references to the `example-hello` project, in the README.md file instead.

### Lombok

We use Lombok sparingly. Most of its features are disabled in lombok.config.

### Wrangler interfaces

Wrangler interfaces (e.g. `OneMemberWrangler`, `MemberWrangler`, `Gatherer`) must have **more than one abstract method**. Single-method (SAM) wranglers would allow lambda usage, but lambdas don't preserve generic type parameters at runtime, breaking the reflection-based `DataType.of(wrangler.getClass())` type extraction used in factory methods. Always make wranglers multi-method to force anonymous-class instantiation.

### Commits

- Commits in a PR should ideally be rebased and massaged to follow these guidelines prior to committing, to give a clean history:

- Each **logical change** gets its own commit: one commit per bug fix (fix + test), one per refactoring, one per feature.
- Tests belong in the same commit as the code that motivated them (not in a separate "Tests" commit).
- Commits should be in this overall order:
  1. Fixes for existing bugs (including new tests for those bugs)
  2. Refactoring to make subsequent work easier
  3. The newly added functionality
- When a bug is introduced **within the same branch**, squash the fix into the commit that introduced the bug. The history should read as if the code was correct from the start.
- Mechanical refactorings (eg. using an IDE) should be in their own commit describing what they do in enough detail that they could be repeated if necessary.
- Avoid merging a bug and its fix in the same PR. Prefer squashing the fix into the commit with the bug so it looks like the bug was never there.
- Each commit should have correct spotless formatting.
- Each commit should pass all tests unless it's marked as WIP or is explicitly doing test-driven development.

## Testing Patterns

- Tests use JUnit 5
- For parameterizing test methods, use the `@InjectedTest` annotation: `bosk-junit/src/main/java/works/bosk/junit/InjectedTest.java`
- Tests for subprojects that integrate with external technologies like databases use Testcontainers to run those technologies, not mocks
- `@Slow` annotation marks tests excluded from `smoke` task
  - Smoke tests should exercise a component enough to demonstrate it's not completely broken; if the tests are fast, they can do more
- Subclasses of `DriverConformanceTest` in `bosk-testing` verify driver implementations; all drivers ought to pass these tests
  - Use `SharedDriverConformanceTest` for drivers that do replication between bosks
- Aim for readability in test code.
  - A good test does not merely fail if something goes wrong: it also serves to document examples of intended usage.
    Write tests that *demonstrate* the intended usage, not just exercise code paths.
    - Names given to test artifacts (classes, fields, methods etc.) should guide the reader to the correct generalization, rather than suggesting real-world meaning.
      - Good: `String field1`, `int field2`: convey that this test is expected to work on any field
      - Good: Country/City: concept that is clearly from another domain, clearly conveys a whole/part relationship, if that's what the test is demonstrating
      - Bad: `String name`, `int version`: might be framework concepts or meaningful domain properties; generalization is unclear.
      - Good: bosk jargon used in the right context, like "reference", "driver", "path".
      - Bad: Bosk jargon used outside its Bosk meaning.
      - Good: names that convey the relationship between the artifacts (`Catalog<T> parts`, `TaggedUnion<X> variant`).
    - Things that are the same should look the same; things that are different should look different.
      - Example: `field1`, `field2` suggests the test treats these fields the same way
      - Example: `date`, `name` suggests the test might treat dates and names differently
    - Test values should be transparently arbitrary. `Identifier.from("w1")` is better than `Identifier.from("alice")`
      because `w1` makes no claim about what it is beyond "widget ID 1".
  - With few exceptions, tests should use `assertEquals` rather than complex Hamcrest matchers; the latter is taken as an indication that the API being tested is too complicated
  - Mocks are avoided not because they're inherently bad, but because our components should be designed in a way that doesn't need them, since we favour immutable components and data structures
- Tests should be parallelizable, and they should be self-contained in that a failing test can be re-run on its own
- Tests should not emit logs unless something unexpected occurs
  - use `bosk-logback/src/main/java/works/bosk/logback/BoskLogFilter.java` in tests to suppress logs that we do want in production
- "Meta-tests" verify that a test is working correctly, like `bosk-testing/src/test/java/works/bosk/testing/drivers/ConformanceMetaTest.java`
- Tests should be deterministic, and they should not rely on timing except in rare cases where there is no alternative, or where timeout behaviour is specifically being tested
  - Where components have timeout settings, tests should adjust those settings to be very short or very long as appropriate to make spurious failures vanishingly rare

## Hints

### Testing

- When developing a bug fix, write the test first and verify it fails against the unfixed code. Then apply the fix and confirm the test passes. This ensures the test would actually catch the bug.
- Prefer building the entire expected data structure and using `assertEquals` over checking individual fields one-by-one. Tests with per-field assertions get stale when the object acquires new fields.
- Assertion message strings should state what was expected (e.g. `"Set must have the new item"`), not describe the error (e.g. `"Set does not contain the new item"`).
- Use `./gradlew <task> --rerun` (not `--rerun-tasks`) to force Gradle to re-execute a task when cached results exist.
- Java assertions (-ea) are enabled for tests

### Miscellaneous

- Keep entries in `gradle/libs.versions.toml` alphabetized within each section.

## Notes

- We use spotbugs for shipped code
- Published to Maven Central via GitHub actions, by creating a new release in GitHub
- We use GitHub Dependabot to keep dependencies up to date
- We separate setup from execution. Prefer designs where work is described once (at construction/configuration time) and executed many times efficiently.
  - `Reference` is an example: path parsing and validation happen once when the `Reference` is built, and then reads are fast.
    Apply this pattern when designing new abstractions — avoid doing expensive setup work inside hot paths.
- Each published subproject may have its own developer documentation (e.g. [bosk-mongo/DEVELOPERS.md](bosk-mongo/DEVELOPERS.md))
  - Changes to those subprojects should first consult that documentation for further guidance, and even update it if necessary
- Bosk treats reads and writes very differently
  - Some other projects perceive a symmetry between these two operations
  - The bosk philosophy is that they have nothing in common and are handled by entirely separate mechanisms. (This is almost a corollary of representing data with immutable structures.)
- Even when the bosk state is persisted (say, in MongoDB), the in-memory state tree is a _replica_, not a cache, and is always available.
- Prefer `if (x) { ... } else { ... }` over `if (!x) { ... } else { ... }` to avoid the double-negative `else` branch.
