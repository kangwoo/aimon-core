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

    // The model-capability binding contract ModelCapabilityConfigBindingTest subclasses. The starter's surface runs
    // the same one, which is why it lives in a module both can see rather than being copied into each.
    testImplementation(project(":aimon-llm-capability-testkit"))
}

// The CLI's tests run on the versions the CLI ships (#99). aimon.java-conventions gives every module
// spring-boot-starter-test, and here it raised three jars the distribution carries — logback-classic and
// logback-core 1.5.13 -> 1.5.34, and Quartz's jakarta.xml.bind-api 4.0.4 -> 4.0.5 — so the Logback these tests
// assert on was not the Logback `tasks.jar` below packs.
//
// Consistent resolution rather than naming the three: every version runtimeClasspath resolves becomes a strict
// constraint on both test classpaths, so a catalog or Quartz bump moves the tests with the distribution, and a jar
// raised the same way later is pulled back too. `dependencyInsight` names it ("by consistent resolution").
//
// The same strictness has a quiet side. A test library that asks for a newer version of a jar the CLI ships is
// given the shipped one without a message: resolution fails only if that library's own request is strict, and
// otherwise the only signal is a linkage error in :aimon-cli:test, and only if a test reaches the missing API.
// A test that needs the newer jar is a decision to take on purpose — this block will not announce it.
//
// Test classpaths only: `java { consistentResolution { useRuntimeClasspathVersions() } }` would constrain the main
// compile classpath too, where the conventions plugin's compileOnly org.jetbrains:annotations 26.1.0 cannot resolve
// against the 13.0 kotlin-stdlib ships. `shouldResolveConsistentlyWith` is @Incubating (Gradle 9.2.1). The record of
// every remaining test-classpath difference, and why each is accepted, is next to `junit` in
// gradle/libs.versions.toml.
configurations {
    val shipped = runtimeClasspath.get()
    testCompileClasspath { shouldResolveConsistentlyWith(shipped) }
    testRuntimeClasspath { shouldResolveConsistentlyWith(shipped) }
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
