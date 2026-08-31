plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Anthropic Java SDK
    implementation(libs.anthropic.client)

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.archunit.junit5)
}
