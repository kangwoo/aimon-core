plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // MongoDB driver
    implementation(libs.mongodb.driver)

    // aimon-core keeps slf4j at implementation scope, so it does not reach this module transitively.
    implementation(libs.slf4j.api)

    testImplementation(libs.testcontainers.mongodb)

    // The VirtualFileSystem contract test, so this backend is checked against the same directory/listing/failure
    // behaviour as LocalFileSystem instead of only against its own description of itself.
    testImplementation(project(":aimon-filesystem-testkit"))
}
