plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // OpenSearch Java client (3.x ships its own Apache HttpClient 5 transport — no low-level rest client)
    implementation(libs.opensearch.client)

    // Logging
    implementation(libs.slf4j.api)
}

// This module's tests run on the versions it ships (#91's bar; backlog D-2). spring-boot-starter-test, which
// aimon.java-conventions gives every module, asks for jakarta.annotation-api 3.0.0 (jakarta.annotation.*) while
// opensearch-java brings the 1.3.5 this module ships (javax.annotation.*), and snakeyaml 2.6 onto the test compile
// classpath against the shipped 2.7 (measured 2026-10-05). The annotation jar is inert on both sides today: no class
// on runtimeClasspath references a class of 1.3.5 — opensearch-java's javax.annotation references are JSR-305's
// Nonnull/Nullable/CheckForNull, which neither version contains — and on the test side only spring-context names
// 3.0.0, which no test here loads. Aligned anyway, with the same remedy as aimon-cli, so that the next opensearch-java
// or Spring Boot bump moves these tests with what ships instead of opening a gap nothing reports. The comment on the
// identical block in aimon-cli/build.gradle.kts says why consistent resolution rather than naming the jar, and what it
// stays quiet about: a test that needs Spring here would get 1.3.5, without jakarta.annotation.*, and no message.
//
// `shouldResolveConsistentlyWith` is @Incubating, still so in Gradle 9.8.0 (javap, 2026-10-05), and called on the terms
// at the end of aimon.java-conventions.gradle.kts. Removed or re-signed, this script stops compiling; changed in what
// it does, it may fail nothing, and
//     ./gradlew -q :aimon-knowledge-opensearch:dependencyInsight --configuration testRuntimeClasspath --dependency jakarta.annotation-api
// would stop answering 1.3.5 "by consistent resolution" — run it after a Gradle upgrade until backlog D-3's check exists.
configurations {
    val shipped = runtimeClasspath.get()
    testCompileClasspath { shouldResolveConsistentlyWith(shipped) }
    testRuntimeClasspath { shouldResolveConsistentlyWith(shipped) }
}
