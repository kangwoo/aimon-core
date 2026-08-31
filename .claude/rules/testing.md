---
paths:
  - "**/src/test/**/*.java"
---

# Testing Rules

## Framework & Tools
- JUnit 5 for test framework
- AssertJ for fluent assertions (prefer over JUnit assertions)
- Mockito for mocking
- `@TempDir` for temporary file system tests

## Conventions
- Test class name: `{ClassName}Test` in same package under `src/test/java`
- Run single test: `./gradlew :module:test --tests "fully.qualified.TestClass"`
- Run all: `./gradlew test`
- Checkstyle does NOT apply to test sources

## Test Coverage
- Test all critical paths and edge cases
- Tool tests: verify success cases, error cases, boundary values, null inputs
- Verify `ToolResult.isSuccess()` / `ToolResult.isError()` for tool tests
- Use `ToolInput.of(Map.of(...))` and `ToolContext.empty()` for test setup

## Integration Tests (Docker / Testcontainers)
- Tests that need a Docker daemon (Testcontainers — MongoDB / Postgres / Redis / LocalStack / GridFS, etc.)
  MUST be annotated `@Tag("docker")` at the class level.
- `./gradlew test` / `build` / `check` **exclude** `@Tag("docker")` so unit tests stay fast and daemonless.
- Run them with `./gradlew integrationTest` (per-module task; needs a running Docker daemon).
- Convention mirrors `@Tag("playwright")` in `aimon-browser-playwright`. Wiring lives in
  `buildSrc/.../aimon.java-conventions.gradle.kts` (`test` excludes the tag; `integrationTest` includes it).
- Keep the container lifecycle in a single `*TestSupport` class per module; tag every test class that uses it.

## Architecture Tests
- ArchUnit tests enforce module boundaries and naming conventions
- Location: `at.aimon.core.architecture` package in aimon-core test source
- Tool classes extending `AbstractTool` must have names ending with "Tool"

## Coverage
- JaCoCo configured for all subprojects — run `./gradlew jacocoTestReport` for reports
- HTML report: `build/reports/jacoco/test/html/index.html`
- Focus coverage on critical paths: tool execution, agent orchestration, scheduling

## Formatting
- Tests must pass Spotless formatting (`./gradlew format`)
- Eclipse Formatter applies to test sources as well
