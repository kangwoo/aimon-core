import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavaPlatform
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish")
}

configure<MavenPublishBaseExtension> {
    // Central Portal is the only destination the plugin still knows. Releasing stays manual (the default,
    // `mavenCentralAutomaticPublishing=false`), and the upload now waits until the Portal has *validated* the
    // deployment — so a bundle Central rejects fails `scripts/release.sh` before it commits or tags anything.
    publishToMavenCentral()
    signAllPublications()
}

// Two kinds of thing are published from this build, and what a publication contains depends on which one
// this is. They are mutually exclusive by construction — Gradle refuses `java-platform` alongside
// `java`/`java-library` — so this is a choice, not a merge. It is written with `plugins.withId` rather than
// an `if` so that the order of a module's own `plugins { }` block cannot change the answer.
plugins.withId("java-library") {
    configure<MavenPublishBaseExtension> {
        configure(
            JavaLibrary(
                javadocJar = JavadocJar.Javadoc(),
                sourcesJar = SourcesJar.Sources(),
            ),
        )
    }
}

// What is missing from the published javadoc. Registered here rather than in aimon.java-conventions because
// "public API" is a property of what ships: the CLI, the samples and the unpublished testkits are not measured.
// Within a module, `*.impl` is left out — api-stability.md §2 says that is internal, and a count that included it
// would measure the wrong thing. Report-only; see JavadocCoverageTask.
plugins.withId("java-library") {
    val main = the<SourceSetContainer>().named("main")
    // Resolved here, not inside the task block: in there `the<...>()` looks at the task's own extensions.
    val tool = the<JavaToolchainService>().javadocToolFor(the<JavaPluginExtension>().toolchain)
    tasks.register<JavadocCoverageTask>("javadocCoverage") {
        description = "Count public API elements without javadoc (report only, never fails)"
        group = "documentation"
        sources.from(main.map { it.allJava.matching { exclude("**/impl/**") } })
        classpath.from(main.map { it.compileClasspath }, main.map { it.output })
        javadocTool.set(tool)
        reportDir.set(layout.buildDirectory.dir("reports/javadoc-coverage"))
        rootDir.set(rootProject.layout.projectDirectory)
    }
}

plugins.withId("java-platform") {
    configure<MavenPublishBaseExtension> {
        // A platform has no code, so there is no javadoc and no sources to attach: the POM is the whole
        // artifact. It still has to be signed, which the block above already arranges.
        configure(JavaPlatform())
    }
}

// Gradle writes a checksum next to every file it publishes, including each `.asc` signature and a SHA256/SHA512
// pair Central never reads. Sonatype names these as a Gradle-shaped way file counts inflate
// (https://central.sonatype.org/publish/reducing-publishing-usage/). The plugin leaves them out of the
// Central bundle by default since 0.37 — only md5/sha1, and none for signatures — so nothing here has to.
// `mavenCentralChecksums` / `mavenCentralExcludeSignatureChecksums` are the knobs if that ever needs to change.

// Applying this plugin to anything else would produce a publication with nothing in it, and the first
// evidence of that would be an empty artifact on Central. Say it here instead.
afterEvaluate {
    check(plugins.hasPlugin("java-library") || plugins.hasPlugin("java-platform")) {
        "Project '$path' applies aimon.publishable but is neither a java-library nor a java-platform, " +
            "so there is nothing for it to publish."
    }
}
