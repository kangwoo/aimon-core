import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * One line of `gradle/test-classpath-version-differences.txt`: a version a module's tests run on that is not the
 * version the module ships, recorded with the reason it is left that way.
 */
data class RecordedDifference(
    val module: String,
    val coordinate: String,
    val shipped: String,
    val underTest: String,
    val reason: String,
    val lineNumber: Int,
) {
    /** The line as it is written in the file, so a failure can print the exact text to write. */
    fun line() = "$module | $coordinate | $shipped -> $underTest | $reason"
}

/**
 * Reads the recorded differences. The format is one difference per line, four fields separated by ` | `:
 *
 * ```
 * <module> | <group>:<name> | <shipped version> -> <version under test> | <why it is left that way>
 * ```
 *
 * `#` starts a comment line and blank lines are skipped. Anything else that does not parse fails the build with its line
 * number: a line this cannot read is a difference somebody believes is recorded and that nothing checks. It is not a
 * `.properties` file, unlike the two baselines beside it, because a coordinate holds a colon and a reason holds prose
 * — both of which `.properties` would need escaped, in a file people edit by copying the line a failure printed.
 */
object RecordedDifferences {

    const val PATH = "gradle/test-classpath-version-differences.txt"

    private val VERSIONS = Regex("""^(\S+)\s*->\s*(\S+)$""")

    fun read(file: File): List<RecordedDifference> {
        val out = mutableListOf<RecordedDifference>()
        val seen = mutableMapOf<Pair<String, String>, Int>()
        file.readLines().forEachIndexed { index, raw ->
            val number = index + 1
            val text = raw.trim()
            if (text.isEmpty() || text.startsWith("#")) {
                return@forEachIndexed
            }
            val fields = text.split("|", limit = 4).map { it.trim() }
            val versions = fields.getOrNull(2)?.let { VERSIONS.matchEntire(it) }
            if (fields.size != 4 || versions == null || fields[0].isEmpty() || !fields[1].contains(':')) {
                throw GradleException(
                    "$PATH:$number: cannot read this line. Write it as\n" +
                        "  <module> | <group>:<name> | <shipped version> -> <version under test> | <reason>",
                )
            }
            if (fields[3].isEmpty()) {
                throw GradleException(
                    "$PATH:$number: ${fields[0]} | ${fields[1]} has no reason. A difference nobody explained is " +
                        "one nobody can retire — say why the tests may run on a version the module does not ship.",
                )
            }
            val key = fields[0] to fields[1]
            seen.put(key, number)?.let { first ->
                throw GradleException("$PATH:$number: ${fields[0]} | ${fields[1]} is already recorded on line $first.")
            }
            out += RecordedDifference(
                fields[0], fields[1], versions.groupValues[1], versions.groupValues[2], fields[3], number,
            )
        }
        return out
    }
}

/**
 * Fails when a module's tests run on a different version of a library than the module ships, unless that difference is
 * recorded (backlog D-3).
 *
 * The bar is #91's: a module's tests run against the versions the module ships, unless the difference is a recorded
 * choice. Until this task the record was prose — a comment block in `gradle/libs.versions.toml` and two design
 * documents — and prose is a measurement of the day it was written. On the day this task first ran, one of its three
 * entries had already stopped being true (a Caffeine bump had levelled `error_prone_annotations` on the starter) and
 * four differences were on no list at all. Nothing had gone red for either.
 *
 * It compares `runtimeClasspath`, which is what the module ships, with `testRuntimeClasspath`, which is what its tests
 * run on: every external module (`ModuleComponentIdentifier`; project dependencies have no version to differ in) that
 * is on both, matched by `group:name`. Three things fail, and the last two are what keep the list from rotting:
 *
 *  * a difference that is not in the file;
 *  * a recorded difference whose versions moved — the reason was written about the versions on the line, so a bump on
 *    either side sends someone back to read it;
 *  * a recorded difference that is gone. "A baseline nobody shrinks is a baseline nobody reads."
 *
 * Each failure prints the line to write or delete.
 *
 * `testCompileClasspath` is not compared. It differs from `runtimeClasspath` in 43 places across 17 modules today
 * (2026-10-05), 32 of them a *lower* version than the one shipped, because a compile classpath resolves a smaller
 * graph than a runtime one — a difference of kind rather than a drift, and a 43-line list of exemptions would be
 * exactly the unread table this task exists to replace.
 *
 * The two graphs arrive as [ResolvedComponentResult] providers, which Gradle resolves when the task runs (or when it
 * stores the configuration cache), never while the build is being configured.
 */
abstract class TestClasspathVersionsTask : DefaultTask() {

    /** Root of the resolved `runtimeClasspath` graph — what the module ships. */
    @get:Input
    abstract val shipped: Property<ResolvedComponentResult>

    /** Root of the resolved `testRuntimeClasspath` graph — what the module's tests run on. */
    @get:Input
    abstract val underTest: Property<ResolvedComponentResult>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val recordedFile: RegularFileProperty

    @get:Input
    abstract val moduleName: Property<String>

    /** Every difference found, recorded or not, one per line; empty when the two classpaths agree. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val module = moduleName.get()
        val ships = versions(shipped.get())
        val tests = versions(underTest.get())
        val found = ships.keys.intersect(tests.keys).filter { ships[it] != tests[it] }.sorted()
        val recorded = RecordedDifferences.read(recordedFile.get().asFile)
            .filter { it.module == module }
            .associateBy { it.coordinate }

        report.get().asFile.apply { parentFile.mkdirs() }.writeText(
            found.joinToString("") { "$module | $it | ${ships[it]} -> ${tests[it]}\n" },
        )

        val problems = mutableListOf<String>()
        for (coordinate in found) {
            val now = "${ships[coordinate]} -> ${tests[coordinate]}"
            val entry = recorded[coordinate]
            if (entry == null) {
                problems += "$coordinate: ships ${ships[coordinate]}, tests run on ${tests[coordinate]}, and " +
                    "nothing records why.\n" +
                    "    Either resolve the test classpaths like runtimeClasspath (modules/aimon-cli/build.gradle.kts " +
                    "shows how), or add to ${RecordedDifferences.PATH}:\n" +
                    "      $module | $coordinate | $now | <why the tests may run on a version this module does " +
                    "not ship>"
            } else if (entry.shipped != ships[coordinate] || entry.underTest != tests[coordinate]) {
                problems += "$coordinate: recorded as ${entry.shipped} -> ${entry.underTest} " +
                    "(${RecordedDifferences.PATH}:${entry.lineNumber}), and is now $now.\n" +
                    "    The reason on that line was written about the old versions. Read it again; if it still " +
                    "holds, rewrite the line as:\n" +
                    "      ${entry.copy(shipped = ships.getValue(coordinate), underTest = tests.getValue(coordinate)).line()}"
            }
        }
        for (entry in recorded.values.sortedBy { it.lineNumber }) {
            if (entry.coordinate !in found) {
                val state = when {
                    entry.coordinate !in ships && entry.coordinate !in tests -> "it is on neither classpath"
                    entry.coordinate !in ships -> "it is not on runtimeClasspath"
                    entry.coordinate !in tests -> "it is not on testRuntimeClasspath"
                    else -> "both classpaths resolve ${ships[entry.coordinate]}"
                }
                problems += "${entry.coordinate}: recorded as ${entry.shipped} -> ${entry.underTest} " +
                    "(${RecordedDifferences.PATH}:${entry.lineNumber}), but $state.\n" +
                    "    Delete the line, so the list keeps saying something true."
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "$module: the versions its tests run on and the versions it ships differ from what is recorded.\n\n" +
                    problems.joinToString("\n\n") { "  $it" } +
                    "\n\n  Why a version got picked: ./gradlew :$module:dependencyInsight " +
                    "--configuration testRuntimeClasspath --dependency <name>",
            )
        }
    }

    /** `group:name` to version, for every external module in the graph under [root]. */
    private fun versions(root: ResolvedComponentResult): Map<String, String> {
        val seen = LinkedHashSet<ResolvedComponentResult>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val component = pending.removeLast()
            if (!seen.add(component)) {
                continue
            }
            for (dependency in component.dependencies) {
                if (dependency is ResolvedDependencyResult) {
                    pending.add(dependency.selected)
                }
            }
        }
        return seen.mapNotNull { it.id as? ModuleComponentIdentifier }.associate { "${it.group}:${it.module}" to it.version }
    }
}

/**
 * The half of the stale-entry rule no module can see: a recorded difference that names a module this build does not
 * have. Each module's [TestClasspathVersionsTask] reads only its own lines, so a line for a module that was renamed or
 * removed — or misspelled — would be read by none of them and sit in the file as though it were checked.
 */
abstract class RecordedDifferenceModulesTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val recordedFile: RegularFileProperty

    /** The modules that register a [TestClasspathVersionsTask]. */
    @get:Input
    abstract val modules: SetProperty<String>

    @TaskAction
    fun check() {
        val known = modules.get()
        val orphans = RecordedDifferences.read(recordedFile.get().asFile).filter { it.module !in known }
        if (orphans.isNotEmpty()) {
            throw GradleException(
                "${RecordedDifferences.PATH} records a difference for a module this build does not check:\n" +
                    orphans.joinToString("\n") { "  line ${it.lineNumber}: ${it.module} | ${it.coordinate}" } +
                    "\nNo task reads those lines. Fix the module name, or delete them.",
            )
        }
    }
}
