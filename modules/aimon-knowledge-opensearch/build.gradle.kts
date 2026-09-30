plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // OpenSearch Java client (3.x ships its own Apache HttpClient 5 transport — no low-level rest client)
    implementation(libs.opensearch.client)

    // Logging
    implementation(libs.slf4j.api)
}
