package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.ImportRuntimeHints;

import at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser;
import at.aimon.core.agent.impl.AdaptiveAgentBundleLoader;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.tools.todo.Todo;
import at.aimon.core.tools.todo.TodoStatus;

/**
 * Verifies what {@link AimonRuntimeHints} declares — not that a native image works.
 *
 * <p>
 * The distinction matters and is the whole reason this file can exist. There is no native build in this
 * repository, and for a long time that was recorded as "no way to verify the hints". It is not: hints are a
 * declaration, {@code RuntimeHintsPredicates} reads that declaration with the same matching logic the native
 * build ships (Spring writes {@code ResourcePatternHint#toRegex()} straight into {@code resource-config.json}),
 * and that is how Spring Boot verifies its own. What a predicate test cannot tell us is whether the declared set
 * is <em>sufficient</em> — only a real image can say that. So these tests are worth exactly this much: the
 * registrar declares what we meant it to declare, and it stays wired to something that is always active.
 */
class AimonRuntimeHintsTest {

    private final RuntimeHints hints = new RuntimeHints();

    @BeforeEach
    void registerHints() {
        new AimonRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Nested
    @DisplayName("wiring")
    class Wiring {

        @Test
        @DisplayName("hangs off the autoconfiguration that is active whenever the starter is present")
        void importedFromTheAlwaysOnEntryPoint() {
            final ImportRuntimeHints imported = AimonAutoConfiguration.class.getAnnotation(ImportRuntimeHints.class);

            assertThat(imported).as("@ImportRuntimeHints on AimonAutoConfiguration").isNotNull();
            assertThat(imported.value()).contains(AimonRuntimeHints.class);
        }
    }

    @Nested
    @DisplayName("bundle resources")
    class BundleResources {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"agents/hints-probe/agent.md", "agents/hints-probe/agents/index",
                "agents/hints-probe/agents/probe.md", "agents/hints-probe/skills/index",
                "agents/hints-probe/skills/probe/SKILL.md", "agents/hints-probe/skills/probe/reference/checklist.md"})
        @DisplayName("every shape a bundle is read from is covered")
        void coversEveryBundleShape(String resource) {
            assertThat(RuntimeHintsPredicates.resource().forResource(resource)).accepts(hints);
        }

        @Test
        @DisplayName("nothing outside the bundle root is dragged into the image")
        void doesNotCoverUnrelatedResources() {
            assertThat(RuntimeHintsPredicates.resource().forResource("application.yml")).rejects(hints);
            assertThat(RuntimeHintsPredicates.resource().forResource("META-INF/spring.factories")).rejects(hints);
        }
    }

    @Nested
    @DisplayName("todo binding")
    class TodoBinding {

        @Test
        @DisplayName("the creator Jackson calls is reachable")
        void registersTheTodoCreator() throws NoSuchMethodException {
            assertThat(RuntimeHintsPredicates.reflection()
                    .onConstructor(Todo.class.getDeclaredConstructor(String.class, TodoStatus.class, String.class))
                    .invoke()).accepts(hints);
            assertThat(RuntimeHintsPredicates.reflection().onType(Todo.class)
                    .withMemberCategories(MemberCategory.DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                    .accepts(hints);
        }

        @Test
        @DisplayName("the enum's @JsonCreator and @JsonValue pair is reachable")
        void registersTheStatusCreatorAndValue() {
            assertThat(RuntimeHintsPredicates.reflection().onMethod(TodoStatus.class, "fromValue").invoke())
                    .accepts(hints);
            assertThat(RuntimeHintsPredicates.reflection().onMethod(TodoStatus.class, "getValue").invoke())
                    .accepts(hints);
        }
    }

    @Nested
    @DisplayName("quartz jobs")
    class QuartzJobs {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"at.aimon.scheduling.quartz.QuartzTaskScheduler$DelegatingJob",
                "at.aimon.scheduling.quartz.rewake.QuartzRewakeService$RewakeJob",
                "at.aimon.scheduling.quartz.dreamer.DreamerJob"})
        @DisplayName("each job Quartz instantiates by name can be constructed")
        void registersJobConstructors(String className) throws ClassNotFoundException {
            assertThat(RuntimeHintsPredicates.reflection().onType(Class.forName(className))
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)).accepts(hints);
        }

        @Test
        @DisplayName("the names are the ones the scheduler module actually declares")
        void namesResolveOnAClassPathThatHasTheModule() {
            assertThat(AimonRuntimeHints.QUARTZ_JOB_CLASS_NAMES).allSatisfy(
                    name -> assertThat(Class.forName(name, false, getClass().getClassLoader())).isNotNull());
        }

        @Test
        @DisplayName("an application without the optional scheduler module gets no dangling hint")
        void registersNothingWhenTheModuleIsAbsent() {
            final RuntimeHints withoutQuartz = new RuntimeHints();
            try (FilteredClassLoader filtered = new FilteredClassLoader("at.aimon.scheduling.quartz")) {
                new AimonRuntimeHints().registerHints(withoutQuartz, filtered);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }

            assertThat(withoutQuartz.reflection().typeHints().map(hint -> hint.getType().getName()))
                    .noneMatch(name -> name.startsWith("at.aimon.scheduling.quartz"));
            assertThat(RuntimeHintsPredicates.resource().forResource("agents/hints-probe/agent.md"))
                    .as("the rest of the declaration is unaffected").accepts(withoutQuartz);
        }
    }

    @Nested
    @DisplayName("drift guard")
    class DriftGuard {

        /**
         * Runs the loader the starter actually uses and asserts the hints cover what it asked for.
         *
         * <p>
         * The paths a bundle is read from are assembled inside {@code aimon-core} from private constants. Nothing
         * stops core from adding a sixth shape, and a starter that pinned five would then be silently short by
         * one — the failure mode being an agent that comes up with no skills and a WARN nobody reads. So instead
         * of restating core's constants here, this drives the real loader against a fixture bundle through a
         * class loader that records every name it is asked for, and requires the declaration to cover all of them.
         */
        @Test
        @DisplayName("everything the real loader asks the class loader for is covered")
        void coversWhateverTheLoaderAsksFor() {
            final RecordingClassLoader recording = new RecordingClassLoader(getClass().getClassLoader());

            final AgentBundle bundle = new AdaptiveAgentBundleLoader("agents", new MarkdownAgentDefinitionParser(),
                    recording).load("hints-probe");
            // Loading the bundle only reads the indexes; skill bodies are resolved on demand, and "on demand"
            // still happens inside a native image.
            bundle.getSkillRegistry().orElseThrow().getAllSkills();
            bundle.getSubagentRegistry().orElseThrow().getAllSubagents();

            final Set<String> files = filesAmong(recording.requested());
            assertThat(files).as("the loader must really have walked the fixture").contains(
                    "agents/hints-probe/agent.md", "agents/hints-probe/agents/index",
                    "agents/hints-probe/agents/probe.md", "agents/hints-probe/skills/index",
                    "agents/hints-probe/skills/probe/SKILL.md");
            assertThat(files).allSatisfy(
                    resource -> assertThat(RuntimeHintsPredicates.resource().forResource(resource)).accepts(hints));
        }

        /**
         * Keeps only the recorded names that resolve to a real file. Directory probes are dropped because a
         * directory is not a resource a hint can carry — that gap is real and is documented on the registrar,
         * not papered over here.
         */
        private Set<String> filesAmong(List<String> recorded) {
            final ClassLoader loader = getClass().getClassLoader();
            final Set<String> files = new LinkedHashSet<>();
            for (String name : recorded) {
                final URL url = loader.getResource(name);
                if (url == null || !"file".equals(url.getProtocol())) {
                    continue;
                }
                try {
                    if (Files.isRegularFile(Path.of(url.toURI()))) {
                        files.add(name);
                    }
                } catch (URISyntaxException e) {
                    // Not addressable as a file; it cannot be a bundle resource either.
                }
            }
            return files;
        }
    }

    /** Delegating class loader that remembers every resource name it was asked for. */
    private static final class RecordingClassLoader extends ClassLoader {

        private final List<String> requested = new ArrayList<>();

        RecordingClassLoader(ClassLoader parent) {
            super(parent);
        }

        List<String> requested() {
            return List.copyOf(requested);
        }

        @Override
        public URL getResource(String name) {
            requested.add(name);
            return super.getResource(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            requested.add(name);
            return super.getResourceAsStream(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            requested.add(name);
            return super.getResources(name);
        }
    }
}
