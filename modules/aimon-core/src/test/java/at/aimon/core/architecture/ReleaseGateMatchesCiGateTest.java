package at.aimon.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release must not pass a quality gate narrower than the one every pull request already clears.
 *
 * <p>
 * CI, {@code scripts/release.sh} and the {@code /release} skill are three hand-maintained statements of one list of
 * Gradle tasks, and nothing made them agree. They drifted once already: the release gate ran
 * {@code test spotlessCheck} while CI ran {@code checkAll}, so checkstyle — and the BOM's own {@code verifyBom} —
 * never gated a publish. The justification carried in the script was that checkstyle had "pre-existing warnings",
 * which was never true of this build: checkstyle here is {@code severity=error} with {@code maxErrors=0} and an empty
 * suppressions file, so it has no warning tier to accumulate. A stale comment outlived the condition it described
 * because no check read it.
 *
 * <p>
 * Then it happened a second time, in prose. When {@code integrationTest} and {@code packagingTest} moved inside both
 * gates, the skill's Notes went on saying the gate was {@code checkAll} alone and that those two tiers stayed out of
 * it "in both places" — the exact inverse of what the script ran. The first drift was caught by adding a check; this
 * one survived that check because the check only read the two files it already knew about.
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
 * Testcontainers tests out of {@code test}. This pins that half of the fix so it cannot creep back;
 * <li>the {@code /release} skill's Notes name that same task list. A document is not a gate, but this one is read by
 * whoever is about to publish, and a wrong description of what a release is checked against is worth failing a build
 * over;
 * <li>the release script refuses to start while a provider API key is in its environment, even one set to the empty
 * string. It names every key that is set and no other, never prints a value, refuses before it invokes {@code git}
 * for any reason and before its pre-flight checks, and still answers a bad argument with its usage line and exit 2
 * first. The live-API classes carry no tag, so a key in the environment would run them inside {@code checkAll}:
 * billed calls, a gate that can go red for a reason on the provider's side, and a gate CI — which has no key — does
 * not run;
 * <li>the keys it refuses are exactly the variables {@code @EnabledIfEnvironmentVariable} gates on in the provider
 * modules' tests, so the next provider's key cannot join the build without joining the refusal.
 * </ul>
 *
 * <p>
 * The refusal is behaviour, not a task name, so those tests run the script rather than read it: a commented-out,
 * inverted or unreachable check passes any scan, and no scan can show that a value is never printed. They run the
 * real file with {@code --dry-run} from an empty directory, with the environment cleared and a {@code PATH} holding
 * only a stub {@code git} that records every call. The sandbox is inert with or without the check — no real
 * {@code git}, {@code docker} or {@code ./gradlew} is reachable, and a run nothing refuses stops at
 * {@code gradle.properties not found} — so removing the check turns these tests red instead of starting a release.
 * The call log is what pins the ordering. A check moved below the {@code cd} that calls {@code git}, or below the
 * {@code EXIT} trap that calls it again on a non-zero exit, still refuses before "Pre-flight checks" and would pass
 * every other assertion here.
 *
 * <h2>What this cannot see</h2>
 *
 * <p>
 * Task names, not task graphs. If {@code checkAll} itself stops depending on {@code checkStyle}, both files still
 * agree and this test still passes — that hole is closed by the root build script owning one aggregate rather than by
 * a text scan.
 *
 * <p>
 * This paragraph used to carve out a tier that ran in neither place and so could not be seen here. There is none
 * left: {@code integrationTest}, {@code packagingTest} and {@code playwrightTest} were each in that position in turn
 * and each is now a CI step and a gate task, so the comparison below holds every tier in the build to the same rule.
 * The carve-out is worth remembering rather than deleting, because it described a real blind spot and the shape of
 * it recurs: what this test compares is two <em>lists</em>, so a tier absent from both is invisible to it however
 * badly it rots. {@code playwrightTest} spent that time not merely ungated but inert — its Gradle task was
 * registered without {@code testClassesDirs} or {@code classpath}, so it matched no test class, reported
 * {@code NO-SOURCE} and went green in 650ms. A tier nothing runs is a tier nothing can tell apart from a passing
 * one.
 *
 * <p>
 * The key refusal is held to what its census can read. A key read with {@code System.getenv} and an assumption rather
 * than with the annotation, and a key gate outside {@code modules/aimon-llm-*}, are both invisible to it; so is a new
 * class gated on a variable that is already refused, which is not a change the refusal needs. The sandbox classes
 * gated on {@code AIMON_DOCKER_IT} and {@code AIMON_KUBERNETES_IT} are outside the census on purpose, and the script
 * does not refuse them — whether it should is backlog {@code LA-2}. Nothing checks that the script calls nothing but
 * {@code git} before its pre-flight checks: today it calls nothing else, and the stub records only {@code git}. And
 * the provider modules' test sources are not inputs of this module's {@code test} task, so a local build that changes
 * only them can report this test {@code UP-TO-DATE}. The tag scan above has the same gap; CI builds from a fresh
 * checkout and does not.
 *
 * <p>
 * Shell and YAML rather than bytecode is why this is plain JUnit and not ArchUnit, following the precedent set by
 * {@link PublishedModuleLoggingBindingTest}.
 */
@DisplayName("release gate matches CI gate")
class ReleaseGateMatchesCiGateTest {

    private static final String RELEASE_SCRIPT = "scripts/release.sh";

    private static final String CI_WORKFLOW = ".github/workflows/build.yml";

    private static final String RELEASE_SKILL = ".claude/skills/release/SKILL.md";

    /** The aggregate that both paths are supposed to run. */
    private static final String AGGREGATE_TASK = "checkAll";

    /**
     * Section header in the release script that precedes the gate invocation. Locating the gate by section rather
     * than by "the first {@code $GRADLE} line" keeps the publish invocation later in the same file out of scope.
     */
    private static final String GATE_SECTION_MARKER = "quality gate";

    /**
     * CI tasks that produce artifacts rather than pass/fail a build, and so are not expected in the release gate.
     * {@code jacocoTestReport} writes coverage XML and HTML and has no pass/fail opinion of its own.
     *
     * <p>
     * Its sibling {@code jacocoTestCoverageVerification} is deliberately <em>not</em> here. That task does fail a
     * build — it carries the per-module floors in {@code gradle/coverage-baselines.properties} — so the rule below
     * applies to it like any other verification task, and the release gate names it. This exemption list is for
     * tasks that cannot fail, not for coverage as a subject.
     */
    private static final List<String> REPORTING_ONLY_CI_TASKS = List.of("jacocoTestReport");

    /**
     * The skill's claim about what a release is gated on, as a backticked list of task names. Matching a fixed phrase
     * rather than scanning loose prose is deliberate: a reworded sentence must fail loudly here, because the whole
     * point is that nobody notices when this sentence quietly stops being true.
     */
    private static final Pattern SKILL_GATE_DECLARATION = Pattern.compile("Quality gate = `([^`]+)`");

    /**
     * Every JUnit tag this build keeps out of {@code test}, mapped to the task that runs it. Both halves matter: a
     * new tag with no entry fails here, and an entry whose task is missing from either gate fails too.
     */
    private static final Map<String, String> TAG_TO_GATE_TASK = Map.of("docker", "integrationTest", "packaging",
            "packagingTest", "playwright", "playwrightTest");

    /**
     * {@code @Tag("...")} as an annotation, not as a mention inside javadoc — the distinction §0.4-a of the backlog
     * item paid for twice.
     *
     * <p>
     * Both optional groups were added because a probe walked past the pattern, not because they looked prudent. The
     * first draft matched a bare {@code @Tag(} and missed {@code @org.junit.jupiter.api.Tag("smoke")}; the second
     * missed {@code @Tag(value = "smoke")}. Neither form appears in the tree today — every {@code @Tag} here is
     * unqualified and unnamed — so each was a gap in a new safety net rather than a regression, and each was found
     * the same way: by writing the form and watching the check stay green.
     *
     * <p>
     * The anchor is what keeps it honest in the other direction. Requiring the annotation to open the line rejects a
     * javadoc mention, a commented-out {@code // @Tag("x")} and a string literal containing one — all three probed,
     * all three still passing. A same-line {@code @Test @Tag("x")} would slip past that anchor but cannot survive
     * {@code spotlessJavaCheck}, which puts annotations on their own lines.
     */
    private static final Pattern TEST_TAG_ANNOTATION = Pattern
            .compile("^\\s*@(?:[\\w.]+\\.)?Tag\\((?:value\\s*=\\s*)?\"([^\"]+)\"\\)");

    /**
     * Words the skill uses to say a tier is <em>not</em> gated. A line carrying one of these must not also name a task
     * the gate runs — see {@link #releaseSkillDoesNotCallAGatedTierUngated()} for why one assertion is not enough.
     */
    private static final List<String> UNGATED_CLAIM_MARKERS = List.of("opt-in", "outside both", "outside the gate");

    /**
     * The variables the live-API test classes are gated on, and so the ones the release script must refuse. Frozen
     * rather than derived, on the precedent of {@link #TAG_TO_GATE_TASK}: the refusal cases run once per entry, and
     * {@link #refusedKeysAreTheProviderModulesKeyGates()} holds the list equal to what the provider modules gate on.
     */
    private static final List<String> PROVIDER_KEY_VARIABLES = List.of("ANTHROPIC_KEY", "OPENAI_KEY");

    /**
     * The value the refusal cases put in a key. Not a key and not shaped like one — no {@code sk-} prefix for a secret
     * scanner to flag — and distinctive enough that finding it anywhere in the script's output means a value was
     * printed.
     */
    private static final String KEY_SENTINEL = "dummy-98-sentinel";

    /** The first line the release script logs once it is past the key check. */
    private static final String PRE_FLIGHT_LOG = "Pre-flight checks";

    /** The release script's usage line, which a bad argument must still get before any key is looked at. */
    private static final String USAGE_LINE = "Usage: scripts/release.sh";

    /**
     * {@code @EnabledIfEnvironmentVariable}, or its {@code @EnabledIfEnvironmentVariables} container, opening a line —
     * anchored for the reasons given on {@link #TEST_TAG_ANNOTATION}. What follows is read up to the parenthesis that
     * closes it, across as many lines as that takes, and every {@link #KEY_GATE_NAME} inside it counts.
     *
     * <p>
     * Reading the whole argument list instead of expecting {@code named} straight after the parenthesis is what a
     * probe asked for: {@code @EnabledIfEnvironmentVariable(matches = ".+", named = "GEMINI_KEY")} and the container
     * both walked past that first pattern. For the case this census exists for, such a miss is silent rather than
     * loud: a new provider module's one new name goes unread, the two old names are still found in the other modules,
     * and the equality holds.
     */
    private static final Pattern KEY_GATE_ANNOTATION = Pattern
            .compile("^\\s*@(?:[\\w.]+\\.)?EnabledIfEnvironmentVariables?\\(");

    /** The {@code named} attribute inside a {@link #KEY_GATE_ANNOTATION}, in whichever position it is written. */
    private static final Pattern KEY_GATE_NAME = Pattern.compile("\\bnamed\\s*=\\s*\"([^\"]+)\"");

    /** The provider modules: every directory under {@code modules/} whose name starts with this. */
    private static final String PROVIDER_MODULE_PREFIX = "aimon-llm-";

    /**
     * How long one sandboxed run of the release script may take. Nothing it can reach there blocks — the one
     * {@code read} in the script comes after the gate — so this only has to outlast a slow machine starting bash.
     */
    private static final long SCRIPT_TIMEOUT_SECONDS = 30;

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

    @Test
    @DisplayName("the release skill describes the gate the release script actually runs")
    void releaseSkillDescribesTheRealGate() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final List<String> gateTasks = gradleTasksIn(releaseGateInvocation());
        final List<String> declaredTasks = skillDeclaredGateTasks();

        assertThat(declaredTasks).withFailMessage(
                "%s tells the release operator the gate is `%s`, but %s runs `%s`.%n"
                        + "Whoever is about to publish reads that sentence to decide what has been checked, so it "
                        + "must name the same tasks. Update the skill's Notes to match the script.",
                RELEASE_SKILL, declaredTasks, RELEASE_SCRIPT, gateTasks).containsExactlyInAnyOrderElementsOf(gateTasks);
    }

    /**
     * The sentence above is not the only sentence in that file about what is gated, and checking one of them is what
     * let the third drift through. When {@code playwrightTest} joined both gates, the declaration on one line was
     * updated and the prose three lines below it — "{@code playwrightTest} is the only opt-in tier outside both" —
     * was not. {@link #releaseSkillDescribesTheRealGate()} passed the whole time, because
     * {@link #SKILL_GATE_DECLARATION} reads the backticked list and nothing else, and the operator was left reading
     * both claims inside one bullet.
     *
     * <p>
     * So this does not widen that pattern. The note on it — that matching a fixed phrase beats scanning loose prose —
     * is a decision about how to <em>locate the declaration</em>, and loosening it would trade a loud failure on a
     * reworded sentence for a quiet one. What is added instead is a second, narrow invariant that needs no parsing:
     * <b>no line that calls something ungated may name a task the gate runs.</b> It is deliberately blunt about
     * false positives — a line legitimately describing a tier as opt-in simply must not name a gated task, which
     * costs a rewording and buys a check that does not depend on anyone noticing.
     */
    @Test
    @DisplayName("the release skill does not call a gated tier opt-in")
    void releaseSkillDoesNotCallAGatedTierUngated() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final List<String> gateTasks = gradleTasksIn(releaseGateInvocation());
        final Path skill = REPOSITORY_ROOT.resolve(RELEASE_SKILL);
        assertThat(skill).withFailMessage("%s not found — this test is pointed at the wrong path", RELEASE_SKILL)
                .isRegularFile();

        final List<String> offenders = new ArrayList<>();
        int lineNumber = 0;
        for (final String line : Files.readAllLines(skill)) {
            lineNumber++;
            final String lowered = line.toLowerCase(Locale.ROOT);
            if (UNGATED_CLAIM_MARKERS.stream().noneMatch(lowered::contains)) {
                continue;
            }
            for (final String task : gateTasks) {
                if (line.contains(task)) {
                    offenders.add(RELEASE_SKILL + ":" + lineNumber + " — " + line.strip());
                    break;
                }
            }
        }

        assertThat(offenders).withFailMessage(
                "%s describes a task the release gate actually runs as ungated:%n  %s%nThe gate is %s. Whoever is "
                        + "about to publish reads this file to decide what has been checked, and a bullet that both "
                        + "names a tier in the gate and calls it opt-in leaves them with two answers.",
                RELEASE_SKILL, String.join(System.lineSeparator() + "  ", offenders), gateTasks).isEmpty();
    }

    /**
     * Three files now tell a reader that nothing in this build is opt-in — {@code scripts/release.sh}, the tier note
     * in {@code aimon.java-conventions}, and {@link #RELEASE_SKILL}. All three are prose, and a fourth
     * {@code @Tag} added to any test would falsify all three at once without failing anything. That is the shape
     * every finding in this area has had, so the sentence gets an invariant instead of a promise.
     *
     * <p>
     * A frozen set rather than a rule that derives the mapping, on the precedent of this repo's other baselines
     * (checkstyle's error budget, {@code BASELINE_TOP_LEVEL_CYCLES}, the coverage floors): a tag name and a task
     * name are not derivable from one another — {@code docker} runs as {@code integrationTest} — so the link has to
     * be written down, and writing it down is only worth anything if adding a tag without touching it fails.
     */
    @Test
    @DisplayName("every test tag excluded from `test` is a task both gates run")
    void everyTestTagIsGated() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final Set<String> tags = testTagsInRepository();
        assertThat(tags)
                .withFailMessage("the tags used by this build's tests are %s, but %s maps %s.%n"
                        + "A tag with no entry here is a tier nothing gates, and three files say in prose that no "
                        + "such tier exists. Add the tag to TAG_TO_GATE_TASK together with the task that runs it, "
                        + "put that task in CI and the release gate — or, if it is genuinely meant to stay out, "
                        + "reword those three files first.", tags, "TAG_TO_GATE_TASK", TAG_TO_GATE_TASK.keySet())
                .isEqualTo(TAG_TO_GATE_TASK.keySet());

        final List<String> gateTasks = gradleTasksIn(releaseGateInvocation());
        final List<String> ciTasks = ciGradleTasks();
        for (final String task : TAG_TO_GATE_TASK.values()) {
            assertThat(ciTasks).withFailMessage("`%s` runs a tagged tier but %s has no step for it", task, CI_WORKFLOW)
                    .contains(task);
            assertThat(gateTasks)
                    .withFailMessage("`%s` runs a tagged tier but %s does not gate on it", task, RELEASE_SCRIPT)
                    .contains(task);
        }
    }

    /**
     * One run per key, with only that key set. A refusal that also named the key that is not set would tell the
     * operator to unset something that is not there, so each run must name its own key and not the other.
     */
    @Test
    @DisplayName("the release script refuses to start while a provider API key is set, before it invokes git")
    void releaseScriptRefusesEachProviderKey(@TempDir Path sandbox) throws IOException, InterruptedException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        for (final String key : PROVIDER_KEY_VARIABLES) {
            final ScriptRun run = runReleaseScript(sandbox, key, Map.of(key, KEY_SENTINEL), "--dry-run");
            assertRefused(run, List.of(key));
        }
    }

    /**
     * "Set" means present in the environment, whatever the value. A class gated with {@code matches = ".*"} runs on an
     * empty value, and the refusal should not depend on how each class writes its regex.
     */
    @Test
    @DisplayName("the release script refuses a provider API key set to the empty string")
    void releaseScriptRefusesAKeySetToEmpty(@TempDir Path sandbox) throws IOException, InterruptedException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final ScriptRun run = runReleaseScript(sandbox, "empty-key", Map.of("OPENAI_KEY", ""), "--dry-run");

        assertRefused(run, List.of("OPENAI_KEY"));
    }

    @Test
    @DisplayName("the release script names every provider API key that is set, in one refusal")
    void releaseScriptNamesEveryKeyThatIsSet(@TempDir Path sandbox) throws IOException, InterruptedException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final Map<String, String> environment = new TreeMap<>();
        for (final String key : PROVIDER_KEY_VARIABLES) {
            environment.put(key, KEY_SENTINEL);
        }
        final ScriptRun run = runReleaseScript(sandbox, "every-key", environment, "--dry-run");

        assertRefused(run, PROVIDER_KEY_VARIABLES);
    }

    /**
     * The refusal cases assert absences — no pre-flight, no {@code git} call — and an absence proves nothing unless the
     * same sandbox without a key produces the presence. This run must get past the check to pre-flight and must call
     * the stub, which shows the stub was on {@code PATH} and could execute. It also catches a check that refuses
     * whether or not a key is set.
     */
    @Test
    @DisplayName("the release script starts without a provider API key, so the refusal cases are not vacuous")
    void releaseScriptStartsWithoutAProviderKey(@TempDir Path sandbox) throws IOException, InterruptedException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final ScriptRun run = runReleaseScript(sandbox, "no-key", Map.of(), "--dry-run");

        assertThat(run.output).withFailMessage(
                "with no provider key set, %s did not reach its pre-flight checks — the key check refuses a clean "
                        + "environment, or this harness no longer reaches the script:%n%s",
                RELEASE_SCRIPT, run).contains(PRE_FLIGHT_LOG);
        for (final String key : PROVIDER_KEY_VARIABLES) {
            assertThat(run.output)
                    .withFailMessage("with no provider key set, %s still named %s:%n%s", RELEASE_SCRIPT, key, run)
                    .doesNotContain(key);
        }
        assertThat(run.gitCalls).withFailMessage(
                "with no provider key set, %s never called the stub git — the stub is not reachable on PATH, so every "
                        + "\"no git call\" assertion in the refusal cases would pass without checking anything:%n%s",
                RELEASE_SCRIPT, run).contains("rev-parse --show-toplevel");
    }

    /**
     * The key check comes after the argument loop, so a bad argument keeps the answer it always had: the usage line
     * and exit 2. A check moved above the loop would refuse instead.
     */
    @Test
    @DisplayName("the release script answers a bad argument with its usage line before it looks at provider keys")
    void releaseScriptRejectsABadArgumentBeforeTheKeyCheck(@TempDir Path sandbox)
            throws IOException, InterruptedException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final ScriptRun run = runReleaseScript(sandbox, "bad-argument", Map.of("OPENAI_KEY", KEY_SENTINEL), "--bogus");

        assertThat(run.exitCode).withFailMessage(
                "given an unknown argument with OPENAI_KEY set, %s exited %d rather than 2 — the key check moved above "
                        + "the argument loop:%n%s",
                RELEASE_SCRIPT, run.exitCode, run).isEqualTo(2);
        assertThat(run.output)
                .withFailMessage("given an unknown argument, %s did not print its usage line:%n%s", RELEASE_SCRIPT, run)
                .contains(USAGE_LINE);
        assertThat(run.output).withFailMessage(
                "given an unknown argument, %s answered with the key refusal rather than the usage line:%n%s",
                RELEASE_SCRIPT, run).doesNotContain("OPENAI_KEY").doesNotContain(KEY_SENTINEL);
        assertThat(run.gitCalls)
                .withFailMessage("given an unknown argument, %s invoked git before exiting:%n%s", RELEASE_SCRIPT, run)
                .isEmpty();
    }

    /**
     * The refusal list is a hand-written copy of which variables gate a billed tier, and this class exists because
     * such copies drift. So the copy is held to its source: the {@code @EnabledIfEnvironmentVariable} gates in the
     * provider modules' tests. A provider module gated on a new variable fails here until
     * {@link #PROVIDER_KEY_VARIABLES} names it, and that fails the refusal cases until the script refuses it too.
     *
     * <p>
     * Scoped to {@code modules/aimon-llm-*} rather than the whole tree. Two classes elsewhere are gated the same way,
     * on {@code AIMON_DOCKER_IT} and {@code AIMON_KUBERNETES_IT}, and neither variable is a provider key. An allowlist
     * for them would need a written reason they may stay out of the refusal, and nobody has verified one — backlog
     * {@code LA-1} §0.1 declined to write it, and {@code LA-2} leaves it open.
     */
    @Test
    @DisplayName("the keys the release script refuses are the key gates in the provider modules")
    void refusedKeysAreTheProviderModulesKeyGates() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final Set<String> gated = keyGateVariablesInProviderModules();

        assertThat(gated)
                .withFailMessage("found no @EnabledIfEnvironmentVariable under modules/%s*/src/test — the scan "
                        + "is broken, not clean", PROVIDER_MODULE_PREFIX)
                .isNotEmpty();
        assertThat(gated).withFailMessage("the provider modules' tests are gated on %s, but %s refuses %s.%n"
                + "If a gated variable is a provider key: add it to PROVIDER_KEY_VARIABLES in this test, make %s "
                + "refuse it, and add its classes to CONTRIBUTING.md's live-API table. The refusal cases fail until "
                + "the script refuses it.%n"
                + "If it gates something that is not a provider key: narrow this scan to leave it out, with a note "
                + "saying what it gates and why the release gate may inherit it.%n"
                + "If a refused variable no longer gates anything: remove it here and from the script.", gated,
                RELEASE_SCRIPT, PROVIDER_KEY_VARIABLES, RELEASE_SCRIPT)
                .isEqualTo(new TreeSet<>(PROVIDER_KEY_VARIABLES));
    }

    /** Holds one refusal to everything the class javadoc says is enforced about it. */
    private static void assertRefused(ScriptRun run, List<String> setKeys) {
        assertThat(run.exitCode).withFailMessage("with %s set, %s exited %d instead of refusing with 1:%n%s", setKeys,
                RELEASE_SCRIPT, run.exitCode, run).isEqualTo(1);
        for (final String key : PROVIDER_KEY_VARIABLES) {
            if (setKeys.contains(key)) {
                assertThat(run.output)
                        .withFailMessage("with %s set, %s did not name %s:%n%s", setKeys, RELEASE_SCRIPT, key, run)
                        .contains(key);
            } else {
                assertThat(run.output).withFailMessage(
                        "with %s set, the refusal also names %s, which is not set — the operator would be told to "
                                + "unset something that is not there:%n%s",
                        setKeys, key, run).doesNotContain(key);
            }
        }
        assertThat(run.output)
                .withFailMessage("with %s set, %s printed a key's value:%n%s", setKeys, RELEASE_SCRIPT, run)
                .doesNotContain(KEY_SENTINEL);
        assertThat(run.output).withFailMessage(
                "with %s set, %s got as far as its pre-flight checks — the key check is gone, or below `log \"%s\"`:"
                        + "%n%s",
                setKeys, RELEASE_SCRIPT, PRE_FLIGHT_LOG, run).doesNotContain(PRE_FLIGHT_LOG);
        assertThat(run.gitCalls).withFailMessage("with %s set, %s invoked git before refusing.%n"
                + "The key check moved below the `cd` that calls git, or below `trap cleanup EXIT`, whose handler "
                + "calls git when a refusal exits non-zero. A shell about to be refused must not reach `git fetch` "
                + "first.%n%s", setKeys, RELEASE_SCRIPT, run).isEmpty();
    }

    /**
     * Runs the real release script in {@code tempDir}/{@code caseName}, from an empty working directory, with a
     * cleared environment that holds only {@code PATH}, {@code HOME} and the case's variables. {@code PATH} holds a
     * single stub {@code git} that appends each call's arguments to a log outside the working directory, answers
     * {@code rev-parse} with the directory it runs in, and exits 0, so the {@code EXIT} trap's
     * {@code git diff --quiet} prints no note either.
     *
     * <p>
     * Output goes to a file that is read only after the process exits, so a script that hangs cannot block the read
     * and the timeout always fires. Clearing the environment keeps the no-key case deterministic on a machine with a
     * key exported, and drops the {@code JAVA_TOOL_OPTIONS} the release script exports into the JVM running this.
     */
    private static ScriptRun runReleaseScript(Path tempDir, String caseName, Map<String, String> environment,
            String... arguments) throws IOException, InterruptedException {
        final Path bash = locateBash();
        assumeTrue(bash != null, "no bash on PATH — " + RELEASE_SCRIPT + " cannot run here either");
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"),
                "the temp directory's file system has no POSIX permissions, so the stub git this test puts on PATH "
                        + "cannot be made executable");

        final Path sandbox = Files.createDirectories(tempDir.resolve(caseName));
        final Path work = Files.createDirectories(sandbox.resolve("work"));
        final Path bin = Files.createDirectories(sandbox.resolve("bin"));
        final Path gitCallLog = sandbox.resolve("git-calls.log");
        final Path output = sandbox.resolve("output.txt");

        // The stub names its log single-quoted, which a quote in the temp path would break.
        assertThat(gitCallLog.toString()).doesNotContain("'");
        final Path stub = bin.resolve("git");
        Files.writeString(stub, "#!/bin/sh\n" + "printf '%s\\n' \"$*\" >> '" + gitCallLog + "'\n"
                + "[ \"$1\" = rev-parse ] && pwd\n" + "exit 0\n");
        Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwxr-xr-x"));
        requireRunnableStub(stub, gitCallLog);

        final ProcessBuilder builder = new ProcessBuilder(bash.toString(),
                REPOSITORY_ROOT.resolve(RELEASE_SCRIPT).toString());
        builder.command().addAll(List.of(arguments));
        builder.directory(work.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        final Map<String, String> processEnvironment = builder.environment();
        processEnvironment.clear();
        processEnvironment.put("PATH", bin.toString());
        processEnvironment.put("HOME", work.toString());
        processEnvironment.putAll(environment);

        final Process process = builder.start();
        process.getOutputStream().close();
        awaitExit(process, RELEASE_SCRIPT + " " + String.join(" ", arguments));

        final List<String> gitCalls = Files.exists(gitCallLog) ? Files.readAllLines(gitCallLog) : List.of();
        return new ScriptRun(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8), gitCalls);
    }

    /**
     * Runs the stub once on its own and requires it to record the call. Every refusal case asserts that the log stayed
     * empty, and a stub that cannot execute would satisfy that without the script being checked at all.
     *
     * <p>
     * This fails where the two assumptions before it skip, because it answers a different question. Those two say
     * the harness cannot be built on this machine: without bash the release script cannot run here either, and
     * without POSIX permissions there is no execute bit to give the stub. A stub that was built and still does not
     * run — a {@code noexec} temp mount, say — turns up on a machine that can run the release script, and skipping
     * there would leave the refusal unpinned exactly where a release can still be cut. The remedy is to point
     * {@code java.io.tmpdir} at a directory that allows execution.
     */
    private static void requireRunnableStub(Path stub, Path gitCallLog) throws IOException, InterruptedException {
        final Process process;
        try {
            process = new ProcessBuilder(stub.toString(), "self-check").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (final IOException e) {
            throw new AssertionError("the stub git at " + stub + " cannot be started (" + e.getMessage() + "), so no "
                    + "assertion that the release script never called git would check anything. Point "
                    + "java.io.tmpdir at a directory that allows execution.", e);
        }
        process.getOutputStream().close();
        awaitExit(process, "the stub git at " + stub);
        if (!Files.exists(gitCallLog)) {
            throw new AssertionError("the stub git at " + stub + " ran but recorded nothing in " + gitCallLog
                    + ", so no assertion that the release script never called git would check anything");
        }
        Files.delete(gitCallLog);
    }

    private static void awaitExit(Process process, String what) throws InterruptedException {
        if (!process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(what + " did not exit within " + SCRIPT_TIMEOUT_SECONDS + "s in the sandbox, "
                    + "where nothing it can reach should block");
        }
    }

    /**
     * The first executable {@code bash} on this JVM's {@code PATH}, which is the one {@code #!/usr/bin/env bash} picks
     * from the same {@code PATH}, or {@code null} when there is none.
     */
    private static Path locateBash() {
        final String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (final String directory : path.split(File.pathSeparator)) {
            if (directory.isEmpty()) {
                continue;
            }
            try {
                final Path candidate = Path.of(directory, "bash");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (final InvalidPathException e) {
                // A PATH entry this platform cannot parse holds no bash this test could start.
            }
        }
        return null;
    }

    /** The variables named by key-gate annotations in the test sources of every provider module. */
    private static Set<String> keyGateVariablesInProviderModules() throws IOException {
        final List<Path> providerModules;
        try (Stream<Path> modules = Files.list(REPOSITORY_ROOT.resolve("modules"))) {
            providerModules = modules.filter(Files::isDirectory)
                    .filter(module -> module.getFileName().toString().startsWith(PROVIDER_MODULE_PREFIX)).toList();
        }
        final Set<String> names = new TreeSet<>();
        for (final Path module : providerModules) {
            final Path testSources = module.resolve("src/test");
            if (!Files.isDirectory(testSources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(testSources)) {
                for (final Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    names.addAll(keyGateVariablesIn(Files.readAllLines(file)));
                }
            }
        }
        return names;
    }

    /**
     * Every {@code named} value in one source file's key-gate annotations. Each annotation is read from the line it
     * opens on through the line where its parentheses balance, counting only parentheses outside string literals,
     * since a {@code matches} regex is free to contain one.
     */
    private static Set<String> keyGateVariablesIn(List<String> lines) {
        final Set<String> names = new TreeSet<>();
        for (int start = 0; start < lines.size(); start++) {
            if (!KEY_GATE_ANNOTATION.matcher(lines.get(start)).find()) {
                continue;
            }
            final StringBuilder annotation = new StringBuilder();
            int depth = 0;
            int line = start;
            do {
                annotation.append(lines.get(line)).append('\n');
                depth += parenthesisBalance(lines.get(line));
                line++;
            } while (depth > 0 && line < lines.size());
            final Matcher name = KEY_GATE_NAME.matcher(annotation);
            while (name.find()) {
                names.add(name.group(1));
            }
        }
        return names;
    }

    /** Opening minus closing parentheses on one line of Java, not counting those inside a string literal. */
    private static int parenthesisBalance(String line) {
        int balance = 0;
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                balance++;
            } else if (c == ')') {
                balance--;
            }
        }
        return balance;
    }

    /** Distinct {@code @Tag} values annotated on tests anywhere under {@code modules/} and {@code samples/}. */
    private static Set<String> testTagsInRepository() throws IOException {
        final Set<String> tags = new TreeSet<>();
        for (final String root : List.of("modules", "samples")) {
            final Path dir = REPOSITORY_ROOT.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (final Path file : files.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.toString().contains("/src/test/")).toList()) {
                    for (final String line : Files.readAllLines(file)) {
                        final Matcher matcher = TEST_TAG_ANNOTATION.matcher(line);
                        if (matcher.find()) {
                            tags.add(matcher.group(1));
                        }
                    }
                }
            }
        }
        assertThat(tags)
                .withFailMessage(
                        "found no @Tag annotations under modules/ or samples/ — the scan is broken, " + "not clean")
                .isNotEmpty();
        return tags;
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

    /**
     * Task names the {@code /release} skill tells the operator the gate runs. Fails rather than returns empty when the
     * phrase is gone: a skill that no longer states its gate is the condition this test exists to catch, not a reason
     * to pass quietly.
     */
    private static List<String> skillDeclaredGateTasks() throws IOException {
        final Path skill = REPOSITORY_ROOT.resolve(RELEASE_SKILL);
        assertThat(skill).withFailMessage("%s not found — this test is pointed at the wrong path", RELEASE_SKILL)
                .isRegularFile();

        final Matcher matcher = SKILL_GATE_DECLARATION.matcher(Files.readString(skill));
        if (!matcher.find()) {
            throw new AssertionError("no `" + SKILL_GATE_DECLARATION.pattern() + "` match in " + RELEASE_SKILL
                    + " — the skill stopped stating which tasks gate a release, or the sentence was reworded. Restore "
                    + "the phrase or repoint this test; do not delete the claim and leave the operator guessing.");
        }
        return List.of(matcher.group(1).strip().split("\\s+"));
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

    /** What one sandboxed run of the release script did. */
    private static final class ScriptRun {

        private final int exitCode;

        private final String output;

        /** Each line is one call's arguments; empty when the stub was never called. */
        private final List<String> gitCalls;

        private ScriptRun(int exitCode, String output, List<String> gitCalls) {
            this.exitCode = exitCode;
            this.output = output;
            this.gitCalls = List.copyOf(gitCalls);
        }

        @Override
        public String toString() {
            return "exit " + exitCode + ", git calls " + gitCalls + ", output:" + System.lineSeparator() + output;
        }
    }
}
