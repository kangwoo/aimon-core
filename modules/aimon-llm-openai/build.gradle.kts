plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // OpenAI Java SDK
    implementation(libs.openai.client)

    // tiktoken Java port (BPE token counting for OpenAI models)
    implementation(libs.jtokkit)

    // Jackson for serialising tool inputs into the form OpenAI bills
    implementation(libs.jackson.databind)

    // OkHttp logging for debugging
    implementation(libs.okhttp.logging)

    // OkHttp itself, at compile scope: OpenAIAbortableHttpClient is this module's own transport for the SDK, so that
    // a cancelled call can cancel its okhttp3.Call. The version is the one the module already runs on (aimon-core
    // declares it; the SDK's 4.12.0 is conflict-resolved up to it), so nothing changes at runtime.
    implementation(libs.okhttp)

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.archunit.junit5)

    // The local HTTP server the SDK-retry tests count attempts against, shared with the other provider module.
    testImplementation(project(":aimon-llm-testkit"))
}
