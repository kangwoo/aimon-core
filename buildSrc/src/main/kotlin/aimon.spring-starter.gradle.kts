/**
 * Spring Boot starter authoring conventions.
 *
 * Applied on top of `aimon.java-conventions`. It exists so the Spring coordinates and the two annotation
 * processors are declared once: a starter that forgets `spring-boot-configuration-processor` still builds and
 * still works, it just silently loses IDE completion for every `aimon.*` key — the kind of omission that is
 * only noticed by a user, months later.
 */
plugins {
    id("aimon.java-conventions")
}

@Suppress("UnstableApiUsage")
val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    // `implementation`, not `api`: the consumer already has spring-boot-autoconfigure on its compile
    // classpath (every Boot app does, via spring-boot-starter), and re-exporting it would let us win version
    // mediation against the app's own Boot BOM. The facade exception in .claude/rules/code-style.md is about
    // re-exporting *AIMON* types, which is a different thing.
    "implementation"(libs.findLibrary("spring-boot-autoconfigure").get())

    // Generates META-INF/spring-configuration-metadata.json from the @ConfigurationProperties classes.
    "annotationProcessor"(libs.findLibrary("spring-boot-configuration-processor").get())
    // Generates the autoconfiguration condition index, so a context that excludes a slice never loads it.
    "annotationProcessor"(libs.findLibrary("spring-boot-autoconfigure-processor").get())
}

/*
 * Point the configuration processor at the hand-written metadata it has to merge, and make edits to that file
 * re-run compilation.
 *
 * Without this the processor still finds an `additional-spring-configuration-metadata.json` — but by a
 * heuristic: it derives `build/resources/main` from the class output directory and reads whatever is there.
 * That works only if `processResources` happened to run first, and nothing orders those two tasks against each
 * other. When it loses the race the hand-written hints are silently merged from the *previous* build's copy, so
 * a newly added value is simply missing from the metadata and the build still succeeds. Observed, not theorised:
 * adding a value to the file and running `compileJava` produced metadata without it.
 *
 * The Spring Boot Gradle plugin does exactly this, for exactly this reason. We do not apply that plugin here —
 * these are library modules, not applications, and it would also bring a bootJar task that makes no sense for a
 * starter — so the one piece of it that starter authoring needs is reproduced.
 */
val mainResources = the<SourceSetContainer>()["main"].resources

tasks.named<JavaCompile>("compileJava") {
    inputs.files(mainResources).withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("additionalConfigurationMetadata")
    options.compilerArgs.add("-Aorg.springframework.boot.configurationprocessor.additionalMetadataLocations="
            + mainResources.srcDirs.joinToString(","))
}
