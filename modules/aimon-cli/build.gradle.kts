plugins {
    id("aimon.java-conventions")
    application
}

application {
    mainClass.set("at.aimon.cli.AimonCli")
}

dependencies {
    // Core modules
    implementation(project(":aimon-core"))
    // Framework-neutral assembly: AgentSetupFactory describes the stack as an AimonStackSpec and lets
    // AimonStackBuilder wire it, so the CLI is left with only its terminal-bound decorations.
    implementation(project(":aimon-bootstrap"))
    implementation(project(":aimon-llm-anthropic"))
    implementation(project(":aimon-llm-openai"))
    implementation(project(":aimon-scheduling-quartz"))
    // Phase 5 GraalJS workflow frontend (opt-in via cli.enableWorkflowJs). Isolates the org.graalvm deps behind
    // this module; the CLI is the assembly layer that registers its WorkflowJs tool.
    implementation(project(":aimon-workflow-graaljs"))

    // Quartz Scheduler — needed directly so the CLI can build a dedicated dreamer scheduler instance
    implementation(libs.quartz)

    // CLI Framework
    implementation(libs.picocli)
    annotationProcessor(libs.picocli.codegen)

    // Configuration parsing
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)

    // Terminal features
    implementation(libs.jline)
    implementation(libs.jansi)
}

// Create executable JAR with all dependencies
tasks.jar {
    dependsOn(configurations.runtimeClasspath)

    manifest {
        attributes["Main-Class"] = "at.aimon.cli.AimonCli"
    }

    // Include all runtime dependencies
    from(
        configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        },
    )

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Create distribution with scripts
distributions {
    main {
        contents {
            from("src/main/resources") {
                into("config")
                include("default-config.yaml")
            }
        }
    }
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 21
}
