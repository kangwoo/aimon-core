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
