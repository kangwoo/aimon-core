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

    // JUnit and AssertJ are compiled against by this module's *main* source set, which the junit-bom arriving with the
    // conventions plugin's test dependencies does not reach, so the platform comes in here. JUnit's own, not Spring
    // Boot's — the catalog note next to `junit` says why for all four testkits.
    api(platform(libs.junit.bom))
    api(libs.bundles.testing)

    // `implementation`, and on the *main* source set, because AbstractSessionInboxDurabilityContractTest compiles
    // against logback's ListAppender to assert that a dropped inbox entry says so at WARN. The same reasoning is
    // already written down in aimon-core's build script: a ListAppender assertion is a compile-time dependency, not a
    // runtime one, so testRuntimeOnly — which is what the three backend modules declare — does not reach it from here.
    // `implementation` rather than `api` because subclasses do not compile against it, and no POM is at stake: this
    // module is deliberately not published.
    implementation(libs.logback.classic)
}
