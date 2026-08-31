plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Postgres JDBC driver + Hikari pool.
    implementation(libs.postgresql)
    implementation(libs.hikari)

    // Jackson for cross-node payload serialization (signal payloads + idempotency entries).
    implementation(libs.bundles.jackson)

    // Logging
    implementation(libs.slf4j.api)

    // The shared multi-node contract suite, which is where this module's cross-node scenarios now live. It puts
    // `aimon-session-routing` on the test classpath through its own `api`, and nothing in this module's tests
    // names a routing type any more — so the direct `testImplementation(project(":aimon-session-routing"))` that
    // used to sit here is gone rather than kept as decoration.
    //
    // Routing is still absent from `main` for its original reason: every SPI this module implements —
    // SessionRecordStore, SessionLeaseStore, SessionInbox, SessionSignalBus, IdempotencyStore — lives in
    // aimon-core (SPX, docs/design/session/spi-extraction.md).
    testImplementation(project(":aimon-session-testkit"))

    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.logback.classic)
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 29
}
