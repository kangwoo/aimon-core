package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.AimonStack;
import at.aimon.cli.config.AgentConfig;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.config.MemoryConfig;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.config.hook.HookConfigParseException;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.memory.deriver.DerivationQueueManager;
import at.aimon.core.memory.deriver.Deriver;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * EE-71 on the CLI assembly: a {@code hooks.json} that does not load stops {@link AgentSetupFactory#create(CliConfig)}
 * with a {@link ConfigurationException} naming the file, instead of starting a REPL whose file guards are all off —
 * and what {@code create()} had already started is released before it throws.
 *
 * <p>
 * {@code user.home} is pointed at a temporary directory for the duration, so the broken file is the USER layer and no
 * developer's own {@code ~/.aimon/hooks.json} is involved. The real Anthropic client is built with a placeholder key
 * and sends nothing.
 */
@DisplayName("AgentSetupFactory.create: a broken hooks.json stops startup")
class AgentSetupFactoryBrokenHookConfigTest {

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final String originalUserHome = System.getProperty("user.home");
    private final List<LlmClient> clients = new ArrayList<>();
    private final ListAppender<ILoggingEvent> stackLog = new ListAppender<>();
    private final ch.qos.logback.classic.Logger stackLogger = (ch.qos.logback.classic.Logger) LoggerFactory
            .getLogger(AimonStack.class);

    @TempDir
    Path home;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(stdout));
        System.setProperty("user.home", home.toAbsolutePath().toString());
        stackLog.start();
        // The CLI's logback.xml keeps the framework quiet; the teardown line is INFO.
        stackLogger.setLevel(Level.INFO);
        stackLogger.addAppender(stackLog);
    }

    @AfterEach
    void restore() throws Exception {
        stackLogger.detachAppender(stackLog);
        stackLogger.setLevel(null);
        System.setProperty("user.home", originalUserHome);
        System.setOut(originalOut);
        for (LlmClient client : clients) {
            if (client instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private CliConfig config(Path memoryStorage) {
        final LlmProviderConfig llm = new LlmProviderConfig();
        llm.setProvider("anthropic");
        llm.setApiKey("sk-ant-placeholder");
        final AgentConfig agent = new AgentConfig();
        agent.setName("default-anthropic");
        final CliSettings settings = new CliSettings();
        settings.setColorOutput(false);
        // Memory on, so there is a derivation queue with a running worker pool to leak.
        final MemoryConfig memory = new MemoryConfig();
        memory.setWorkspaceId("ws-probe");
        memory.setPeerId("peer-probe");
        memory.setStoragePath(memoryStorage.toString());
        memory.setBackend("in-memory");
        final CliConfig config = new CliConfig();
        config.setLlmConfig(llm);
        config.setAgentConfig(agent);
        config.setCliSettings(settings);
        config.setMemoryConfig(memory);
        return config;
    }

    @Test
    @DisplayName("create() throws a ConfigurationException naming the file and the cause, and releases what it started")
    void brokenHooksJsonStopsCreateAndReleasesTheStack(@TempDir Path work) throws Exception {
        final Path hooksFile = Files.createDirectories(home.resolve(".aimon")).resolve("hooks.json");
        Files.writeString(hooksFile, "{\"hooks\":{\"PreToolUse\":[\n  {not valid json");
        final List<DerivationQueueManager> queues = new ArrayList<>();
        final AgentSetupFactory factory = new AgentSetupFactory(new LlmClientFactory() {
            @Override
            public LlmClient create(LlmProviderConfig config) {
                final LlmClient client = super.create(config);
                clients.add(client);
                return client;
            }
        }, null) {
            @Override
            DerivationQueueManager buildDerivationQueue(Deriver deriver) {
                final DerivationQueueManager queue = spy(super.buildDerivationQueue(deriver));
                queues.add(queue);
                return queue;
            }
        };

        assertThatThrownBy(() -> factory.create(config(work.resolve("representations.jsonl"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("hooks config " + hooksFile.toAbsolutePath())
                .hasMessageContaining("(USER layer) is invalid").hasMessageContaining("line: 2")
                .hasMessageContaining("fix or remove the file").hasCauseInstanceOf(HookConfigParseException.class);

        // The stack ran its teardown plan — which holds the host shell and the script engines by then ...
        assertThat(stackLog.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.startsWith("Shutting down AIMON stack"));
        // ... and the queue, which is enrolled on that plan only after the step that threw, was stopped by hand.
        assertThat(queues).hasSize(1);
        verify(queues.get(0)).stop();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "a guard handler with no command | {\"hooks\":{\"preTool\":[{\"hooks\":[{\"type\":\"command\"}]}]}}"
                    + " | preTool entry #0, handler #0",
            "an event name one letter off a guard event"
                    + " | {\"hooks\":{\"preTol\":[{\"hooks\":[{\"type\":\"deny\",\"reason\":\"no\"}]}]}}"
                    + " | did you mean 'preTool'",
            // The CLI wires no http executor: this guard was never asked, and used to be registered all the same.
            "an http guard, which the CLI cannot run"
                    + " | {\"hooks\":{\"preTool\":[{\"hooks\":[{\"type\":\"http\",\"url\":\"https://example.test/h\"}]}]}}"
                    + " | no HttpActionExecutor is wired"})
    @DisplayName("a hooks.json that parses but has a guard entry that cannot be applied stops create() the same way")
    void inapplicableGuardEntryStopsCreate(String what, String json, String expected, @TempDir Path work)
            throws Exception {
        final Path hooksFile = Files.createDirectories(home.resolve(".aimon")).resolve("hooks.json");
        Files.writeString(hooksFile, json);
        final AgentSetupFactory factory = new AgentSetupFactory(new LlmClientFactory() {
            @Override
            public LlmClient create(LlmProviderConfig config) {
                final LlmClient client = super.create(config);
                clients.add(client);
                return client;
            }
        }, null);

        assertThatThrownBy(() -> factory.create(config(work.resolve("representations.jsonl"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("hooks config " + hooksFile.toAbsolutePath())
                .hasMessageContaining("(USER layer) is invalid").hasMessageContaining(expected)
                .hasMessageContaining("fix or remove the file").hasCauseInstanceOf(HookConfigParseException.class);
        assertThat(stackLog.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.startsWith("Shutting down AIMON stack"));
    }

    @Test
    @DisplayName("without a hooks.json the same configuration starts")
    void missingHooksJsonStarts(@TempDir Path work) {
        try (AgentSetupFactory.AgentSetup setup = new AgentSetupFactory(new LlmClientFactory() {
            @Override
            public LlmClient create(LlmProviderConfig config) {
                final LlmClient client = super.create(config);
                clients.add(client);
                return client;
            }
        }, null).create(config(work.resolve("representations.jsonl")))) {
            assertThat(setup.getAgentBundleName()).isEqualTo("default-anthropic");
        }
    }
}
