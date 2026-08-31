plugins {
    id("aimon.java-conventions")
    id("aimon.spring-starter")
    id("aimon.publishable")
}

dependencies {
    // Facade module: the host app compiles against Agent / LiveSession / AgentExecutionResult and against
    // AimonStack, so both layers are re-exported. See .claude/rules/code-style.md — `implementation` would
    // publish as runtime scope and leave the app unable to compile against the types it is handed.
    api(project(":aimon-core"))
    api(project(":aimon-bootstrap"))
    api(project(":aimon-session-routing"))

    // LLM providers are selected by `aimon.llm.provider` at runtime. compileOnly so neither vendor SDK
    // lands on the classpath of an app that uses the other one (or brings its own LlmClient bean); the
    // slices guard the reference with @ConditionalOnClass, which is evaluated from bytecode, not by loading.
    compileOnly(project(":aimon-llm-anthropic"))
    compileOnly(project(":aimon-llm-openai"))

    // Same arrangement for the Quartz scheduler, selected by `aimon.scheduling.backend=quartz`. Quartz itself is
    // named a second time because aimon-scheduling-quartz declares it `implementation` and so does not re-export
    // it — and the slice's @ConditionalOnClass mentions org.quartz.Scheduler directly, since the adapter class
    // being present says nothing about the library it needs.
    compileOnly(project(":aimon-scheduling-quartz"))
    compileOnly(libs.quartz)

    // Actuator, for the health indicator and the tenant-runtime meters. Same compileOnly arrangement and the
    // same reason: an application that does not expose an operations endpoint should not receive one because it
    // depended on AIMON, and both branches are @ConditionalOnClass-guarded so the types are never loaded.
    //
    // The starter rather than micrometer-core alone, even though the two branches are independent: this one
    // artifact supplies both org.springframework.boot.actuate.health.HealthIndicator and
    // io.micrometer.core.instrument.MeterRegistry, and it carries the Micrometer version Boot 3.5 manages.
    // Naming libs.micrometer.core instead would pin 1.12.1 from this catalog against the 1.14.x an application
    // on this Boot line actually runs — a compile against one minor and a runtime on another, for no gain.
    compileOnly(libs.spring.boot.starter.actuator)

    implementation(libs.slf4j.api)

    // Both providers on the test classpath so the slice tests can assert the selector picks the right one,
    // and FilteredClassLoader can hide one to assert the @ConditionalOnClass back-off.
    testImplementation(project(":aimon-llm-anthropic"))
    testImplementation(project(":aimon-llm-openai"))

    // Quartz on the test classpath for the same two reasons: assert the backend selector builds a Quartz-backed
    // spec, and hide the classes with FilteredClassLoader to assert the back-off.
    testImplementation(project(":aimon-scheduling-quartz"))
    testImplementation(libs.quartz)

    // Actuator on the test classpath so the observability slice can be asserted at all — and so its two branches
    // can be hidden separately with FilteredClassLoader, which is the only way to show they really are
    // independent rather than merely written in two blocks.
    testImplementation(libs.spring.boot.starter.actuator)

    // AimonPropertiesMetadataTest reads the metadata the configuration processor generated for this module.
    // Jackson is already on the test runtime classpath transitively (aimon-core declares it `implementation`),
    // but not on the test *compile* classpath — and relying on a transitive runtime artifact to compile
    // against is exactly the leak the `implementation` scope exists to prevent. Declared explicitly.
    testImplementation(libs.jackson.databind)

    // The two-node cluster test builds two independent stacks over one backend. Redis is the only backing
    // module that ships all five collaborators the scenario needs (record store + the four cluster SPIs) from a
    // single container, so it is the cheapest honest version of "two nodes, one database". testImplementation
    // only: the starter selects a backend, it never constructs one, and putting this on the main classpath
    // would make that sentence false.
    //
    // Lettuce is declared even though aimon-session-redis already brings it — that module declares it
    // `implementation`, so it arrives at test *runtime* but not on the test compile classpath, and compiling
    // against a transitive runtime artifact is the leak `implementation` exists to prevent (same reasoning as
    // the Jackson line above).
    testImplementation(project(":aimon-session-redis"))
    testImplementation(libs.lettuce.core)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.logback.classic)
}

// `AimonDocumentedPropertiesTest` reads the guides, which are on no classpath, so Gradle cannot infer them.
// Without this the common case — editing only a document — leaves `test` UP-TO-DATE and the guard never runs, so
// the build reports green on precisely the change it exists to check. Same reasoning, and the same fix, as the
// `inputs.file` declarations `ReleaseGateMatchesCiGateTest` needs in aimon-core.
tasks.test {
    inputs.dir(rootProject.file("docs")).withPropertyName("documentation")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
