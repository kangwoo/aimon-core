plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Jackson for JSONL serialization.
    implementation(libs.bundles.jackson)

    // Logging
    implementation(libs.slf4j.api)

    testRuntimeOnly(libs.logback.classic)
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 23
}
