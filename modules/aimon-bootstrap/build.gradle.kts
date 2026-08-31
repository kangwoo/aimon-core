plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // Facade module: consumers assemble against core + routing types that this module
    // returns from its public API (AimonStack exposes SessionRouter, OrcaAgentRuntime, ...),
    // so both must be `api` — aimon-session-routing declares core as `implementation` and
    // therefore does not re-export it.
    api(project(":aimon-core"))
    api(project(":aimon-session-routing"))

    // Logging
    implementation(libs.slf4j.api)

    testRuntimeOnly(libs.logback.classic)
}
