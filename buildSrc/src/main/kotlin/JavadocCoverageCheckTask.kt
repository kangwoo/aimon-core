import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The ratchet over [JavadocCoverageTask]: a module's count of undocumented public API must equal its baseline.
 *
 * The baseline lives in one file for the whole build (`config/javadoc/coverage-baseline.properties`, one
 * `<module>=<count>` line each) so that the number the roadmap quotes and the number the build enforces are the same
 * number in the same place. A module with no line has a baseline of zero — a new published module starts fully
 * documented.
 *
 * Both directions fail. Above the baseline is the regression this exists to stop. Below it is good news, and it fails
 * anyway so the baseline is lowered in the same change: a ratchet that is allowed to sit above the real count lets
 * the next change spend the slack without anyone seeing it.
 *
 * It compares totals, so a change that documents one element and adds an undocumented one passes. That is the price of
 * a number instead of a list of names, which would break on every rename.
 */
abstract class JavadocCoverageCheckTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val summary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    @get:Input
    abstract val moduleName: Property<String>

    /** Where the module's full list is, for the failure message. */
    @get:Input
    abstract val warningsPath: Property<String>

    @TaskAction
    fun check() {
        val module = moduleName.get()
        val actual = load(summary.get().asFile).getProperty("total").toInt()
        val baseline = load(baselineFile.get().asFile).getProperty(module)?.trim()?.toInt() ?: 0
        val file = "config/javadoc/" + baselineFile.get().asFile.name
        when {
            actual > baseline -> throw GradleException(
                "$module: ${actual - baseline} more public API element(s) without javadoc than the baseline " +
                    "($actual > $baseline). Document what you added — the full list is in ${warningsPath.get()}.",
            )
            actual < baseline -> throw GradleException(
                "$module: undocumented public API went down to $actual (baseline $baseline). " +
                    "Lower '$module' in $file to $actual so the ratchet keeps the gain.",
            )
        }
    }

    private fun load(f: java.io.File) = Properties().apply { f.reader().use { load(it) } }
}
