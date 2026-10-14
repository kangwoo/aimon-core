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

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.archunit.junit5)

    // The local HTTP server the SDK-retry tests count attempts against, shared with the other provider module.
    testImplementation(project(":aimon-llm-testkit"))
}
