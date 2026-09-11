package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackBuilder;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.cli.config.AgentConfig;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.factory.AgentModelProviderCheck.DeclaredModel;
import at.aimon.cli.factory.AgentModelProviderCheck.Origin;
import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AdaptiveAgentBundleLoader;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The factory seam that prints #92's startup warning: that it prints through the CLI's startup channel, that nothing
 * it reads can stop startup, and that the file each line names is the one the real stack wiring resolved.
 */
@DisplayName("AgentSetupFactory.reportAgentModelMismatch")
class AgentSetupFactoryAgentModelCheckTest {

    private static final String WORKING_DIRECTORY = "/work";

    /** Never called — no test here runs a turn. */
    private static final LlmClient STUB_LLM = new LlmClient() {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    };

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final Logger factoryLog = (Logger) LoggerFactory.getLogger(AgentSetupFactory.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    private AgentSetupFactory factory;
    private OutputFormatter formatter;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(stdout));
        logEvents.start();
        factoryLog.addAppender(logEvents);
        final CliSettings settings = new CliSettings();
        settings.setColorOutput(false);
        formatter = new OutputFormatter(settings);
        factory = new AgentSetupFactory();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        factoryLog.detachAppender(logEvents);
    }

    private static CliConfig config(String provider, String agentName) {
        final LlmProviderConfig llm = new LlmProviderConfig();
        llm.setProvider(provider);
        final AgentConfig agent = new AgentConfig();
        agent.setName(agentName);
        final CliConfig config = new CliConfig();
        config.setLlmConfig(llm);
        config.setAgentConfig(agent);
        return config;
    }

    private static AgentBundle bundle(String mainModel, SubagentRegistry bundleSubagents) {
        return AgentBundle.builder()
                .agent(DefaultAgent.builder().name("test-agent").systemPrompt("You are a test agent.")
                        .model(LlmModel.builder().name(mainModel).build()).build())
                .subagentRegistry(bundleSubagents).build();
    }

    private static Subagent subagent(String name, String model) {
        return Subagent.of(name, SubagentMetadata.builder().description("test subagent").model(model).build(),
                SubagentContent.of("You are a test subagent."));
    }

    private static SubagentRegistry registryOf(Subagent... subagents) {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        for (Subagent subagent : subagents) {
            registry.register(subagent);
        }
        return registry;
    }

    private void report(String provider, AgentBundle bundle, Supplier<SubagentRegistry> runtimeSubagents,
            Supplier<String> workingDirectory) {
        factory.reportAgentModelMismatch(config(provider, "default"), bundle, runtimeSubagents, workingDirectory,
                formatter);
    }

    private String printed() {
        return stdout.toString();
    }

    private List<ILoggingEvent> warnings() {
        return logEvents.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    @Nested
    @DisplayName("E. the seam prints once and never stops startup")
    class Seam {

        @Test
        @DisplayName("E1 a mismatch prints to the terminal and is paired with a WARN for the log file")
        void printsAndLogs() {
            report("anthropic", bundle("gpt-5.6-terra", null), InMemorySubagentRegistry::new, () -> WORKING_DIRECTORY);

            assertThat(printed()).contains("Agent model:", "model.name: gpt-5.6-terra");
            assertThat(warnings()).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                    .contains("Agent model:", "model.name: gpt-5.6-terra");
        }

        @Test
        @DisplayName("E2 a matching pair prints nothing")
        void matchingPairIsSilent() {
            report("anthropic", bundle("claude-sonnet-4-5", null), InMemorySubagentRegistry::new,
                    () -> WORKING_DIRECTORY);

            assertThat(printed()).isEmpty();
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("E3 a runtime-registry supplier that throws costs the message, logs, and nothing else")
        void throwingRuntimeSupplier() {
            assertThatCode(() -> report("anthropic", bundle("gpt-5.6-terra", null), () -> {
                throw new IllegalStateException("registry unavailable");
            }, () -> WORKING_DIRECTORY)).doesNotThrowAnyException();

            assertThat(printed()).isEmpty();
            assertThat(warnings()).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                    .contains("Agent model check skipped", "registry unavailable");
        }

        @Test
        @DisplayName("E4 the bundle registry's getSubagent throws on the path the check takes")
        void throwingBundleLookup() {
            final SubagentRegistry throwingBundle = new StubRegistry() {
                @Override
                public Optional<Subagent> getSubagent(String subagentName) {
                    throw new IllegalStateException("bundle lookup failed");
                }
            };

            assertThatCode(() -> report("anthropic", bundle("claude-sonnet-4-5", throwingBundle),
                    () -> registryOf(subagent("explore", "gpt-5.1")), () -> WORKING_DIRECTORY))
                    .doesNotThrowAnyException();

            assertThat(printed()).isEmpty();
        }

        @Test
        @DisplayName("E4' the same inputs with a lookup that answers empty print the user path — E4's throw is on the path")
        void emptyBundleLookup() {
            final SubagentRegistry emptyBundle = new StubRegistry() {
                @Override
                public Optional<Subagent> getSubagent(String subagentName) {
                    return Optional.empty();
                }
            };

            report("anthropic", bundle("claude-sonnet-4-5", emptyBundle),
                    () -> registryOf(subagent("explore", "gpt-5.1")), () -> WORKING_DIRECTORY);

            assertThat(printed()).contains("/work/.aimon/agents/explore.md");
        }

        @Test
        @DisplayName("E5 a runtime registry whose getAllSubagents throws costs the message and nothing else")
        void throwingListing() {
            final SubagentRegistry throwingRuntime = new StubRegistry() {
                @Override
                public List<Subagent> getAllSubagents() {
                    throw new IllegalStateException("listing failed");
                }
            };

            assertThatCode(() -> report("anthropic", bundle("gpt-5.6-terra", null), () -> throwingRuntime,
                    () -> WORKING_DIRECTORY)).doesNotThrowAnyException();

            assertThat(printed()).isEmpty();
        }

        @Test
        @DisplayName("E6 a runtime-registry supplier that answers null checks the main agent only")
        void nullRuntimeRegistry() {
            assertThatCode(
                    () -> report("anthropic", bundle("gpt-5.6-terra", null), () -> null, () -> WORKING_DIRECTORY))
                    .doesNotThrowAnyException();

            assertThat(printed()).contains("main agent").doesNotContain("subagent `");
        }

        @Test
        @DisplayName("E7 a working-directory supplier that throws costs the message and nothing else")
        void throwingWorkingDirectory() {
            assertThatCode(
                    () -> report("anthropic", bundle("gpt-5.6-terra", null), InMemorySubagentRegistry::new, () -> {
                        throw new IllegalStateException("no working directory");
                    })).doesNotThrowAnyException();

            assertThat(printed()).isEmpty();
        }
    }

    /**
     * Origin is instance identity alone, and identity rests on facts spread over three modules: the stack builder
     * passes the loaded bundle through unchanged, {@code OrcaAgentRuntimeFactory} layers the bundle's own registry
     * object first and a separate {@code DefaultSubagentRegistry} over {@code .aimon/agents} second, and every registry
     * involved returns the instances it stored rather than copies. On the test classpath the bundle resolves as
     * {@code file://}, so its own registry is {@code AdaptiveAgentBundleLoader}'s two-layer composite, which is covered
     * too.
     *
     * <p>
     * <strong>F1 is the guard.</strong> A registry that starts returning copies, or wiring that wraps or re-parses the
     * bundle's registry before layering it, would label every bundled subagent {@code OUTSIDE_BUNDLE} and print it at a
     * {@code .aimon/agents} path that does not exist. The message has no hedge for that; this test fails first.
     */
    @Nested
    @DisplayName("F. provenance through the real stack wiring")
    class Provenance {

        @TempDir
        Path tempDir;

        private final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader("agents");

        private AimonStack stackFor(AgentBundle bundle) {
            final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
            fileSystem.initialize();
            return AimonStackBuilder.build(
                    AimonStackSpec.builder().llm(LlmSpec.of(STUB_LLM)).fileSystem(FileSystemSpec.supplied(fileSystem))
                            .agent(AgentSpec.builder().bundle(bundle).build()).build());
        }

        private void userSubagentFile(String name, String model) throws IOException {
            final Path directory = tempDir.resolve(".aimon/agents");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name + ".md"),
                    "---\nname: " + name
                            + "\ndescription: \"A user-authored subagent.\"\nallowed-tools: Read, Grep, Glob\nmodel: "
                            + model + "\n---\nYou are a user-authored subagent with a prompt of its own.\n");
        }

        private List<DeclaredModel> declaredThroughStack(String bundleName) throws Exception {
            final AgentBundle bundle = loader.load(bundleName);
            try (AimonStack stack = stackFor(bundle)) {
                final OrcaAgentRuntime runtime = stack.runtime(stack.primaryRuntimeId()).orElseThrow();
                return AgentModelProviderCheck.declaredModels(bundle.getAgent(), bundle.getSubagentRegistry(),
                        runtime.getSubagentRegistry());
            }
        }

        private Origin originOf(List<DeclaredModel> models, String subagentName) {
            return models.stream().filter(model -> subagentName.equals(model.getSubagentName())).findFirst()
                    .orElseThrow(() -> new AssertionError("no entry for subagent " + subagentName)).getOrigin();
        }

        // F1, F2 and F4 load `default`: since #104 it is the only shipped bundle whose subagent still names a model of
        // its own, and a subagent that names none is not an entry at all.

        @Test
        @DisplayName("F1 the guard: a bundled subagent the runtime resolves is the bundle's own instance")
        void bundledSubagentIsBundle() throws Exception {
            assertThat(originOf(declaredThroughStack("default"), "explore")).isEqualTo(Origin.BUNDLE);
        }

        @Test
        @DisplayName("F2 a new user subagent is OUTSIDE_BUNDLE, and the bundled one stays BUNDLE")
        void newUserSubagent() throws Exception {
            userSubagentFile("reviewer", "gpt-4o");

            final List<DeclaredModel> models = declaredThroughStack("default");

            assertThat(originOf(models, "reviewer")).isEqualTo(Origin.OUTSIDE_BUNDLE);
            assertThat(originOf(models, "explore")).isEqualTo(Origin.BUNDLE);
        }

        @Test
        @DisplayName("F3 a user file shadowing a bundled subagent that names no model is OUTSIDE_BUNDLE")
        void shadowWithAnotherModel() throws Exception {
            userSubagentFile("explore", "gpt-5.1");

            assertThat(originOf(declaredThroughStack("default-anthropic"), "explore")).isEqualTo(Origin.OUTSIDE_BUNDLE);
        }

        @Test
        @DisplayName("F4 a user copy that keeps the bundled model is still OUTSIDE_BUNDLE — the same row as F3")
        void shadowKeepingTheBundledModel() throws Exception {
            userSubagentFile("explore", "gpt-5.1");

            assertThat(originOf(declaredThroughStack("default"), "explore")).isEqualTo(Origin.OUTSIDE_BUNDLE);
        }

        @Test
        @DisplayName("F5 review 2's variant end to end: the printed path is the file the test wrote, the bundled one is not printed")
        void userCopyUnderTheShippedDefault() throws Exception {
            userSubagentFile("explore", "gpt-5.1");
            final AgentBundle bundle = loader.load("default");

            try (AimonStack stack = stackFor(bundle)) {
                final OrcaAgentRuntime runtime = stack.runtime(stack.primaryRuntimeId()).orElseThrow();
                factory.reportAgentModelMismatch(config("anthropic", "default"), bundle, runtime::getSubagentRegistry,
                        () -> runtime.getEnvironment().getWorkingDirectory(), formatter);

                assertThat(printed())
                        .contains("agents/default/agent.md", "`agent.name: default-anthropic`", "does not change them")
                        .doesNotContain("agents/default/agents/explore.md");
                // A file comparison, not a string one (#107): the printed path must name the file this test wrote,
                // whatever spelling the runtime's working directory gives it (macOS /var vs /private/var).
                final Matcher entry = Pattern.compile("subagent `explore`, `(.+?)`: `model: gpt-5\\.1`")
                        .matcher(printed());
                assertThat(entry.find()).as("an entry line for the user's explore.md").isTrue();
                assertThat(Files.isSameFile(Path.of(entry.group(1)), tempDir.resolve(".aimon/agents/explore.md")))
                        .as("the printed path %s names the written file", entry.group(1)).isTrue();
            }
        }
    }

    /** A registry with nothing in it, for tests that override the one method they make throw. */
    private static class StubRegistry implements SubagentRegistry {

        @Override
        public Optional<Subagent> getSubagent(String subagentName) {
            return Optional.empty();
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return List.of();
        }

        @Override
        public void reloadSubagent(String subagentName) {
            // nothing to reload
        }

        @Override
        public void reloadAll() {
            // nothing to reload
        }
    }
}
