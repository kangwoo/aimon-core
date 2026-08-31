package at.aimon.sample.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves that an application assembled as a fat jar sees everything its dependencies ship.
 *
 * <p>
 * The claim under test is the one nothing else in this build can make. Every other test runs off a directory
 * class path, where a resource lookup is a file lookup and a directory walk is a directory walk. Packaged, both
 * become jar-entry enumeration through a URL scheme Spring Boot has changed once already — and the code that
 * enumerates them casts a {@code URLConnection} to {@code JarURLConnection} to do it. Whether that holds is a
 * fact about the world, not about this repository, so it is checked by launching a real jar rather than by
 * reading the code and concluding.
 *
 * <p>
 * Four shapes are compared, because each one can fail while the others pass:
 *
 * <ul>
 * <li><b>Two dependency jars</b> both contributing skills under the same resource path. Reading only the first
 * is a regression that a single-jar sample would never catch, and it is one this framework has had.
 * <li><b>Nested versus classic</b> Boot loaders — {@code jar:nested:} (3.2+) and {@code jar:file:} (before it).
 * An application on either must behave the same.
 * <li><b>Packaged versus exploded</b> — the deployment layout against the development layout. This is the only
 * test in the build that can see them diverge, and they have.
 * <li><b>A bundle whose skills carry no index</b>, which must be said out loud rather than silently loading
 * nothing.
 * </ul>
 *
 * <p>
 * Tagged {@code packaging}: these build two fat jars and launch three JVMs, so they are excluded from
 * {@code test} and run by {@code ./gradlew :aimon-sample-app:packagingTest}.
 */
@Tag("packaging")
@DisplayName("A packaged application sees everything its dependencies ship")
class FatJarPackagingTest {

    private static final String ALPHA_SKILL = "alpha-notes";
    private static final String BETA_SKILL = "beta-notes";
    private static final String BETA_SUBAGENT = "beta-explorer";
    private static final String SAMPLE_AGENT = "sample";
    private static final String NOINDEX_AGENT = "noindex";
    private static final String SCRIPTED_ANSWER = "sample agent answered without leaving the machine";

    @TempDir
    private static Path tempDir;

    private static SampleAppProcess nested;
    private static SampleAppProcess classic;
    private static SampleAppProcess exploded;

    private static Map<String, Object> nestedView;
    private static Map<String, Object> classicView;
    private static Map<String, Object> explodedView;

    @BeforeAll
    static void launchAll() {
        // Started once and shared. Each launch is a JVM and a Spring context; per-test launches would triple the
        // cost of the tier without changing a single assertion, since none of these tests mutate the app.
        nested = SampleAppProcess.launchJar("nested", jarPath("aimon.sample.bootJar"), tempDir.resolve("nested"));
        classic = SampleAppProcess.launchJar("classic", jarPath("aimon.sample.bootJarClassic"),
                tempDir.resolve("classic"));
        exploded = SampleAppProcess.launchExploded("exploded", requiredProperty("aimon.sample.explodedClasspath"),
                tempDir.resolve("exploded"));

        nestedView = nested.introspect();
        classicView = classic.introspect();
        explodedView = exploded.introspect();
    }

    @AfterAll
    static void stopAll() {
        closeQuietly(nested);
        closeQuietly(classic);
        closeQuietly(exploded);
    }

    @Test
    @DisplayName("both dependency jars contribute their skills, not just the first one on the class path")
    void bothDependencyJarsContributeSkills() {
        assertThat(nestedView.get("agentDefinitionProtocol"))
                .as("the packaged run must actually be reading resources out of a jar, or it proves nothing")
                .isEqualTo("jar");

        assertThat(stringList(nestedView, "skillIndexResources"))
                .as("both sample skill jars declare agents/sample/skills/index; a merge that stops at the first "
                        + "would still see one of them")
                .hasSize(2).anySatisfy(url -> assertThat(url).contains("aimon-sample-skills-alpha"))
                .anySatisfy(url -> assertThat(url).contains("aimon-sample-skills-beta"));

        assertThat(skillsOf(nestedView, SAMPLE_AGENT)).containsExactly(ALPHA_SKILL, BETA_SKILL);
    }

    @Test
    @DisplayName("skill trees are copied out of the jar whole, supplementary files included")
    void supplementaryFilesAreMaterialised() {
        // The distinction this makes is between a skill being advertised and its files being there. A registry
        // entry can exist for a skill whose reference/ directory was never copied; the agent finds out when it
        // tries to read one, which is far too late.
        assertThat(materialisedOf(nestedView, SAMPLE_AGENT)).containsExactlyInAnyOrder(
                ".aimon/bundled-skills/alpha-notes/SKILL.md",
                ".aimon/bundled-skills/alpha-notes/reference/checklist.md",
                ".aimon/bundled-skills/beta-notes/SKILL.md");
    }

    @Test
    @DisplayName("a real turn shows the model every skill and subagent that was packaged")
    void theModelIsShownEverythingThatWasPackaged() {
        final Map<String, Object> result = nested.turn("packaging-nested", "which+skills+do+you+have");

        assertThat(result.get("success")).as("turn failed: %s", result.get("error")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("answer")).isEqualTo(SCRIPTED_ANSWER);

        // Registries are one step short of the truth. Skills reach a model through the Skill tool's dynamically
        // built description and subagents through the Task tool's — so this is where a packaging failure would
        // finally show, and it is the only place that reflects what the model was actually told.
        final List<String> definitions = stringList(result, "toolDefinitions");
        assertThat(definitionNamed(definitions, "Skill")).contains(ALPHA_SKILL).contains(BETA_SKILL);
        assertThat(definitionNamed(definitions, "Task")).contains(BETA_SUBAGENT);
    }

    @Test
    @DisplayName("a bundle that ships skills with no index says so instead of loading nothing")
    void aBundleWithoutASkillIndexIsReported() {
        assertThat(skillsOf(nestedView, NOINDEX_AGENT))
                .as("the noindex bundle genuinely registers nothing — the point is whether anyone is told").isEmpty();

        assertThat(nested.output())
                .as("silently loading zero skills is indistinguishable from shipping none; at default log level "
                        + "an operator must see the difference")
                .contains("agents/noindex/skills").contains("no index file exists");
    }

    @Test
    @DisplayName("Boot's classic loader and its nested loader agree")
    void bothBootLoadersAgree() {
        assertThat(classicView.get("agentDefinitionProtocol")).isEqualTo("jar");
        assertSameAssembly(classicView, nestedView, "the classic (pre-3.2) Boot loader");
    }

    @Test
    @DisplayName("running exploded gives the same agent as running the jar")
    void explodedAndPackagedAgree() {
        assertThat(explodedView.get("agentDefinitionProtocol"))
                .as("the exploded run must actually be reading the agent definition off disk, or the comparison "
                        + "is between two identical things")
                .isEqualTo("file");

        // The divergence this guards against is not hypothetical and it is not symmetric: the packaged run reads
        // every class path root, while a filesystem loader is handed one directory and can only see what is in
        // it. A subagent shipped by a dependency jar is therefore the first thing to disappear — in development
        // only, which is the worst place for a difference to live.
        assertSameAssembly(explodedView, nestedView, "running exploded, as bootRun and every IDE do");
    }

    private static void assertSameAssembly(Map<String, Object> actual, Map<String, Object> expected, String what) {
        assertThat(skillsOf(actual, SAMPLE_AGENT)).as("skills seen when %s", what)
                .isEqualTo(skillsOf(expected, SAMPLE_AGENT));
        assertThat(subagentsOf(actual, SAMPLE_AGENT)).as("subagents seen when %s", what)
                .isEqualTo(subagentsOf(expected, SAMPLE_AGENT));
        assertThat(materialisedOf(actual, SAMPLE_AGENT)).as("files materialised when %s", what)
                .containsExactlyInAnyOrderElementsOf(materialisedOf(expected, SAMPLE_AGENT));
    }

    private static List<String> skillsOf(Map<String, Object> view, String agent) {
        return stringList(agentView(view, agent), "skills");
    }

    private static List<String> subagentsOf(Map<String, Object> view, String agent) {
        return stringList(agentView(view, agent), "subagents");
    }

    private static List<String> materialisedOf(Map<String, Object> view, String agent) {
        return stringList(agentView(view, agent), "materializedFiles");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> agentView(Map<String, Object> view, String agent) {
        final Map<String, Object> agents = (Map<String, Object>) view.get("agents");
        assertThat(agents).as("introspection reported no agents at all").isNotNull().containsKey(agent);
        return (Map<String, Object>) agents.get(agent);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> view, String key) {
        final Object value = view.get(key);
        assertThat(value).as("expected '%s' in %s", key, view.keySet()).isInstanceOf(List.class);
        return (List<String>) value;
    }

    /**
     * Finds the definition of one tool among the ones the model was shown.
     *
     * <p>
     * The definitions arrive as {@code name\ndescription}, so the name is matched on its own line rather than by
     * substring: "Task" appears inside {@code TaskList} and {@code TaskStop} too.
     */
    private static String definitionNamed(List<String> definitions, String name) {
        return definitions.stream().filter(d -> d.equals(name) || d.startsWith(name + "\n")).findFirst()
                .orElseThrow(() -> new AssertionError("The model was never shown a '" + name + "' tool. It saw: "
                        + definitions.stream().map(d -> d.split("\n", 2)[0]).toList()));
    }

    private static Path jarPath(String property) {
        return Path.of(requiredProperty(property));
    }

    private static String requiredProperty(String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("System property '" + property + "' was not set. These tests are wired "
                    + "by the packagingTest task; run them with ./gradlew :aimon-sample-app:packagingTest");
        }
        return value;
    }

    private static void closeQuietly(SampleAppProcess process) {
        if (process != null) {
            process.close();
        }
    }
}
