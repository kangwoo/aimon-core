package at.aimon.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A release must not pass a quality gate narrower than the one every pull request already clears.
 *
 * <p>
 * CI and {@code scripts/release.sh} are two hand-maintained lists of Gradle tasks that are supposed to say the same
 * thing, and nothing made them agree. They drifted once already: the release gate ran {@code test spotlessCheck} while
 * CI ran {@code checkAll}, so checkstyle — and the BOM's own {@code verifyBom} — never gated a publish. The
 * justification carried in the script was that checkstyle had "pre-existing warnings", which was never true of this
 * build: checkstyle here is {@code severity=error} with {@code maxErrors=0} and an empty suppressions file, so it has
 * no warning tier to accumulate. A stale comment outlived the condition it described because no check read it.
 *
 * <p>
 * The asymmetry is what makes the drift dangerous in one direction only. A publish to Maven Central is permanent, so
 * the release path is the one that must be at least as strict — never the reverse.
 *
 * <h2>What is enforced</h2>
 *
 * <ul>
 * <li>the release script's gate invokes {@code checkAll}, the aggregate CI runs;
 * <li>every Gradle task named by a CI {@code run: ./gradlew …} step is also named by that gate, except the
 * reporting-only tasks listed in {@link #REPORTING_ONLY_CI_TASKS}. Adding a verification step to CI without touching
 * the release script fails here;
 * <li>the gate carries no {@code -x} exclusion. The old gate excluded {@code :aimon-filesystem-gridfs:test} and
 * {@code :aimon-filesystem-s3:test}, which predate the {@code @Tag("docker")} convention that already keeps
 * Testcontainers tests out of {@code test}. This pins that half of the fix so it cannot creep back.
 * </ul>
 *
 * <h2>What this cannot see</h2>
 *
 * <p>
 * Task names, not task graphs. If {@code checkAll} itself stops depending on {@code checkStyle}, both files still
 * agree and this test still passes — that hole is closed by the root build script owning one aggregate rather than by
 * a text scan. One tier runs in neither place and so cannot be seen here: {@code playwrightTest}
 * ({@code @Tag("playwright")}, which needs browser binaries installed). Nothing here notices when it rots.
 * {@code integrationTest} and {@code packagingTest} were both once in that position; each is now a CI step and a
 * gate task, so the comparison below holds them to the same rule as every other task.
 *
 * <p>
 * Shell and YAML rather than bytecode is why this is plain JUnit and not ArchUnit, following the precedent set by
 * {@link PublishedModuleLoggingBindingTest}.
 */
@DisplayName("release gate matches CI gate")
class ReleaseGateMatchesCiGateTest {

    private static final String RELEASE_SCRIPT = "scripts/release.sh";

    private static final String CI_WORKFLOW = ".github/workflows/build.yml";

    /** The aggregate that both paths are supposed to run. */
    private static final String AGGREGATE_TASK = "checkAll";

    /**
     * Section header in the release script that precedes the gate invocation. Locating the gate by section rather
     * than by "the first {@code $GRADLE} line" keeps the publish invocation later in the same file out of scope.
     */
    private static final String GATE_SECTION_MARKER = "quality gate";

    /**
     * CI tasks that produce artifacts rather than pass/fail a build, and so are not expected in the release gate.
     * {@code jacocoTestReport} writes coverage HTML; nothing in this build configures a
     * {@code jacocoTestCoverageVerification} rule, so it cannot fail a PR either.
     */
    private static final List<String> REPORTING_ONLY_CI_TASKS = List.of("jacocoTestReport");

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    @DisplayName("the release gate runs the same aggregate CI runs")
    void releaseGateRunsTheAggregate() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final String gate = releaseGateInvocation();

        assertThat(gradleTasksIn(gate)).withFailMessage(
                "%s gates a publish on %s, but CI runs `%s`. A release must not pass a narrower gate than a "
                        + "pull request — a publish to Maven Central cannot be taken back.",
                RELEASE_SCRIPT, gradleTasksIn(gate), AGGREGATE_TASK).contains(AGGREGATE_TASK);
    }

    @Test
    @DisplayName("every verification task CI runs is also in the release gate")
    void releaseGateCoversEveryCiVerificationTask() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final List<String> gateTasks = gradleTasksIn(releaseGateInvocation());
        final List<String> ciTasks = ciGradleTasks();

        // Without this the comparison would report success just as loudly if the YAML had been renamed and no CI
        // task were found at all.
        assertThat(ciTasks)
                .withFailMessage("found no `./gradlew` steps in %s — the scan is broken, not clean", CI_WORKFLOW)
                .isNotEmpty();

        for (final String ciTask : ciTasks) {
            if (REPORTING_ONLY_CI_TASKS.contains(ciTask)) {
                continue;
            }
            assertThat(gateTasks).withFailMessage(
                    "CI runs `%s` (%s) but the release gate in %s does not: %s.%n"
                            + "Either add it to the gate, or — if it only produces reports and cannot fail a build — "
                            + "add it to REPORTING_ONLY_CI_TASKS in this test with a note saying why.",
                    ciTask, CI_WORKFLOW, RELEASE_SCRIPT, gateTasks).contains(ciTask);
        }
    }

    @Test
    @DisplayName("the release gate excludes no module")
    void releaseGateExcludesNoModule() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final String gate = releaseGateInvocation();

        assertThat(gate).withFailMessage(
                "the release gate in %s carries a `-x` exclusion:%n  %s%nInfrastructure-dependent tests are kept out "
                        + "of `test` by @Tag(\"docker\") already; an exclusion here silently narrows the gate for "
                        + "whichever module is named.",
                RELEASE_SCRIPT, gate).doesNotContain(" -x ");
    }

    /**
     * The Gradle invocation under the release script's quality-gate section. Fails rather than returns empty if the
     * section or the invocation cannot be found — a renamed section must break this test, not silently empty it.
     */
    private static String releaseGateInvocation() throws IOException {
        final Path script = REPOSITORY_ROOT.resolve(RELEASE_SCRIPT);
        assertThat(script).withFailMessage("%s not found — this test is pointed at the wrong path", RELEASE_SCRIPT)
                .isRegularFile();

        final List<String> lines = Files.readAllLines(script);
        boolean inGateSection = false;
        for (final String rawLine : lines) {
            final String line = rawLine.strip();
            if (line.startsWith("#") && line.contains(GATE_SECTION_MARKER)) {
                inGateSection = true;
                continue;
            }
            if (inGateSection && line.startsWith("$GRADLE ")) {
                return line;
            }
        }
        throw new AssertionError("no `$GRADLE` invocation found after the '" + GATE_SECTION_MARKER + "' section in "
                + RELEASE_SCRIPT + " — the section marker or the gate moved, so this test can no longer see what a "
                + "release is gated on");
    }

    /** Task names from every {@code run: ./gradlew …} step in the CI workflow. */
    private static List<String> ciGradleTasks() throws IOException {
        final Path workflow = REPOSITORY_ROOT.resolve(CI_WORKFLOW);
        assertThat(workflow).withFailMessage("%s not found — this test is pointed at the wrong path", CI_WORKFLOW)
                .isRegularFile();

        final List<String> tasks = new ArrayList<>();
        for (final String rawLine : Files.readAllLines(workflow)) {
            final String line = rawLine.strip();
            if (line.startsWith("run:") && line.contains("./gradlew")) {
                tasks.addAll(gradleTasksIn(line));
            }
        }
        return tasks;
    }

    /**
     * Task names in a shell invocation: the tokens after the launcher that are not flags. {@code -x} and its argument
     * are dropped, since an excluded task is not one the gate runs.
     */
    private static List<String> gradleTasksIn(String invocation) {
        final String[] tokens = invocation.split("\\s+");
        final List<String> tasks = new ArrayList<>();
        boolean afterLauncher = false;
        boolean skipNext = false;
        for (final String token : tokens) {
            if (!afterLauncher) {
                afterLauncher = token.endsWith("gradlew") || "$GRADLE".equals(token);
                continue;
            }
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if ("-x".equals(token)) {
                skipNext = true;
                continue;
            }
            if (token.startsWith("-")) {
                continue;
            }
            tasks.add(token);
        }
        return tasks;
    }

    /**
     * Walks up from the test's working directory — the module directory under Gradle's defaults — until it finds the
     * directory holding both {@code settings.gradle.kts} and {@code modules/}. Returns {@code null} rather than
     * throwing so the test can skip itself if it is ever run from somewhere unexpected.
     */
    private static Path locateRepositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))
                    && Files.isDirectory(candidate.resolve("modules"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
