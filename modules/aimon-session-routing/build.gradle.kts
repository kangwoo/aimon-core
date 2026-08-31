plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Caffeine for LocalSessionCache (LRU + TTL).
    implementation(libs.caffeine)

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.logback.classic)
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 68
}
