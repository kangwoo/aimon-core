// The five-tier PeerMemory contract suite, in a module of its own so every backend can run it.
//
// Same shape and same reasons as `aimon-filesystem-testkit`: `java-test-fixtures` on aimon-core is not an option
// (the publishing plugin reacts to it by calling a Gradle internal removed in Gradle 9), and a plain module gets
// to the same place without touching release infrastructure.
//
// Deliberately NOT published: `aimon.publishable` is absent, so `aimon-bom` — which derives its managed list from
// that plugin — leaves it out automatically.
//
// Note what this module does *not* depend on: `aimon-memory-file`, `-mongodb` and `-postgres`. Those three
// implement stores, not tiers, and stores are the default backend's materials rather than the seam a backend is
// replaced at. The suite's subjects are `PeerMemory` implementations.
plugins {
    id("aimon.java-conventions")
}

dependencies {
    // `api`, not `implementation`: subclasses in other modules implement `PeerMemory newBackend()`, so the core
    // memory types and the JUnit/AssertJ annotations belong on their compile classpath.
    api(project(":aimon-core"))

    api(platform(libs.spring.boot.dependencies))
    api(libs.bundles.testing)
}
