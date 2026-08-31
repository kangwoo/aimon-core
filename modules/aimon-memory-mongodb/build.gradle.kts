plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // MongoDB sync driver (also used by aimon-session-mongodb and aimon-filesystem-gridfs).
    implementation(libs.mongodb.driver)

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.logback.classic)
}

// New module starts clean: any checkstyle warning fails the build.
checkstyle {
    maxWarnings = 0
}
