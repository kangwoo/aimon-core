plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Postgres JDBC driver + Hikari pool.
    implementation(libs.postgresql)
    implementation(libs.hikari)

    // Jackson for cross-node payload serialization (outbox payloads, metadata).
    implementation(libs.bundles.jackson)

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.logback.classic)
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 12
}
