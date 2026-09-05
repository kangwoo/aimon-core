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

    // The narrowest platform that supplies what this module publishes without a version, and the reason it is not
    // `spring-boot-dependencies` like the other two testkits.
    //
    // Those two are not on Central, so what they re-export never leaves this build. This one is, and `api` puts a
    // platform into both `apiElements` and `runtimeElements` of the published metadata. Exporting Spring Boot's
    // platform would hand every consumer its entire dependency management — measured against a consumer on Spring
    // Boot 3.4.0, sixteen coordinates move, including `spring-boot-dependencies` itself, because Gradle applies
    // highest-version conflict resolution to platform modules too. A JUnit contract suite has no business
    // relocating someone's Jackson, Logback and Netty.
    //
    // And it never needed to. The only thing this module publishes without a version is `junit-jupiter`; AssertJ
    // carries its own from the catalog. Spring Boot's platform was reaching ~1,400 managed coordinates to settle
    // one of them.
    //
    // The catalog comment next to `junit` explains why a number now exists there after deliberately not existing:
    // in short, the tradeoff it described was priced for an unpublished module and publication reversed it.
    api(platform(libs.junit.bom))

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
