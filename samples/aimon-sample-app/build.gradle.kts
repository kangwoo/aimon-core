import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.loader.tools.LoaderImplementation

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

    // Deliberately no explicit logback dependency. spring-boot-starter-web already brings one through
    // spring-boot-starter-logging, and pinning logback-classic from the version catalog while
    // spring-dependency-management keeps managing logback-core produced a split pair (classic 1.5.13 against
    // core 1.5.34) that fails at startup with NoSuchMethodError. A sample app takes its logging stack from Boot,
    // like the applications it stands in for.
    // It takes the version from the catalog rather than from Boot, though: see `logback.version` below.
}

// Spring Boot 3.5.16 manages Logback 1.5.34, inside CVE-2026-13006 (logback-core up to 1.5.36; needs Janino, which this
// class path does not carry) and CVE-2026-19880 (logback-classic up to 1.6.2; needs a SiftingAppender whose MDC
// discriminator an attacker influences, and this module has no Logback configuration file), and no Spring Boot line
// manages a Logback outside both (#129). So the sample does what an application does to take a fix Boot does not
// manage yet: it overrides the property Boot's dependency management reads for logback-classic and logback-core, with
// the catalog's `logback` — the version aimon-cli ships. One property moves both jars; naming one of them is what split
// the pair above. Both fat jars start on it, on Boot's default logging setup (packagingTest, 2026-09-11). A catalog
// bump moves this with it, and `packagingTest` is what fails if that Logback stops starting under Boot — though not
// for a logback-spring.xml, whose Boot extensions nothing here loads. If Boot ever manages a newer Logback than the
// catalog, this line holds the sample below Boot's; the catalog note says to keep `logback` at or above it.
extra["logback.version"] = libs.versions.logback.get()

tasks.named<BootJar>("bootJar") {
    mainClass.set(sampleMainClass)
}

// A second fat jar built by Boot's pre-3.2 loader. Boot 3.2 replaced `jar:file:…!/BOOT-INF/lib/x.jar!/…` with
// the `jar:nested:` scheme, and AIMON reads skill trees by casting the resource URL's connection to
// JarURLConnection and enumerating entries — a cast that either loader may or may not satisfy. Building both and
// running the same assertions against each is the only way to find out rather than assume.
val bootJarClassic by tasks.registering(BootJar::class) {
    description = "Builds the fat jar with Boot's classic (pre-3.2) loader, for the packaging-tier comparison."
    group = "build"
    archiveClassifier.set("classic")
    loaderImplementation.set(LoaderImplementation.CLASSIC)
    mainClass.set(sampleMainClass)
    // The Boot plugin fills this in for the bootJar task it registers itself, not for one registered by hand.
    targetJavaVersion.set(JavaVersion.VERSION_17)
    classpath(
        mainSourceSet.output,
        configurations.named("runtimeClasspath"),
    )
}

// Packaging tests launch the jars in a child JVM, so they cannot run until the jars exist and they need to be
// told where the jars landed. Both facts are wiring, and wiring belongs here rather than in a test that guesses
// paths relative to its own working directory.
tasks.named<Test>("packagingTest") {
    dependsOn(tasks.named("bootJar"), bootJarClassic)
    systemProperty("aimon.sample.bootJar", tasks.named<BootJar>("bootJar").flatMap { it.archiveFile }.get().asFile.path)
    systemProperty("aimon.sample.bootJarClassic", bootJarClassic.flatMap { it.archiveFile }.get().asFile.path)
    // The exploded comparison runs the same application off a plain directory class path — the layout `bootRun`,
    // an IDE and every unit test use. Passing the runtime class path verbatim is what makes the two runs differ
    // in packaging and in nothing else.
    systemProperty("aimon.sample.explodedClasspath", mainSourceSet.runtimeClasspath.asPath)
}
