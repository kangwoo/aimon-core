// The multi-node SessionRouter contract suite, in a module of its own so every session backend can run it.
//
// The same shape as `aimon-filesystem-testkit`, and for the same two reasons — see that build script for the long
// version. In short: `java-test-fixtures` on aimon-core fails configuration under the publishing plugin, and this
// module is deliberately NOT published (`aimon.publishable` is absent), so `aimon-bom` leaves it out on its own.
plugins {
    id("aimon.java-conventions")
}

dependencies {
    // `api`, not `implementation`: subclasses in the backend modules implement factory methods returning core SPI
    // types (SessionLeaseStore, SessionInbox, IdempotencyStore) and drive a SessionRouter, so both belong on their
    // compile classpath. The "don't leak core through implementation modules" rule guards a published POM's honesty;
    // nothing here is published. PublishedModuleApiScopeTest scans only modules applying `aimon.publishable`.
    api(project(":aimon-core"))

    // The suite's subject. A backend module keeps routing at `testImplementation` scope; this module is only ever on
    // a test classpath itself, so the same edge lands in the same place.
    api(project(":aimon-session-routing"))

    // JUnit and AssertJ are compiled against by this module's *main* source set, so — as in the filesystem testkit —
    // the platform comes in here rather than relying on the one the conventions plugin puts on test configurations.
    api(platform(libs.spring.boot.dependencies))
    api(libs.bundles.testing)
}
