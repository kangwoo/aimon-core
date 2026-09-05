// The five-tier PeerMemory contract suite, in a module of its own so every backend can run it.
//
// Same shape as `aimon-filesystem-testkit`: `java-test-fixtures` on aimon-core is not an option (the publishing
// plugin reacts to it by calling a Gradle internal removed in Gradle 9), and a plain module gets to the same place
// without touching release infrastructure.
//
// Published, unlike the other two testkits — and that difference is not an inconsistency, it is the difference
// between what each suite's subjects are. `aimon-filesystem-testkit` and `aimon-session-testkit` describe contracts
// whose every implementation is in this repository, so an unpublished module reaches all of them. This suite's
// subjects are `PeerMemory` backends, and after the distributed memory backends moved out, the implementation that
// most needs holding to the contract — `RemotePeerMemory`, in the aimon-memory service — is in another repository.
// Leaving it unpublished meant the one backend the contract was written for was the one backend that could not run
// it, which is the failure mode the suite exists to prevent.
//
// Note what this module does *not* depend on: `at.aimon.core.memory.file` and the `InMemory*Store`s. Those implement
// stores, not tiers, and stores are the default backend's materials rather than the seam a backend is replaced at.
plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // `api`, not `implementation`: subclasses in other modules implement `PeerMemory newBackend()`, so the core
    // memory types and the JUnit/AssertJ annotations belong on their compile classpath. This is what puts the
    // module in `PublishedModuleApiScopeTest`'s facade list — a contract suite whose extension point is an
    // aimon-core type is a re-exported API surface in the sense that rule carves out.
    api(project(":aimon-core"))

    // Kept on `api`, and this is the one place in the build where that decision is published rather than internal:
    // `aimon-filesystem-testkit` and `aimon-session-testkit` write the same line and neither is on Central. What
    // travels is Spring Boot 3.5.16's whole dependency management, into the `testImplementation` configuration of
    // every consumer — Jackson, Logback, Netty, Testcontainers and the rest quietly align to it.
    //
    // It stays because dropping it breaks resolution rather than narrowing it. `junit-jupiter` is published from
    // here with NO version: the catalog entry deliberately carries none, because this build's single source for
    // that version is the Spring Boot platform, and the testkit's main sources compile against JUnit. Move the
    // platform to `compileOnly` and consumers get a versionless coordinate nothing can resolve; pin a version in
    // the catalog instead and this repository grows a second JUnit version that can drift from the one every other
    // module tests on. Exporting the platform is the narrower mistake, and its blast radius is a test classpath
    // belonging to someone who already had to bring a JUnit runner.
    //
    // Recorded rather than assumed because `api-stability.md` §5 makes it a one-way door: after the first release
    // this cannot be taken back before the next minor.
    api(platform(libs.spring.boot.dependencies))

    // The two the source actually uses, named individually rather than through `libs.bundles.testing`. That bundle
    // also carries mockito-core and mockito-junit-jupiter, which this module references nowhere — while it was
    // unpublished that cost nothing, but `api` puts every entry into the POM at compile scope, so publishing the
    // bundle would put two unused libraries on every consumer's compile classpath. Taking them back out afterwards
    // is a public-API reduction (`api-stability.md` §5) and a Maven Central publish cannot be recalled; declaring
    // only what is used costs nothing before the first release. Keep this list matched to the imports in
    // `AbstractPeerMemoryContractTest`, not to the bundle.
    api(libs.junit.jupiter)
    api(libs.assertj.core)
}
