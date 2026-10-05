plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Quartz Scheduler
    implementation(libs.quartz)

    // Connection pool for the JDBC job store. Quartz 2.5 moved c3p0 and HikariCP to `provided`, so
    // a pool no longer arrives with the scheduler -- whoever configures a JDBC job store supplies
    // one. HikariCP because it is already this repository's pool (aimon-session-postgres); c3p0,
    // which 2.3.2 happened to drag in, appears nowhere else here.
    implementation(libs.hikari)

    // Logging
    implementation(libs.slf4j.api)

    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.logback.classic)

    // JDBC drivers, tests only. The JDBC job store tests name a driver class, and HikariCP loads it
    // while the pool is being configured rather than at first use the way c3p0 did -- so without
    // these the scheduler cannot be built at all. Their absence used to go unnoticed, which is the
    // same blind spot the `dataSourceClass` test's comment already records.
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.postgresql)
}

// This module's tests run on the versions it ships (#91's bar; backlog D-2). spring-boot-starter-test, which
// aimon.java-conventions gives every module, asks for jakarta.xml.bind-api 4.0.5 while Quartz brings the 4.0.4 this
// module ships — a jar of code (JAXBContext, DatatypeConverter), not annotations — and snakeyaml 2.6 onto the test
// compile classpath against the shipped 2.7 (measured 2026-10-05). Same source and same jar as aimon-cli, so the same
// remedy: every version runtimeClasspath resolves becomes a strict constraint on both test classpaths. Why that
// rather than naming the jars, and what it stays quiet about, is the comment on the identical block in
// aimon-cli/build.gradle.kts.
//
// `shouldResolveConsistentlyWith` is @Incubating, still so in Gradle 9.8.0 (javap, 2026-10-05), and called on the terms
// at the end of aimon.java-conventions.gradle.kts. Removed or re-signed, this script stops compiling; changed in what
// it does, it may fail nothing, and
//     ./gradlew -q :aimon-scheduling-quartz:dependencyInsight --configuration testRuntimeClasspath --dependency jakarta.xml.bind-api
// would stop answering 4.0.4 "by consistent resolution" — run it after a Gradle upgrade until backlog D-3's check exists.
configurations {
    val shipped = runtimeClasspath.get()
    testCompileClasspath { shouldResolveConsistentlyWith(shipped) }
    testRuntimeClasspath { shouldResolveConsistentlyWith(shipped) }
}
