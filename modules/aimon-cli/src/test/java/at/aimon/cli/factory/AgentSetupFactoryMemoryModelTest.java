package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.config.MemoryConfig;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The one model name every peer-memory component receives (#105): {@code llm.model} when set, the client's own default
 * model otherwise — said on startup — and a {@link ConfigurationException} naming the key when neither exists.
 */
@DisplayName("AgentSetupFactory.memoryModelName")
class AgentSetupFactoryMemoryModelTest {

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final Logger factoryLog = (Logger) LoggerFactory.getLogger(AgentSetupFactory.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    private OutputFormatter formatter;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(stdout));
        logEvents.start();
        factoryLog.addAppender(logEvents);
        final CliSettings settings = new CliSettings();
        settings.setColorOutput(false);
        formatter = new OutputFormatter(settings);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        factoryLog.detachAppender(logEvents);
    }

    private static CliConfig config(boolean memoryOn, String llmModel) {
        final LlmProviderConfig llm = new LlmProviderConfig();
        llm.setProvider("anthropic");
        llm.setModel(llmModel);
        final CliConfig config = new CliConfig();
        config.setLlmConfig(llm);
        if (memoryOn) {
            final MemoryConfig memory = new MemoryConfig();
            memory.setWorkspaceId("ws-probe");
            memory.setPeerId("peer-probe");
            memory.setStoragePath(".aimon/test/representations.jsonl");
            config.setMemoryConfig(memory);
        }
        return config;
    }

    /** A client whose default model is {@code defaultModel} (null for none). It is never asked to send anything. */
    private static LlmClient clientWithDefault(String defaultModel) {
        return new LlmClient() {

            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public String getProviderName() {
                return "Stub";
            }

            @Override
            public Optional<String> getDefaultModelName() {
                return Optional.ofNullable(defaultModel);
            }
        };
    }

    /** A client that fails the test if the resolver so much as asks it for a default. */
    private static LlmClient clientNeverAsked() {
        return new LlmClient() {

            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public String getProviderName() {
                throw new AssertionError("the provider name was read");
            }

            @Override
            public Optional<String> getDefaultModelName() {
                throw new AssertionError("the client's default model was read");
            }
        };
    }

    private List<ILoggingEvent> warnings() {
        return logEvents.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("memory off: no name, nothing printed, and the client is not consulted")
    void memoryOff() {
        assertThat(AgentSetupFactory.memoryModelName(config(false, null), clientNeverAsked(), formatter)).isNull();

        assertThat(stdout.toString()).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("llm.model set: that name, and nothing printed")
    void configuredModelWins() {
        assertThat(AgentSetupFactory.memoryModelName(config(true, "claude-x"), clientNeverAsked(), formatter))
                .isEqualTo("claude-x");

        assertThat(stdout.toString()).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @ParameterizedTest(name = "llm.model = [{0}]")
    @NullSource
    @ValueSource(strings = {"  "})
    @DisplayName("llm.model absent or blank: the client's default model, said once on the terminal and in the log")
    void clientDefaultWhenAbsent(String llmModel) {
        assertThat(AgentSetupFactory.memoryModelName(config(true, llmModel), clientWithDefault("claude-d"), formatter))
                .isEqualTo("claude-d");

        assertThat(stdout.toString().lines()).singleElement().asString().contains("`llm.model` is not set",
                "Stub client's default model `claude-d`");
        assertThat(warnings()).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("`llm.model` is not set", "`claude-d`");
    }

    @Test
    @DisplayName("no llm.model and a client with no default: a ConfigurationException that names llm.model")
    void refusedWhenTheClientHasNoDefault() {
        assertThatThrownBy(
                () -> AgentSetupFactory.memoryModelName(config(true, null), clientWithDefault(null), formatter))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("`llm.model`");
    }

    @Test
    @DisplayName("llm.model \"\" reaches the Anthropic config as \"\": a blank client default is refused the same way")
    void refusedWhenBothAreBlank() {
        assertThatThrownBy(() -> AgentSetupFactory.memoryModelName(config(true, ""), clientWithDefault(""), formatter))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("`llm.model`");
        assertThat(stdout.toString()).isEmpty();
    }
}
