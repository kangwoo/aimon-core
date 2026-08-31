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

    // The testing bundle names JUnit without a version — every other module gets one from the junit-bom that
    // arrives transitively with spring-boot-starter-test, which the conventions plugin puts on *test*
    // configurations only. This is the one module whose main source set compiles against JUnit, so it brings the
    // same platform in itself instead of hard-coding a second version.
    api(platform(libs.spring.boot.dependencies))
    api(libs.bundles.testing)
}
