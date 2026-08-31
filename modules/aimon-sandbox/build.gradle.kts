plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.archunit.junit5)
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 68
}
