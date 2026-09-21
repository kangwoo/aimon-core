/*
 * Runs the PeerMemory contract suite on exactly the JUnit floor `aimon-memory-testkit` publishes.
 *
 * WHY THIS EXISTS. aimon-memory-testkit declares `api(platform(libs.junit.bom))`, so the version in the
 * catalog's `junit` entry is not just what this build happens to run — it is the constraint the testkit puts on
 * every consumer that depends on it, including the one in another repository. Where the suite runs, a
 * consumer's newer JUnit usually wins by conflict resolution, so nothing in an ordinary build ever exercises
 * the floor itself. This pins it and runs the suite there.
 *
 * WHEN TO RUN IT. When the catalog's `junit` entry changes, or when the contract suite changes. The catalog
 * note beside that entry says the same thing and records the result of the last run.
 *
 * NOT IN THE BUILD, ON PURPOSE. No task depends on this and CI does not call it. Pinning JUnit for one suite is
 * not worth a permanent tier; the claim it checks changes about once a year. It is stored here rather than
 * retyped because it had lapsed twice by the time anyone noticed — the cost of re-deriving the pin was the
 * reason, not the cost of running it.
 *
 *   ./gradlew -I scripts/verify-junit-floor.init.gradle.kts -PjunitFloor=<the catalog's junit value> \
 *       :aimon-core:printJunitJars \
 *       :aimon-core:test --tests 'at.aimon.core.memory.StoreBackedPeerMemoryContractTest' --rerun
 *
 * `printJunitJars` is half the verification and not decoration: it shows the pin actually took, which a green
 * test run on its own does not. Read it before believing the result.
 */

// Required rather than defaulted. A default here would be a second copy of the floor, free to drift from the
// catalog's — which is the exact failure this verification exists to catch, reproduced in the tool that checks
// for it. Passing it by hand also forces whoever runs this to look the number up.
val floor: String = providers.gradleProperty("junitFloor").orNull
        ?: error("Pass -PjunitFloor=<version>, the `junit` entry in gradle/libs.versions.toml. "
                + "There is deliberately no default: a hardcoded one could drift from the catalog.")

gradle.projectsEvaluated {
    // This hook also fires for `buildSrc`, which is a separate build with its own root and no :aimon-core.
    // Skip it by name rather than by catching the miss below: a missing :aimon-core in the *real* build means
    // this was run from the wrong directory, and that should say so rather than pass quietly.
    if (rootProject.name != "aimon-core") {
        return@projectsEvaluated
    }

    val core = rootProject.findProject(":aimon-core")
            ?: error("No :aimon-core project — run this from the repository root.")

    // enforcedPlatform, not platform: a plain platform is a constraint other dependencies can still win
    // against, and losing the pin silently is the one outcome that would make this run meaningless.
    core.dependencies.add("testRuntimeOnly", core.dependencies.enforcedPlatform("org.junit:junit-bom:$floor"))

    core.tasks.register("printJunitJars") {
        val classpath = core.the<SourceSetContainer>()["test"].runtimeClasspath
        doLast {
            println("--- JUnit jars on :aimon-core testRuntimeClasspath, floor pinned to $floor ---")
            classpath.files.map { it.name }.filter { it.startsWith("junit-") }.sorted()
                    .forEach { println("  $it") }
        }
    }
}
