// The engine contract suite of programmatic tool calling (docs/design/tool/programmatic-tool-calling.md §17): two
// engines — the GraalJS engine in aimon-graaljs and the shell engine — must produce the same observation for the same
// shape of program and give limits and cancellation the same meaning.
//
// A module of its own because aimon-core cannot use `java-test-fixtures`, and published because the shell engine's
// sandbox half is exercised from the aimon-sandbox repository, which can only run a suite it can depend on.
plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // `api`: subclasses implement `engine()` / `programs()`, which return aimon-core types, so core and the
    // JUnit/AssertJ annotations belong on their compile classpath — the facade argument `aimon-memory-testkit` makes.
    api(project(":aimon-core"))

    // The narrow platform, not Spring Boot's: see the note in `aimon-memory-testkit/build.gradle.kts`.
    api(platform(libs.junit.bom))

    // Only what the sources import — every `api` entry lands in the POM at compile scope.
    api(libs.junit.jupiter)
    api(libs.assertj.core)
}
