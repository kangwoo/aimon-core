plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = findProperty("GROUP") as String
    version = findProperty("VERSION_NAME") as String

    repositories {
        mavenCentral()
    }
}

// Module-wide quality, formatting, packaging and publishing config now lives in
// pre-compiled script plugins under `buildSrc/src/main/kotlin/`:
//   - aimon.java-conventions  (Java 17, Spotless, Checkstyle, JaCoCo, JUnit, common deps)
//   - aimon.publishable       (Maven Central publishing via vanniktech)
//
// Each module opts in via `plugins { id("aimon.java-conventions") }` and, where applicable,
// `id("aimon.publishable")`.

// Convenience aggregator tasks for code quality. Subprojects use the convention plugin so
// `spotlessApply`, `spotlessCheck`, and `checkstyleMain` are guaranteed to exist.
//
// Guaranteed for every subproject that *has* Java sources, that is. `aimon-bom` is a `java-platform`, and
// Gradle refuses `java-platform` alongside the `java-library` that `aimon.java-conventions` applies — so it
// is the one project here with no Spotless, no Checkstyle and no tests to aggregate. It is excluded by
// asking what it is, not by name.
//
// Everything else is still addressed with `tasks.named`, which fails loudly when the task is missing. That
// is the point: a new module that forgets `aimon.java-conventions` breaks the root build instead of quietly
// slipping past the gates. Filtering with `matching { }` or a `withType` sweep would have made that
// omission invisible.
//
// The lookup runs inside the registration action, which Gradle defers until the task is realized — after
// every project has been configured. The existing `tasks.named` calls already depend on that ordering
// (`spotlessApply` is created by a plugin the subproject applies), so `hasPlugin` is answered at the same
// safe moment.
fun codeSubprojects(): List<Project> = subprojects.filterNot { it.plugins.hasPlugin("java-platform") }

tasks.register("format") {
    description = "Format all Java code using Spotless"
    group = "formatting"
    dependsOn(codeSubprojects().map { it.tasks.named("spotlessApply") })
}

tasks.register("checkFormat") {
    description = "Check Java code formatting using Spotless"
    group = "verification"
    dependsOn(codeSubprojects().map { it.tasks.named("spotlessCheck") })
}

tasks.register("checkStyle") {
    description = "Run Checkstyle on all modules"
    group = "verification"
    dependsOn(codeSubprojects().map { it.tasks.named("checkstyleMain") })
}

// `test` here is each module's own test task, which excludes the `@Tag("docker")` integration tests
// (see the aimon.java-conventions plugin). Docker-backed tests stay opt-in via `integrationTest`.
//
// The BOM has no tests, but it does have a claim that can be wrong — that it manages exactly the modules
// this build publishes — so `checkAll` picks up its `verifyBom` in place of the test task it lacks.
tasks.register("checkAll") {
    description = "Run all code quality checks (Spotless + Checkstyle + unit tests)"
    group = "verification"
    dependsOn("checkFormat", "checkStyle")
    dependsOn(codeSubprojects().map { it.tasks.named("test") })
    dependsOn(":aimon-bom:verifyBom")
}
