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
- Code in each file is ordered use-before-definition so it can be read and understood by a human from start to end, to the extent possible

### Formatting

Automatically enforced by Spotless.
Tabs for indentation, except in formats like Markdown and YAML where tabs and spaces are not equivalent.
No wildcard imports.

### Modules

We use the Java Platform Module System (JPMS) with `module-info.java` in published subprojects.
The module names follow the same conventions as package names.

### Exceptions

We use checked exceptions to help avoid bugs, except where they'd place undue burden on the user.
Our internal exceptions are usually checked so the compiler can ensure we handle them.
Also, some methods (like `BoskDriver.initialState`) throw checked exceptions
because those methods are typically called in initialization code that is invoked by a dependency injection framework,
where `throws` clauses have no real downside.

### Javadocs

We use javadocs extensively, including in `module-info.java` and `package-info.java` files.
Avoid documenting words using themselves, like `@param settings  the settings`;
instead, consider someone who has seen the name but still has a question,
and try to answer that question in the javadocs.

### Lombok

We use Lombok sparingly. Most of its features are disabled in lombok.config.

### What to avoid

- Do not use Mockito or other mocking libraries for tests.
  Mockito is a phenomenal solution to the wrong problem.
  Our components are designed to be stateless and to use immutable structures
  so that mocks are almost never necessary. If you think you need a mock, reconsider the design first.
- Don't merge wildcard imports. You can use them temporarily but they must be expanded before merging.
  Spotless will enforce this.
- Tests should not emit logs unless something unexpected occurs.
  - Use `bosk-logback/src/main/java/works/bosk/logback/BoskLogFilter.java` to suppress expected production logs in tests.

## Testing Patterns

- Tests use JUnit 5
- For parameterizing test methods, use the `@InjectedTest` annotation: `bosk-junit/src/main/java/works/bosk/junit/InjectedTest.java`
- Tests for subprojects that integrate with external technologies like databases use Testcontainers to run those technologies, not mocks
- `@Slow` annotation marks tests excluded from `smoke` task
  - Smoke tests should exercise a component enough to demonstrate it's not completely broken; if the tests are fast, they can do more
- Subclasses of `DriverConformanceTest` in `bosk-testing` verify driver implementations; all drivers ought to pass these tests
  - Use `SharedDriverConformanceTest` for drivers that do replication between bosks
- Aim for readability in test code.
  - A good test does not merely fail if something goes wrong: it also serves to document examples of intended usage
  - With few exceptions, tests should use `assertEquals` rather than complex Hamcrest matchers; the latter is taken as an indication that the API being tested is too complicated
  - Mocks are avoided not because they're inherently bad, but because our components should be designed in a way that doesn't need them, since we favour immutable components and data structures
- Tests should be parallelizable, and they should be self-contained in that a failing test can be re-run on its own
- Tests should not emit logs unless something unexpected occurs
  - use `bosk-logback/src/main/java/works/bosk/logback/BoskLogFilter.java` in tests to suppress logs that we do want in production
- "Meta-tests" verify that a test is working correctly, like `bosk-testing/src/test/java/works/bosk/testing/drivers/ConformanceMetaTest.java`
- Tests should be deterministic, and they should not rely on timing except in rare cases where there is no alternative, or where timeout behaviour is specifically being tested
  - Where components have timeout settings, tests should adjust those settings to be very short or very long as appropriate to make spurious failures vanishingly rare

## Notes

- We use spotbugs for shipped code
- Published to Maven Central via GitHub actions, by creating a new release in GitHub
- We use GitHub Dependabot to keep dependencies very up-to-date
- We separate setup from execution. Prefer designs where work is described once (at construction/configuration time) and executed many times efficiently.
  - `Reference` is an example: path parsing and validation happen once when the `Reference` is built, and then reads are fast.
    Apply this pattern when designing new abstractions — avoid doing expensive setup work inside hot paths.
- Each published subproject may have its own developer documentation (e.g. [bosk-mongo/DEVELOPERS.md](bosk-mongo/DEVELOPERS.md))
  - Changes to those subprojects should first consult that documentation for further guidance, and even update it if necessary
- Bosk treats reads and writes very differently
  - Some other projects perceive a symmetry between these two operations
  - The bosk philosophy is that they have nothing in common and are handled by entirely separate mechanisms. (This is almost a corollary of representing data with immutable structures.)
- Even when the bosk state is persisted (say, in MongoDB), the in-memory state tree is a _replica_, not a cache, and is always available.
