import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

// The bill of materials: one coordinate an application can import to get every AIMON module at the same
// version, instead of repeating that version on each of the twenty-odd dependency lines.
//
// It is a `java-platform`, not a library, and it therefore cannot apply `aimon.java-conventions` —
// `java-platform` and `java-library` are mutually exclusive in Gradle. That is why the root aggregators
// (`format`, `checkStyle`, `checkAll`) skip platform projects, and why the verification this module needs
// is a task of its own rather than a JUnit test.
plugins {
    `java-platform`
    id("aimon.publishable")
}

// ── what the BOM manages ────────────────────────────────────────────────────────────────────────────
//
// Only AIMON's own modules. Pinning third-party versions here would look helpful and behave badly: Gradle
// treats a `platform()` version as a recommendation, but Maven's `dependencyManagement` and
// `enforcedPlatform` treat it as an override, so a Maven application importing this BOM would have its
// Spring Boot-managed logback / lettuce / mongo versions silently replaced by whatever this repository
// happened to build against (§7.5). A first-party-only BOM cannot do that to anyone.
//
// The list is derived, never typed. A hand-maintained BOM is a BOM that is one release behind — the
// failure `langchain4j-spring-bom` demonstrates, sitting at 1.0.0-beta5 while its starters moved on
// (§6 D11). Deriving it also makes "lockstep with the release" true by construction: every constraint
// carries this project's version, and that comes from the root `VERSION_NAME`.
dependencies {
    constraints {
        publishedProjects().forEach { api(it) }
    }
}

/**
 * Every sibling that actually publishes, in a stable order.
 *
 * `evaluationDependsOn` is what makes this honest rather than accidental. Gradle configures projects in
 * path order, so when this script runs, most siblings have not applied their plugins yet and `hasPlugin`
 * would answer "no" for everything sorting after `aimon-bom` — which is nearly all of them. Forcing each
 * sibling to be evaluated first is the supported way to ask a question about its configuration.
 */
fun publishedProjects(): List<Project> =
    rootProject.subprojects
        .filter { it.path != project.path }
        .map { evaluationDependsOn(it.path) }
        .filter { it.plugins.hasPlugin("com.vanniktech.maven.publish") }
        .sortedBy { it.name }

// ── verification ────────────────────────────────────────────────────────────────────────────────────

/**
 * Modules that declare a published coordinate, according to their own `gradle.properties`.
 *
 * This is deliberately a *different* signal from the one that builds the constraints above. If the BOM
 * checked itself against the plugin list it derived itself from, it would only ever confirm that a list
 * equals itself. Reading the declared coordinates instead makes the check catch the two ways a module can
 * be half-published: applying the publish plugin without declaring a coordinate (the POM then goes to
 * Central with no `<name>` and no `<description>`, which it rejects), or declaring one and never applying
 * the plugin (a coordinate that looks real in the tree and is never produced).
 */
fun declaredCoordinates(): Map<String, Map<String, String>> =
    rootProject.subprojects
        .filter { it.path != project.path }
        .mapNotNull { module ->
            val properties = module.file("gradle.properties")
            if (!properties.exists()) {
                return@mapNotNull null
            }
            val declared = properties.readLines()
                .map { it.trim() }
                .filter { it.startsWith("POM_") && it.contains("=") }
                .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
            if (declared.containsKey("POM_ARTIFACT_ID")) module.name to declared else null
        }
        .toMap()

val requiredPomProperties = listOf("POM_ARTIFACT_ID", "POM_NAME", "POM_DESCRIPTION")

val generatePom = tasks.named("generatePomFileForMavenPublication")

val verifyBom by tasks.registering {
    description = "Checks that the BOM manages exactly the modules this build publishes, and nothing else."
    group = "verification"
    dependsOn(generatePom)

    val pomFile = layout.buildDirectory.file("publications/maven/pom-default.xml")
    val published = publishedProjects().map { it.name }.toSet()
    val declared = declaredCoordinates()
    val expectedGroup = project.group.toString()
    val expectedVersion = project.version.toString()

    inputs.file(pomFile)
    outputs.upToDateWhen { false }

    doLast {
        val problems = mutableListOf<String>()

        // 1. The two signals must agree: publishing something is declaring a coordinate for it.
        (published - declared.keys).sorted().forEach {
            problems += "module '$it' applies aimon.publishable but declares no POM_ARTIFACT_ID in its " +
                "gradle.properties — its POM would reach Central with no name and no description"
        }
        (declared.keys - published).sorted().forEach {
            problems += "module '$it' declares a published coordinate but does not apply " +
                "aimon.publishable, so that coordinate is never produced"
        }
        declared.filterKeys { it in published }.forEach { (module, properties) ->
            requiredPomProperties.filterNot { properties.containsKey(it) }
                .forEach { problems += "module '$module' is published but declares no $it" }
        }

        // 2. The published POM must manage exactly those modules, at this version, and depend on nothing.
        //
        // Only reached when the coordinates above agree. A module that publishes without declaring a
        // coordinate is missing from both sides of the comparison, so running it anyway would report
        // "this build publishes ⟨list without that module⟩" — which is not true, and points away from
        // the one-line fix the first check already named.
        val coordinatesDisagree = problems.isNotEmpty()

        val pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile.get().asFile)
        val root = pom.documentElement

        if (childElement(root, "dependencies") != null) {
            problems += "the BOM's POM has a <dependencies> block; a platform must only manage versions, " +
                "never put anything on a consumer's classpath"
        }

        val managed = childElement(childElement(root, "dependencyManagement"), "dependencies")
            ?.let { elements(it, "dependency") }
            .orEmpty()
            .map { Triple(text(it, "groupId"), text(it, "artifactId"), text(it, "version")) }

        val expected = published.mapNotNull { declared[it]?.get("POM_ARTIFACT_ID") }.toSortedSet()
        val actual = managed.map { it.second }.toSortedSet()
        if (!coordinatesDisagree && actual != expected) {
            problems += "the BOM manages $actual but this build publishes $expected"
        }
        managed.filterNot { it.first == expectedGroup && it.third == expectedVersion }.forEach {
            problems += "managed entry '${it.first}:${it.second}:${it.third}' is not " +
                "$expectedGroup:*:$expectedVersion — the BOM is not in lockstep with the release"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString(
                    prefix = "The BOM does not match what this build publishes:\n  - ",
                    separator = "\n  - ",
                ),
            )
        }
        logger.lifecycle("aimon-bom manages ${managed.size} modules at $expectedGroup:*:$expectedVersion")
    }
}

// Never publish a BOM that has not been checked. `verifyBom` is on `checkAll`, and since P2-2 closed
// the release script gates on `checkAll` too — but that is not enough to drop this hook. release.sh
// runs the gate and the publish as two SEPARATE Gradle invocations, so a green gate does not prove
// verifyBom saw the tree being published, and a hand-run
// `./gradlew publishAllPublicationsToMavenCentralRepository` bypasses the gate entirely. A wrong BOM
// is only discoverable after it is permanent, so the check rides the publish itself.
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyBom)
}

fun childElement(parent: Element?, name: String): Element? =
    parent?.childNodes?.let { nodes ->
        (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().firstOrNull {
            it.tagName == name
        }
    }

fun elements(parent: Element, name: String): List<Element> =
    parent.childNodes.let { nodes ->
        (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter {
            it.tagName == name
        }
    }

fun text(parent: Element, name: String): String = childElement(parent, name)?.textContent.orEmpty()
