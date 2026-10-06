import java.util.Properties

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

// The measurement behind api-stability.md §6's "javadoc on every public API". Only published modules register
// `javadocCoverage` (aimon.publishable), so this picks them up by what they are, the way codeSubprojects() does.
// Prints one line per module and the total; each module's full list is in build/reports/javadoc-coverage/.
tasks.register("javadocCoverage") {
    description = "Count public API elements without javadoc across published modules (report only)"
    group = "documentation"
    val measured = subprojects.filter { it.plugins.hasPlugin("aimon.publishable") && it.plugins.hasPlugin("java-library") }
    dependsOn(measured.map { it.tasks.named("javadocCoverage") })
    val summaries = measured.associate { it.name to it.layout.buildDirectory.file("reports/javadoc-coverage/summary.properties") }
    doLast {
        val totals = summaries.mapValues { (_, f) ->
            val file = f.get().asFile
            if (file.exists()) Properties().apply { file.reader().use { load(it) } }.getProperty("total").toInt() else 0
        }
        totals.entries.sortedByDescending { it.value }.forEach { (name, n) -> println("%6d  %s".format(n, name)) }
        println("%6d  total (%d modules)".format(totals.values.sum(), totals.size))
    }
}

tasks.register("checkJavadocCoverage") {
    description = "Hold every published module's undocumented public API to its baseline"
    group = "verification"
    dependsOn(
        subprojects.filter { it.plugins.hasPlugin("aimon.publishable") && it.plugins.hasPlugin("java-library") }
            .map { it.tasks.named("checkJavadocCoverage") },
    )
}

tasks.register("checkStyle") {
    description = "Run Checkstyle on all modules"
    group = "verification"
    dependsOn(codeSubprojects().map { it.tasks.named("checkstyleMain") })
}

// Each module compares what its tests run on with what it ships (TestClasspathVersionsTask, registered by
// aimon.java-conventions). The first task here adds the one thing no module can see — a line in the record that names
// a module the build does not have, which no module's task would ever read. It is a task of its own rather than the
// aggregate's action so that it does not wait on the modules: under `--continue` a task whose dependency failed is
// skipped, and an orphaned line would then go unreported in exactly the run that was collecting every problem.
val checkRecordedDifferenceModules = tasks.register<RecordedDifferenceModulesTask>("checkRecordedDifferenceModules") {
    description = "Fails if ${RecordedDifferences.PATH} records a difference for a module this build does not have"
    group = "verification"
    recordedFile.set(layout.projectDirectory.file(RecordedDifferences.PATH))
    modules.set(codeSubprojects().map { it.name })
}

tasks.register("checkTestClasspathVersions") {
    description = "Hold every module's test classpath to the versions it ships, outside the recorded differences"
    group = "verification"
    dependsOn(checkRecordedDifferenceModules)
    dependsOn(codeSubprojects().map { it.tasks.named("checkTestClasspathVersions") })
}

// `test` here is each module's own test task, which excludes `@Tag("docker")` and `@Tag("packaging")` in every
// module (see the aimon.java-conventions plugin). Those tiers run under `integrationTest` and `packagingTest`,
// which `checkAll` does not aggregate but CI and the release gate both name -- out of this aggregate is not out
// of the gates. (There was a third, `playwrightTest`, until aimon-browser-playwright moved to its own repository.)
//
// The BOM has no tests, but it does have a claim that can be wrong — that it manages exactly the modules
// this build publishes — so `checkAll` picks up its `verifyBom` in place of the test task it lacks.
tasks.register("checkAll") {
    description = "Run all code quality checks (Spotless + Checkstyle + javadoc ratchet + test-classpath versions + " +
        "unit tests + the BOM's verifyBom)"
    group = "verification"
    dependsOn("checkFormat", "checkStyle", "checkJavadocCoverage", "checkTestClasspathVersions")
    dependsOn(codeSubprojects().map { it.tasks.named("test") })
    dependsOn(":aimon-bom:verifyBom")
}
