import org.springframework.boot.gradle.tasks.bundling.BootJar

// The only module in the tree that is an *application* rather than a library — and the only place a claim about
// packaging can be tested at all, because a fat jar is a thing this build produces exactly once.
//
// aimon.spring-starter deliberately withholds the Boot plugin from the starter module ("these are library
// modules, not applications, and it would also bring a bootJar task that makes no sense for a starter"). Here
// the bootJar task is the entire point, so the plugin is applied directly. It is not `aimon.publishable`:
// samples are proof, not product.
plugins {
    id("aimon.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

val sampleMainClass = "at.aimon.sample.SampleApplication"

// Resolved here rather than inside the task blocks below: inside them `the<…>()` looks the extension up on the
// task, which has none.
val mainSourceSet = the<SourceSetContainer>()["main"]

dependencies {
    implementation(project(":aimon-spring-boot-starter"))
    implementation(libs.spring.boot.starter.web)

    // The two sample skill jars. Nothing in this module's code names them; they contribute through the class
    // path alone, which is the whole assertion the packaging tier makes. runtimeOnly rather than implementation
    // for the same reason — a compile-scope dependency would let a future edit reference them by type and quietly
    // turn "found on the class path" into "linked against".
    runtimeOnly(project(":aimon-sample-skills-alpha"))
    runtimeOnly(project(":aimon-sample-skills-beta"))

    // Quartz, for the `live` profile's scheduling axis. The starter holds both of these compileOnly on purpose —
    // an application that depends on AIMON should not receive a scheduler it never asked for — so an application
    // that wants `aimon.scheduling.backend=quartz` has to name them itself. Doing exactly that here is part of
    // what the profile verifies: the slice's @ConditionalOnClass reaction to a classpath an integrator assembled.
    //
    // Quartz is named a second time because aimon-scheduling-quartz declares it `implementation` and so does not
    // re-export it. Both are runtimeOnly: no code in this module refers to either, and compile scope would let a
    // future edit quietly turn "selected by a property" into "linked against".
    //
    // They stay on the classpath under the default profile too, where `backend=none` leaves the slice inactive.
    // That costs the fat jar some size and buys the packaging tier a more honest class path than one assembled
    // to be minimal.
    runtimeOnly(project(":aimon-scheduling-quartz"))
    runtimeOnly(libs.quartz)

    // Jackson 3, for the packaging helper that parses the running app's JSON replies. Boot 4 made Jackson 3
    // (`tools.jackson`) the default and spring-boot-starter-web above no longer carries Jackson 2, so the helper
    // moved packages with it. Declared rather than taken transitively from that starter, for the reason the
    // starter module's build script gives about compiling against a transitive artifact — through the catalog's
    // `jackson3-databind` alias, which carries no version, so the coordinate lives where every other coordinate
    // in this build lives while Boot's dependency management still decides the version (an application takes
    // Jackson's version from Boot, not from the catalog). The catalog's `jackson` ref stays where it is: it is
    // Jackson 2, which aimon-core still ships.
    //
    // Both majors therefore run in this JVM at once — AIMON's Jackson 2 inside the framework, Boot's Jackson 3
    // in the web layer — which is the arrangement every Boot 4 application integrating AIMON will have. The
    // packaging tier is where that stops being a claim: the app starts, serves and is introspected over HTTP.
    testImplementation(libs.jackson3.databind)

    // Deliberately no explicit logback dependency. spring-boot-starter-web already brings one through
    // spring-boot-starter-logging, and pinning logback-classic from the version catalog while
    // spring-dependency-management keeps managing logback-core produced a split pair (classic 1.5.13 against
    // core 1.5.34) that fails at startup with NoSuchMethodError. A sample app takes its logging stack from Boot,
    // like the applications it stands in for.
    // It takes the version from the catalog rather than from Boot, though: see `logback.version` below.
}

// Spring Boot 4.1.1 manages Logback 1.5.38, inside CVE-2026-19880 (logback-classic up to 1.6.2; needs a
// SiftingAppender whose MDC discriminator an attacker influences, and this module has no Logback configuration file),
// and no Spring Boot line manages a Logback outside it (#129). So the sample does what an application does to take a
// fix Boot does not manage yet: it overrides the property Boot's dependency management reads for logback-classic and
// logback-core, with the catalog's `logback` — the version aimon-cli ships. One property moves both jars; naming one
// of them is what split the pair above. The fat jar starts on it, on Boot's default logging setup (packagingTest,
// re-measured on Boot 4.1.1). A catalog bump moves this with it, and `packagingTest` is what fails if that Logback
// stops starting under Boot — though not for a logback-spring.xml, whose Boot extensions nothing here loads. If Boot
// ever manages a newer Logback than the catalog, this line holds the sample below Boot's; the catalog note says to
// keep `logback` at or above it.
//
// The Boot 4 move re-checked the mechanism rather than assuming it: `dependencyInsight --dependency
// ch.qos.logback:logback-core` still reports `1.6.3 (selected by rule)`, and logback-classic still shows
// `1.5.38 -> 1.6.3`, so Boot 4's dependency management reads this property exactly as Boot 3's did. The older of
// the two CVEs above (CVE-2026-13006, logback-core up to 1.5.36) is no longer reachable by Boot's own version
// either, since 1.5.38 is past it; it is dropped from the sentence rather than kept as a reason that has expired.
extra["logback.version"] = libs.versions.logback.get()

tasks.named<BootJar>("bootJar") {
    mainClass.set(sampleMainClass)
}

// There was a second fat jar here, built by Boot's pre-3.2 loader: Boot 3.2 replaced
// `jar:file:…!/BOOT-INF/lib/x.jar!/…` with the `jar:nested:` scheme, AIMON reads skill trees by casting the
// resource URL's connection to JarURLConnection, and building both jars was the only way to find out rather
// than assume that the cast held under either scheme.
//
// It is gone with the Boot 4 baseline (spring-boot-starter.md D6), because the thing it built no longer exists:
// Boot 4 removed the classic loader. `org.springframework.boot.loader.tools.LoaderImplementation` is not in
// spring-boot-loader-tools 4.1.1 and `BootJar` has no `loaderImplementation` property (both measured). There is
// no version of this task that compiles.
//
// FatJarPackagingTest's class javadoc records what that costs — the cast is now exercised under one scheme, and
// an application still on Boot 3 packages with a loader nothing in this build launches.

// Packaging tests launch the jar in a child JVM, so they cannot run until it exists and they need to be told
// where it landed. Both facts are wiring, and wiring belongs here rather than in a test that guesses paths
// relative to its own working directory.
tasks.named<Test>("packagingTest") {
    dependsOn(tasks.named("bootJar"))
    systemProperty("aimon.sample.bootJar", tasks.named<BootJar>("bootJar").flatMap { it.archiveFile }.get().asFile.path)
    // The exploded comparison runs the same application off a plain directory class path — the layout `bootRun`,
    // an IDE and every unit test use. Passing the runtime class path verbatim is what makes the two runs differ
    // in packaging and in nothing else.
    systemProperty("aimon.sample.explodedClasspath", mainSourceSet.runtimeClasspath.asPath)
}
