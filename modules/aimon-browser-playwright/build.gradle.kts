plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // Browser automation
    implementation(libs.playwright)

    // Logging — a library logs through the SLF4J API and must not choose the binding for its consumers: a
    // runtimeOnly binding lands in the published POM and collides with a host app's own provider (log4j2, ...).
    implementation(libs.slf4j.api)
    testRuntimeOnly(libs.logback.classic)

    // JSON processing
    implementation(libs.jackson.databind)
}

// Default test task excludes the playwright-tagged tests so unit tests remain fast and headless.
tasks.test {
    useJUnitPlatform {
        excludeTags("playwright")
    }
}

// Opt-in task for running the playwright-tagged integration tests.
tasks.register<Test>("playwrightTest") {
    useJUnitPlatform {
        includeTags("playwright")
    }
    minHeapSize = "256m"
    maxHeapSize = "2g"
}
