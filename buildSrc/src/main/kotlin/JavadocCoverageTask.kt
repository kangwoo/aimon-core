import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavadocTool
import org.gradle.process.ExecOperations

/**
 * Counts the public API elements that have no javadoc, without failing on them.
 *
 * The published `javadoc` task runs with `-Xdoclint:none` so that a jar is never blocked on a comment. That also
 * means the build cannot say how far the code is from the `1.0` condition "javadoc on every public API"
 * (`docs/project/api-stability.md` §6). This task is the measurement: it runs the toolchain's `javadoc` over the
 * module's public sources with only doclint's `missing` group switched on, keeps every warning in
 * `report/warnings.txt`, and writes the per-kind counts to `report/summary.properties`.
 *
 * It is a separate task rather than a flag on `javadoc` for two reasons. Gradle's `Javadoc` task does not hand back
 * the tool's output, and counting is the whole point here. And the published jar must not change because someone
 * wanted a number.
 *
 * Report-only by design: it never fails on a warning. Whether the count should become a ratchet is a separate
 * decision.
 */
@CacheableTask
abstract class JavadocCoverageTask : DefaultTask() {

    /** The sources whose documented elements are checked — the caller decides what "public" means. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** The module's toolchain `javadoc`, so the count does not depend on the JDK that runs Gradle. */
    @get:Nested
    abstract val javadocTool: Property<JavadocTool>

    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    /** Paths in the report are written relative to this, so the report reads the same on every machine. */
    @get:Internal
    abstract val rootDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val out = reportDir.get().asFile
        val docs = out.resolve("html")
        docs.deleteRecursively()
        val argFile = out.resolve("javadoc.args")
        argFile.writeText(
            buildList {
                add("-quiet")
                add("-Xdoclint:missing")
                add("-Xmaxwarns")
                add("1000000")
                add("-encoding")
                add("UTF-8")
                add("-d")
                add(quote(docs.absolutePath))
                if (!classpath.isEmpty) {
                    add("-classpath")
                    add(quote(classpath.asPath))
                }
                sources.files.sorted().forEach { add(quote(it.absolutePath)) }
            }.joinToString("\n"),
        )

        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            executable = javadocTool.get().executablePath.asFile.absolutePath
            args("@${argFile.absolutePath}")
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }

        val root = rootDir.get().asFile.toPath()
        val warnings = output.toString(Charsets.UTF_8).lineSequence()
            .mapNotNull { WARNING.find(it) }
            .map { m -> "${relativize(root, m.groupValues[1])}: ${m.groupValues[2]}" }
            .toList()
        out.resolve("warnings.txt").writeText(warnings.joinToString("\n", postfix = if (warnings.isEmpty()) "" else "\n"))

        val byKind = warnings.groupingBy { kindOf(it) }.eachCount().toSortedMap()
        out.resolve("summary.properties").writeText(
            buildString {
                appendLine("total=${warnings.size}")
                byKind.forEach { (kind, n) -> appendLine("kind.$kind=$n") }
            },
        )
        logger.lifecycle("$path: ${warnings.size} public API element(s) without javadoc $byKind")
        if (result.exitValue != 0) {
            logger.warn("$path: javadoc exited with ${result.exitValue}; the count may be incomplete — see ${out.resolve("javadoc.args")}")
        }
    }

    private fun relativize(root: java.nio.file.Path, file: String): String =
        runCatching { root.relativize(java.nio.file.Path.of(file)).toString() }.getOrDefault(file)

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        // doclint's `missing` group: "no comment", "no @param for x", "no @return", "no @throws for x", ...
        // Anything else javadoc prints (an unknown enum constant in a class file, a broken link) is not a
        // missing comment and is not counted.
        val WARNING = Regex("""^(.+?\.java):\d+: warning: (no (?:comment|main description|@\w+).*)$""")

        fun kindOf(line: String): String {
            val message = line.substringAfter(": ")
            return when {
                message.startsWith("no comment") -> "comment"
                message.startsWith("no main description") -> "description"
                else -> Regex("""^no @(\w+)""").find(message)?.groupValues?.get(1) ?: "other"
            }
        }
    }
}
