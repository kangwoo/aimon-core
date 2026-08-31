plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // OpenSearch Java client
    implementation(libs.opensearch.client)
    implementation(libs.opensearch.rest.client)

    // Logging
    implementation(libs.slf4j.api)
}
