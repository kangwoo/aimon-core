---
paths:
  - "**/*.java"
  - "modules/**/build.gradle.kts"
---

# Java Code Style Rules

- Java 17 with pattern matching (`instanceof` pattern matching, switch expressions)
- **Prefer `class` over `record`** — Use immutable classes with builder pattern (reference: `at.aimon.core.agent.AgentContent`)
  - Exception — **`GenericTool` input DTOs** (`at.aimon.core.agent.tool.generic`) are `record`s. Each component carries a `@ToolParam` annotation that is the single source of *both* the generated JSON Schema and the binding of incoming `ToolInput` keys; a class + builder would restate every component three times (field, getter, builder setter) and, at ~19 lines per field measured on `Todo`, come out longer than the hand-written schema it replaces. The scope is exactly one thing: a type used as the input parameter of a `GenericTool`. Domain types, value objects and configuration objects are **not** covered — they keep the class + builder form. See also `.claude/rules/immutability-pattern.md` (deserialization targets are builder-exempt), which is the reason a `record` does not conflict with the builder rule either.
- Import order: `java` > `javax` > `jakarta` > `org` > `com` > (blank line) > project imports (`at.aimon.*`)
- Run `./gradlew format` before committing (Eclipse Formatter via Spotless, config: `config/eclipse/eclipse-formatter.xml`)
- Checkstyle applies to main source only, not tests (`./gradlew checkStyle`)
- Use `at.aimon.core.base.Principal` for identity representation (user, group, system, service)
- Null safety: use `Objects.requireNonNull()` at method entry points, `Optional<T>` for nullable returns
- Define `public static final String` constants for names/keys (e.g., `TOOL_NAME`, `READ_FILES_KEY`)
- Logger field naming: always `private static final Logger log` (lowercase `log`, never `LOG`)
- Gradle module dependencies: implementation modules must use `implementation(project(":aimon-core"))`, not `api()` — prevent leaking core types to transitive consumers
- Exception — **facade/aggregator modules**, whose whole purpose is to re-export a public API surface (`aimon-bootstrap`, `aimon-spring-boot-starter`), declare that surface with `api(project(":aimon-core"))`. Reason: Gradle's `implementation` publishes as `<scope>runtime</scope>` in the POM, so a consumer app receives the jar but **cannot compile** against `Agent` / `LiveSession` / `Tool`. The "don't leak" intent targets modules that merely *use* core types; for a facade, exposure is the deliverable. In-tree precedent: `aimon-sandbox-docker` and `aimon-sandbox-kubernetes` both re-export their SPI with `api(project(":aimon-sandbox"))` (`build.gradle.kts:9`) while keeping `implementation` for their own libraries. Not a general escape hatch — if a module only consumes core types, it is an implementation module and keeps `implementation()`.
  - `aimon-bootstrap` needs **two** `api(...)` lines — `:aimon-core` *and* `:aimon-session-routing` — because `AimonStack` returns types from both (`SessionRouter` is a routing type) and `aimon-session-routing` declares core as `implementation`, so it does not re-export core on its own. A facade re-exports every module whose types appear in its own signatures, not just core.
- A `java-platform` BOM (`aimon-bom`) is outside both the rule and its exception: it declares `dependencies { constraints { api(project(":aimon-core")) } }`, which publishes into `<dependencyManagement>` and never lands on any consumer's compile classpath. There is no `implementation` counterpart to choose, and a top-level (non-`constraints`) `api(...)` in a `java-platform` build is rejected by Gradle unless `javaPlatform { allowDependencies() }` is enabled — which would make the BOM a real dependency. Do not reason about a BOM by analogy with the facade exception above.
