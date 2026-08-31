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

## Temp files (read this first)

Never write scratch files, logs, or downloads to `/tmp` or the workspace root. Create a
session-specific directory once at the start of every session and keep ALL scratch artifacts inside it:

- Base: `$TMPDIR/opencode`
- Per session: `$TMPDIR/opencode/ses-<shortid>/`

Do not write scratch files directly into the shared base dir or the workspace. If a long-running background
job must survive past the session, log it inside the session dir but point it at the real destination, and
record the PID + log path in your reply.

## Design grounding

Before writing or reviewing substantial code in a module, ground yourself in its design principles. Start
with the user's guide (`USERS.md`, in particular `docs/USERS.md`); it is the best overview of how the
library is meant to work. Then read what applies to the module in question: the relevant README sections,
its `DEVELOPERS.md` where present, the `package-info.java` javadocs of the core packages, and the
foundational types it builds on (Bosk, BoskDriver, ReadSession, Reference).

## Project Structure

Subprojects starting with `bosk-` are published libraries that contain their own `README.md` files briefly explaining what they are.
`boson` is also a published library.
`lib-testing` is not published.
`example-hello` is an example Spring Boot application referenced in documentation and javadocs.

## Common Commands

The usual Gradle commands, plus:

```bash
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
- Generally, in a class, instance fields come first, followed by shared mutable state (i.e. static fields, even if the reference is final),
  then constructors, then methods in use-before-declaration order, and finally internal constants (static final) and the logger.

### Formatting

- Tabs for indentation, except in formats like Markdown and YAML where tabs and spaces are not equivalent.
- No wildcard imports.
- Always use curly braces for conditionals and loops.
  - Conditional guarded statements should be on their own line to facilitate breakpoints
- Prefer if-then-else over early returns (to make subsequent refactoring easier) except in specific situations:
  - If there's an especially simple case, like errors or "already computed" one-liner cases, those can return early to avoid mixing with complex logic

### Writing style

- Documentation and comments should compose proper sentences with normal words and punctuation,
  rather than gluing together sentence fragments with em dashes.
- Avoid the word "honest", including the filler "to be honest" (just drop it);
  prefer more meaningful words like "accurate", "representative", "faithful", "realistic", etc.

### Modules

We use the Java Platform Module System (JPMS) with `module-info.java` in published subprojects.
The module names follow the same conventions as package names.

### Exceptions

Consider `RuntimeException` to be abstract and throw the appropriate subtype:
often `IllegalStateException` but consider whether others are more appropriate.

We use checked exceptions to help avoid bugs, except where they'd place undue burden on the user.
Our internal exceptions are usually checked so the compiler can ensure we handle them.
Also, some methods (like `BoskDriver.initialState`) throw checked exceptions
because those methods are typically called in initialization code that is invoked by a dependency injection framework,
where `throws` clauses have no real downside.

We put exceptions for a module into a sub-package that ends with `.exceptions`
so they don't clutter up other packages.

When declaring `throws` on a method, use the specific exception type(s), not a superclass like `Exception`.
This applies to test methods too: declaring `throws Exception` is not a good habit
and we don't want examples of that in the code.

When a method's checked exceptions are expressed as generic type parameters (e.g. `throws X, Y`),
javac won't allow a dedicated `catch` clause for a specific checked exception such as `InterruptedException`,
because it can't prove the type parameter could be that exception.
The workaround (catch a broader type and test with `instanceof`) is straightforward;
just be sure to add a brief comment explaining the situation.

Don't delete unused constructors of Exception subclasses.
We typically provide a suite of constructors so future maintainers won't need to wonder if they're
breaking some implicit design rule by adding a new exception that wasn't there before.

### Logging

Pick the log level by its audience:
- `error` and `warn` are read by the application's operators, who may not know what bosk is. Write them in plain language, to guide troubleshooting. As a library, prefer `warn`; use `error` only for malfunctions that will inevitably be fatal to an operation the application was trying to perform.
- `info` targets knowledgeable Bosk users, and should explain what the library is doing in a way that helps them learn to use it more effectively.
- `debug` targets Bosk developers. Can use some Bosk jargon, but should also help new Bosk developers climb the learning curve, and should allow developers to tell what code paths executed.
- `trace` targets expert Bosk developers troubleshooting very tricky bugs. Can include information too voluminous to emit under most circumstances, such as stack traces for routine situations, dumps of entire data structures, or high-frequency messages, but do this cautiously, since even disabled log statements have nonzero overhead.

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

- **Exception while a PR is under review:** respond to review comments with new commits, one per logical change, and leave the existing commits alone. Don't squash review fixes into the commits that introduced the bugs, and don't rebase the branch, until the maintainer explicitly says it's time to tidy up the history. The guidelines below describe that final tidy-up, not the review process itself.

- Each **logical change** gets its own commit: one commit per bug fix (fix + test), one per refactoring, one per feature.
- Tests belong in the same commit as the code that motivated them (not in a separate "Tests" commit).
- Commits should be in this overall order:
  1. Fixes for existing bugs (including new tests for those bugs)
  2. Refactoring to make subsequent work easier
  3. The newly added functionality
- **Arrange commits in dependency order**: no commit references anything that appears in a future commit.
- When a bug is introduced **within the same branch**, squash the fix into the commit that introduced the bug. The history should read as if the code was correct from the start.
- Mechanical refactorings (eg. using an IDE) should be in their own commit describing what they do in enough detail that they could be repeated if necessary.
- Avoid merging a bug and its fix in the same PR. Prefer squashing the fix into the commit with the bug so it looks like the bug was never there.
- Each commit should have correct spotless formatting.
- Each commit should pass all tests unless it's marked as WIP or is explicitly doing test-driven development.
- For multi-line commit messages, the first line (the summary) should end with a period.
- Single-line commit messages should not end with a period.

### Pull request descriptions

- Write PR descriptions in plain, informal language. Say what the change is and why, and which modules it touches; don't pad them out with formal headers or restate what's obvious from the diff.
- Don't list the commits in the description: readers can browse the commits themselves.
- For bug fixes, describe the motivating bug and its mechanism so reviewers can see the "why", not just the "what" in the diff.
- If you refer to other work (an issue, PR, commit, or earlier investigation), link to it instead of alluding to it by name.
- Avoid the noun "flake" when describing a test failure. Say what failed and why; the word suggests randomness and hides the real cause.

## Test Coding Patterns

- Tests use JUnit 5
- For parameterizing test methods, use the `@InjectedTest` annotation: `bosk-junit/src/main/java/works/bosk/junit/InjectedTest.java`
- Tests for subprojects that integrate with external technologies like databases use Testcontainers to run those technologies, not mocks
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
- Prefer building the entire expected data structure and using `assertEquals` over checking individual fields one-by-one. Tests with per-field assertions get stale when the object acquires new fields.
- Assertion message strings should state what was expected (e.g. `"Set must have the new item"`), not describe the error (e.g. `"Set does not contain the new item"`).

## Hints

### Testing

- Use gradle's `--fail-fast` option to save time
- When developing a bug fix, write the test first and verify it fails against the unfixed code. Then apply the fix and confirm the test passes. This ensures the test would actually catch the bug.
- Use `./gradlew <task> --rerun` (not `--rerun-tasks`) to force Gradle to re-execute a task when cached results exist.
- Java assertions (-ea) are enabled for tests
- Do not dismiss test failures as "pre-existing". We must fix all failures. Pre-existing failures can be deferred until after the main work, but we must ultimately fix them.

### Miscellaneous

- Keep entries in `gradle/libs.versions.toml` alphabetized within each section.
- Alert the user to leftover branches that may be stale; it's valuable. But first fetch the relevant
  remote and confirm the branch isn't already merged into `upstream/main` (e.g. `git branch --no-merged upstream/main`).
  Feel free to `git fetch` any remote.

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
