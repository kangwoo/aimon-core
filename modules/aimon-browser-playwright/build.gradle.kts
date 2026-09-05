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

// The browser binaries `playwrightTest` needs, installed as a build step rather than as a side effect of the
// first test.
//
// Playwright's Java bindings download browsers from inside `Playwright.create()` — DriverJar.installBrowsers()
// shells out to the bundled driver with a bare `install`, which fetches every browser marked installByDefault
// (chromium, chromium-headless-shell, firefox, webkit, ffmpeg). Measured 2026-09-05: 158s and 1.0 GB on a cold
// cache. PlaywrightLifecycleManager wraps that call in `future.get(30, SECONDS)`, so on a cold cache the download
// does not merely slow the tier down — it overruns the timeout and every test fails. Reproduced twice at 2m21s and
// 2m27s, both red. A half-populated cache is worse still: with chromium present and firefox missing, two of the
// four tests failed and two passed in the same run, which is a flaky gate rather than a slow one.
//
// So the download is hoisted out of the timeout and narrowed to what these tests actually launch. `chromium` alone
// is 94s and 520 MB on disk (280 MB over the wire) against 158s and 1.0 GB for the default set, and every test here
// goes through `pw.chromium()`. PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD then stops `Playwright.create()` from reaching for
// firefox and webkit at run time; a future test that wants one of those fails saying the browser is not installed,
// which is the honest error rather than a silent multi-minute fetch inside a 30-second window.
//
// Not up-to-date-checked on purpose: the cache lives outside the build directory (PLAYWRIGHT_BROWSERS_PATH, or
// ~/.cache/ms-playwright by default), so it is not an output this build owns. The driver answers in well under a
// second when the browsers are already there, which is cheap enough that guessing at staleness would cost more
// than it saves.
val testSourceSet = the<SourceSetContainer>()["test"]

val installPlaywrightBrowsers = tasks.register<JavaExec>("installPlaywrightBrowsers") {
    description = "Downloads the Chromium build playwrightTest needs. No-op when the browser cache is already warm."
    group = "verification"
    mainClass.set("com.microsoft.playwright.CLI")
    classpath = testSourceSet.runtimeClasspath
    args("install", "chromium")
}

// Opt-in task for running the playwright-tagged integration tests.
//
// `testClassesDirs` and `classpath` are not optional here, and their absence is why this task ran nothing at all
// from the initial commit until 2026-09-05. A bare `register<Test>` inherits neither from the `test` task, so the
// task had no candidate classes, reported NO-SOURCE and went green in 650ms -- the same green a passing run
// produces. Both `integrationTest` and `packagingTest` in aimon.java-conventions set these two lines; this one did
// not, and nothing compared them.
val playwrightTest = tasks.register<Test>("playwrightTest") {
    description = "Runs Playwright browser tests (JUnit @Tag(\"playwright\")); installs Chromium first if needed."
    group = "verification"
    dependsOn(installPlaywrightBrowsers)
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    // The install above already fetched what these tests launch. Without this, Playwright.create() would still try
    // to fetch firefox and webkit on every run against a chromium-only cache -- inside the 30s init timeout.
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    useJUnitPlatform {
        includeTags("playwright")
    }
    minHeapSize = "256m"
    maxHeapSize = "2g"
    shouldRunAfter(tasks.named("test"))
}

// aimon.java-conventions orders the report after `integrationTest` and `packagingTest`; it cannot name this tier,
// which exists only in this module. Ordering-only, like the other two: generating a report must not start
// requiring a browser.
tasks.withType<JacocoReportBase>().configureEach {
    mustRunAfter(playwrightTest)
}
