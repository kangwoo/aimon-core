// The scheduling SPI contract suites — task repository, execution-history repository, execution guard and interrupt
// bus — in a module of their own so a backend anywhere can run them.
//
// Published, like `aimon-memory-testkit` and for the same reason: the subjects are SPIs whose durable implementations
// are written outside this repository (a host's JPA or MongoDB stores, a host's distributed guard), and an unpublished
// suite is one exactly those implementations cannot run. The repository and interrupt-bus suites used to live in
// `aimon-session-testkit`, which is not published and also drags in `aimon-session-routing`; nothing here needs it.
plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // `api`: subclasses implement `repository()` / `guard()` / `busFor(...)`, which return aimon-core types, so core and
    // the JUnit/AssertJ annotations belong on their compile classpath — the facade argument `aimon-memory-testkit`
    // makes, and why this module is on `PublishedModuleApiScopeTest`'s facade list.
    api(project(":aimon-core"))

    // The narrow platform, not Spring Boot's: see the note in `aimon-memory-testkit/build.gradle.kts`.
    api(platform(libs.junit.bom))

    // Only what the sources import — every `api` entry lands in the POM at compile scope.
    api(libs.junit.jupiter)
    api(libs.assertj.core)
}
