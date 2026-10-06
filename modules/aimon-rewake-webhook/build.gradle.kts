plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Embedded HTTP server
    implementation(libs.javalin)

    // JSON parsing (already a transitive dep via core; declared explicitly for clarity)
    implementation(libs.jackson.databind)

    // Idempotency cache
    implementation(libs.caffeine)

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.okhttp)
    testRuntimeOnly(libs.logback.classic)
}

// This module's tests run on the versions it ships (#91's bar; backlog D-4, by the criterion D-2 set).
// spring-boot-starter-test, which aimon.java-conventions gives every module, brings spring-boot-starter, which asks
// for jakarta.annotation-api 3.0.0 while Jetty (jetty-ee10-annotations, via Javalin) brings the 2.1.1 this module
// ships (measured 2026-10-06). On testRuntimeClasspath it was the only version that differed; testCompileClasspath
// also had snakeyaml 2.6 against the shipped 2.7, as the two modules D-2 aligned did. The two annotation jars are the
// same package and the same types but for one: 3.0.0 drops jakarta.annotation.ManagedBean and is compiled for Java 11
// rather than 8.
//
// Unlike the pair D-2 aligned on aimon-knowledge-opensearch, the shipped side has a reader: of the 51 jars on
// runtimeClasspath, jetty-ee10-annotations references jakarta.annotation.* from six handler classes (Resource,
// Resources, PostConstruct, PreDestroy, RunAs, DeclareRoles). Whether a test here reaches them was not measured, and
// did not need to be — the cost of aligning lands on spring-boot-starter, which no test in this module loads (no test
// source names org.springframework), not on a library these tests run. The comment on the identical block in
// aimon-cli/build.gradle.kts says why consistent resolution rather than naming the jar, and what it stays quiet about:
// a test that starts Spring here would get 2.1.1 where Spring Boot 4 asks for 3.0.0, and no message.
//
// `shouldResolveConsistentlyWith` is @Incubating, still so in Gradle 9.8.0 (javap, 2026-10-06), and called on the
// terms at the end of aimon.java-conventions.gradle.kts. Removed or re-signed, this script stops compiling. Changed in
// what it does, the difference comes back and `./gradlew checkTestClasspathVersions` fails on it as unrecorded, since
// this module has no line in gradle/test-classpath-version-differences.txt; the command that shows it directly is
//     ./gradlew -q :aimon-rewake-webhook:dependencyInsight --configuration testRuntimeClasspath --dependency jakarta.annotation-api
// which answers 2.1.1 "by consistent resolution".
configurations {
    val shipped = runtimeClasspath.get()
    testCompileClasspath { shouldResolveConsistentlyWith(shipped) }
    testRuntimeClasspath { shouldResolveConsistentlyWith(shipped) }
}
