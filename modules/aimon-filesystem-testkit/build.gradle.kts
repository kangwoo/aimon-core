// The VirtualFileSystem contract test, in a module of its own so every backend can run it.
//
// `java-test-fixtures` on aimon-core would have been the obvious home and does not work here: the publishing
// plugin (com.vanniktech.maven.publish 0.30.0) reacts to that plugin by calling a Gradle internal constructor
// removed in Gradle 9, so applying it to any published module fails configuration outright with
// `NoSuchMethodError: ProjectDerivedCapability.<init>(Project, String)`. A plain module reaches the same place
// without touching release infrastructure.
//
// It is deliberately NOT published: `aimon.publishable` is absent, so `aimon-bom` — which derives its managed
// list from that plugin — leaves it out automatically, the same way it already leaves out `aimon-sample-*`.
plugins {
    id("aimon.java-conventions")
}

dependencies {
    // `api`, not `implementation`: subclasses in other modules implement `VirtualFileSystem newFileSystem()`, so
    // core types and the JUnit/AssertJ annotations belong on their compile classpath. The usual "don't leak core
    // through implementation modules" rule exists to keep a published POM honest; nothing here is published.
    api(project(":aimon-core"))

    // The testing bundle names JUnit without a version, and the junit-bom that arrives with spring-boot-starter-test
    // reaches *test* configurations only — this module's *main* source set compiles against JUnit, so it names a
    // platform itself. JUnit's own, not Spring Boot's: `api` hands the platform to every consumer's test classpath.
    // Why that decides it, measured, is the catalog note next to `junit`, which covers all four testkits.
    api(platform(libs.junit.bom))
    api(libs.bundles.testing)
}
