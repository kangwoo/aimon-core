plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Embedded HTTP server
    implementation(libs.javalin)

    // JSON parsing (already a transitive dep via core; declared explicitly for clarity)
    implementation(libs.jackson.databind)

    // Idempotency cache
    implementation(libs.caffeine)

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.okhttp)
    testRuntimeOnly(libs.logback.classic)
}
