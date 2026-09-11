plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // Logging — a library logs through the SLF4J API and must not choose the binding for its consumers: a
    // runtimeOnly binding lands in the published POM and collides with a host app's own provider (log4j2, ...).
    // Test scope only; testImplementation rather than testRuntimeOnly because two tests compile against
    // logback's ListAppender to assert on log output.
    implementation(libs.slf4j.api)
    testImplementation(libs.logback.classic)

    // Cron expression parsing
    implementation(libs.cron.utils)

    // YAML frontmatter parsing
    implementation(libs.snakeyaml)

    // JSON processing
    implementation(libs.jackson.databind)

    // Template engine
    implementation(libs.mustache.java)

    // HTTP client
    implementation(libs.okhttp)

    // HTML parsing
    implementation(libs.jsoup)

    // Module-specific test dependencies (junit/assertj/mockito provided by root subprojects).
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.archunit.junit5)

    // The shared VirtualFileSystem contract test, so the in-tree backends (LocalFileSystem, ScopedVirtualFileSystem)
    // are held to the same description of the contract as the out-of-tree ones. This points *back* at a module that
    // depends on aimon-core, which is not a cycle: `aimon-core:test` -> `aimon-filesystem-testkit:main` ->
    // `aimon-core:main`. Only main-source dependencies would have to be acyclic.
    testImplementation(project(":aimon-filesystem-testkit"))

    // The shared five-tier PeerMemory contract suite, for the same reason and by the same (non-)cycle: the default
    // backend is the one every other backend will be compared against, so it is the first thing the suite has to
    // hold.
    testImplementation(project(":aimon-memory-testkit"))
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 344
}

// Three guards in `at.aimon.core.architecture` assert on files that are not on any classpath, so Gradle
// cannot infer them. Without these declarations the offending edit leaves `test` UP-TO-DATE and the guard
// never runs — the build reports green on exactly the change the test exists to catch.
//
// `ReleaseGateMatchesCiGateTest` reads the release script, the CI workflow and the `/release` skill, and its key
// census reads every `.java` under `modules/aimon-llm-*/src/test`. That tree is declared below as a glob on the
// census's own `aimon-llm-` prefix rather than a list of modules, so a provider module is an input as soon as its
// directory exists; a wider census needs a wider glob here. The same test's tag scan reads every test source under
// `modules/` and `samples/` and is not declared: that would re-run this module's suite, most of the build's tests,
// after a test edit in any module. The test's javadoc says so.
// `PublishedModuleApiScopeTest` and `PublishedModuleLoggingBindingTest` read build scripts: every module's
// own, plus the shared ones each module inherits. Those two were added the other way round — the scope test
// was written first and passed while a deliberately mis-scoped module sat in the tree, because nothing had
// changed on the test's classpath. The second file it reads is the reason the set below is a tree and not
// two more `inputs.file` lines.
tasks.test {
    inputs.file(rootProject.file("scripts/release.sh")).withPropertyName("releaseScript")
    inputs.file(rootProject.file(".github/workflows/build.yml")).withPropertyName("ciWorkflow")
    inputs.file(rootProject.file(".claude/skills/release/SKILL.md")).withPropertyName("releaseSkill")
    inputs.files(rootProject.fileTree("modules") { include("aimon-llm-*/src/test/**/*.java") })
        .withPropertyName("providerModuleTestSources")
    inputs.files(rootProject.fileTree("modules") { include("*/build.gradle.kts") })
        .withPropertyName("moduleBuildScripts")
    inputs.files(rootProject.fileTree("buildSrc/src/main/kotlin") { include("*.gradle.kts") })
        .withPropertyName("sharedBuildScripts")
    inputs.file(rootProject.file("build.gradle.kts")).withPropertyName("rootBuildScript")
}
