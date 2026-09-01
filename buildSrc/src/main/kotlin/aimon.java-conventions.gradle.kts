import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    checkstyle
    jacoco
    id("com.diffplug.spotless")
}

@Suppress("UnstableApiUsage")
val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    isFailOnError = false
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-parameters",
            "-Xlint:unchecked",
            "-Xlint:deprecation",
        ),
    )
    // Same clash the Test block below pins against, on the other side of the build. A worker daemon inherits
    // JAVA_TOOL_OPTIONS from the shell, and Gradle then passes its own smaller -Xmx on the command line — which
    // overrides the inherited -Xmx but NOT the inherited -Xms. `JAVA_TOOL_OPTIONS=-Xmx4g -Xms1g` therefore lands
    // as "-Xms1g with a max well below 1g" and the worker dies before javac starts:
    //     Error occurred during initialization of VM
    //     Initial heap size set to a larger value than the maximum heap size
    // Nothing is wrong with the source when this happens, which is what makes it expensive to diagnose. CI never
    // sees it (no such env var there), so it only ever hits a contributor's machine.
    options.isFork = true
    options.forkOptions.memoryInitialSize = "256m"
    options.forkOptions.memoryMaximumSize = "2g"
}

checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    // The gate is severity-based: checkstyle.xml sets severity=error, so any (non-baselined) violation fails via
    // maxErrors=0. maxWarnings is not a reliable knob in this Gradle version, so it is left at its default.
    isIgnoreFailures = false
}

tasks.named<Checkstyle>("checkstyleMain") {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Test sources are exempt from Checkstyle.
tasks.named<Checkstyle>("checkstyleTest") {
    enabled = false
}

configure<SpotlessExtension> {
    java {
        target("src/**/*.java")
        eclipse().configFile(rootProject.file("config/eclipse/eclipse-formatter.xml"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        importOrder("java", "javax", "jakarta", "org", "com", "")
        toggleOffOn()
    }

    format("misc") {
        target("*.gradle.kts", "*.md", ".gitignore")
        trimTrailingWhitespace()
        indentWithSpaces(2)
        endWithNewline()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Pin the test JVM heap so JAVA_TOOL_OPTIONS=-Xms… inherited from the user's shell does not
    // clash with a smaller Gradle-default Xmx.
    minHeapSize = "256m"
    maxHeapSize = "2g"
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    reports {
        html.required.set(true)
        junitXml.required.set(true)
    }
}

// Docker/Testcontainers-backed tests are annotated `@Tag("docker")`. The default `test` task — run by
// `build` / `check` — excludes them so unit tests stay fast and need no Docker daemon; the opt-in
// `integrationTest` task runs exactly those. Mirrors the `@Tag("playwright")` convention in
// aimon-browser-playwright. Modules with no docker-tagged tests simply run nothing in `integrationTest`.
//
// `@Tag("packaging")` is a third tier with the same shape and a different reason. Those tests build a fat jar
// and launch it in a child JVM, so they cost tens of seconds — which does not belong in the loop a developer runs
// on every save. Excluded from `test` for the same reason `docker` is, and given its own task for the same reason
// too. Repeated `useJUnitPlatform { }` calls accumulate into one options set, so both exclusions apply.
//
// Out of `test` is not the same as out of CI, and only one tier in this build is actually both. `packagingTest`
// is a step in the `build` job and a task in the release gate, like `integrationTest` before it; the tier that
// still runs nowhere is `playwrightTest` in aimon-browser-playwright.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("docker")
        excludeTags("packaging")
    }
}

val testSourceSet = the<SourceSetContainer>()["test"]
tasks.register<Test>("integrationTest") {
    description = "Runs Docker/Testcontainers integration tests (JUnit @Tag(\"docker\"))."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("docker")
    }
    shouldRunAfter(tasks.named("test"))
}

tasks.register<Test>("packagingTest") {
    description = "Runs fat-jar packaging tests (JUnit @Tag(\"packaging\")); builds and launches a real jar."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("packaging")
    }
    // A child JVM's stdout is the evidence these tests read (a WARN that must be emitted, a skill list that must
    // be complete). The parent's own streams are shown so a failure is diagnosable from the console alone.
    testLogging {
        showStandardStreams = true
    }
    shouldRunAfter(tasks.named("test"))
}

// The report reads every tier's execution data, not just `test`'s.
//
// The plugin's default is `test.exec` alone, and for seven published modules that is a report about tests that do
// not exist there: aimon-memory-{mongodb,postgres} measured 0.0%, aimon-session-postgres 6.2% line, gridfs 11.0%,
// s3 12.9% — every one of them a module whose tests are @Tag("docker") and therefore absent from `test`. A number
// that low reads as "untested" when the truth is "measured with the tests excluded", and it is the number any
// coverage floor would have been set against.
//
// Deliberately `mustRunAfter` and not `dependsOn` for the opt-in tiers: generating a report must not start
// requiring a Docker daemon or a fat jar. Ordering-only means `./gradlew test jacocoTestReport` still works with
// neither, and still reports 0.0% for those modules — correctly, because nothing measured them in that invocation
// — while `./gradlew test integrationTest jacocoTestReport` reports what the docker tier actually covers. Gradle 9
// requires the relationship to be declared either way: reading a file another task produces without saying so
// fails the build with "Declare an explicit dependency".
//
// Two consequences worth knowing. Exec data left over from an earlier run is folded in as well, so a report can
// describe a tier that did not run in this invocation; delete `build/jacoco/*.exec` when that matters. And in CI
// the tiers run as separate jobs with separate workspaces, so no single job can generate this report: `build` and
// `integration` each archive their own `.exec` and a third `coverage` job restores both before running
// `jacocoTestReport -x test`. That is what `dependsOn(test)` above buys besides correctness locally — the
// dependency is declared, so `-x test` can drop it and the report reads exec data no task in that job produced.
tasks.withType<JacocoReport>().configureEach {
    dependsOn(tasks.named("test"))
    mustRunAfter(tasks.named("integrationTest"), tasks.named("packagingTest"))
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

dependencies {
    "compileOnly"(libs.findLibrary("jetbrains-annotations").get())
    "compileOnly"(libs.findLibrary("lombok").get())
    "annotationProcessor"(libs.findLibrary("lombok").get())

    "testImplementation"(libs.findBundle("testing").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    "testImplementation"(libs.findLibrary("spring-boot-starter-test").get())
}
