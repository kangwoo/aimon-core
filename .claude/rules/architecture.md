---
paths:
  - "modules/**/build.gradle.kts"
  - "modules/**/src/main/**/*.java"
---

# Module Architecture Rules

## Dependency Direction
- `aimon-core` defines interfaces and abstractions only — never depends on implementation modules
- Implementation modules (`aimon-llm-*`, `aimon-filesystem-*`, etc.) depend on core via `implementation()`
- CLI assembles implementations — it is the only module allowed to depend on multiple implementation modules

## Module Boundary Enforcement
- Use `implementation()` (not `api()`) for `project(":aimon-core")` in all implementation modules — exception: facade/aggregator modules whose purpose is re-exporting a public API surface (`aimon-bootstrap`, `aimon-spring-boot-starter`; existing precedent `aimon-sandbox-docker`) use `api()`, and a `java-platform` BOM uses `constraints { api(...) }`. A facade re-exports *every* module whose types appear in its own signatures, not only core — `aimon-bootstrap` has `api(project(":aimon-session-routing"))` alongside core because `AimonStack` returns `SessionRouter`. See `.claude/rules/code-style.md`
- The converse of the facade rule: a module that only *implements* an SPI should depend on wherever that SPI actually lives, and nothing more. `aimon-session-{redis,postgres,mongodb}` do not name `aimon-session-routing` at all — every SPI they implement (`SessionRecordStore`, `SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`, `IdempotencyStore`) lives in `aimon-core`, so their main sources compile without the routing module. Their multi-node tests still drive a real `SessionRouter`, but through `aimon-session-testkit`, which declares routing on `api`; the direct `testImplementation` line those modules used to carry went away when the scenarios moved into that testkit, because a dependency nothing names is one nothing declares
- ArchUnit tests in aimon-core enforce dependency rules at build time
- No circular dependencies between packages

## Package Naming Convention
- Core interfaces: `at.aimon.core.{domain}.*` (e.g., `at.aimon.core.llm.LlmClient`)
- Concrete in-tree implementations live alongside the interface as `at.aimon.core.{domain}.impl.*`
  (e.g. `at.aimon.core.filesystem.impl.local.LocalFileSystem`). Direct imports of `*.impl`
  from outside the same domain are discouraged — depend on the interface and let DI / a registry
  pick the implementation.
- External-module implementations (`aimon-llm-openai`, `aimon-filesystem-gridfs`, ...) keep their
  own package namespace.
- Tool implementations: `at.aimon.core.tools.{category}.*` (the legacy `at.aimon.core.ext.tools.*`
  namespace was retired in the Stage 6 refactor; ArchUnit's `extPackageIsDecommissioned` rule
  prevents reintroduction).

## Adding a New Module
1. Create `modules/aimon-{name}/build.gradle.kts`
2. Add to `settings.gradle.kts`
3. Apply `id("aimon.java-conventions")` in the module's `plugins { }` block — that pre-compiled script plugin (`buildSrc/src/main/kotlin/`) supplies Java 17, Spotless, Checkstyle, JaCoCo, JUnit and the shared test dependencies. The **only** exemption is a `java-platform` (`aimon-bom`), which Gradle refuses to combine with the `java-library` the conventions plugin applies; the root aggregators skip platform projects for that reason and still name their tasks on everything else, so forgetting this plugin on a normal module breaks the root build rather than silently escaping the gates
4. Use `implementation(project(":aimon-core"))` for core dependency — unless the module is a facade/aggregator that re-exports the core API, which uses `api()` (see `.claude/rules/code-style.md`)
5. Place code in appropriate package namespace
6. To publish to Maven Central, add `id("aimon.publishable")` to the same `plugins { }` block. Publishing is opt-in per module via that plugin — the root `build.gradle.kts` holds **no** list of publishable modules and must not be edited for this
