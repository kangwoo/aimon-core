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

    // The shared SessionRecordStore contract, run against LateBoundFencedRecordStore. The testkit is unpublished and
    // brings nothing this module's tests do not already resolve: core and routing are `api` above, and its JUnit and
    // AssertJ are the conventions plugin's.
    testImplementation(project(":aimon-session-testkit"))

    testRuntimeOnly(libs.logback.classic)
}
