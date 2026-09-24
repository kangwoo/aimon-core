package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.cli.config.AgentConfig;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.factory.AgentSetupFactory.AgentSetup;
import at.aimon.core.agent.ContextEngineKind;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.context.RollingContextEngine;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.llm.LlmClient;

/**
 * {@code cli.sessionLogWriteFormat}: the CLI writes version 1 unless told otherwise, and an agent declaring
 * {@code context-engine: rolling} starts only when it is told {@code v2}.
 */
@DisplayName("AgentSetupFactory — cli.sessionLogWriteFormat")
class AgentSetupFactoryLogWriteFormatTest {

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final List<LlmClient> created = new ArrayList<>();
    private final LlmClientFactory llmClientFactory = new LlmClientFactory() {
        @Override
        public LlmClient create(LlmProviderConfig config) {
            final LlmClient client = super.create(config);
            created.add(client);
            return client;
        }
    };

    @BeforeEach
    void captureStdout() {
        System.setOut(new PrintStream(stdout));
    }

    @AfterEach
    void restore() throws Exception {
        System.setOut(originalOut);
        for (LlmClient client : created) {
            if (client instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static CliConfig config(SessionLogFormat format) {
        final LlmProviderConfig llm = new LlmProviderConfig();
        llm.setProvider("anthropic");
        // A placeholder: building the client sends nothing, and no turn runs here.
        llm.setApiKey("sk-ant-placeholder");
        llm.setModel("claude-sonnet-4-5");
        final AgentConfig agent = new AgentConfig();
        agent.setName("rolling-probe");
        final CliSettings settings = new CliSettings();
        settings.setColorOutput(false);
        settings.setSessionLogWriteFormat(format);
        final CliConfig config = new CliConfig();
        config.setLlmConfig(llm);
        config.setAgentConfig(agent);
        config.setCliSettings(settings);
        return config;
    }

    private static AgentBundle rollingBundle(String name) {
        return AgentBundle.builder().agent(DefaultAgent.builder().name(name).systemPrompt("You are a probe.")
                .maxIterations(3).contextEngine(ContextEngineKind.ROLLING).build()).build();
    }

    @Test
    @DisplayName("version 1 needs no segment store; version 2 gets an in-memory one beside the in-memory records")
    void sessionSpecPairsTheSegmentStoreWithVersionTwo() {
        final SessionSpec v1 = AgentSetupFactory.buildSessionSpec(SessionLogFormat.V1);
        assertThat(v1.getLogWriteFormat()).isEqualTo(SessionLogFormat.V1);
        assertThat(v1.getSegmentStore()).isEmpty();

        final SessionSpec v2 = AgentSetupFactory.buildSessionSpec(SessionLogFormat.V2);
        assertThat(v2.getLogWriteFormat()).isEqualTo(SessionLogFormat.V2);
        assertThat(v2.getSegmentStore()).isPresent();
    }

    @Test
    @DisplayName("the default is version 1, and a rolling agent does not start on it")
    void rollingAgentFailsOnTheDefault() {
        assertThat(new CliSettings().getSessionLogWriteFormat()).isEqualTo(SessionLogFormat.V1);

        assertThatThrownBy(
                () -> new AgentSetupFactory(llmClientFactory, AgentSetupFactoryLogWriteFormatTest::rollingBundle)
                        .create(config(SessionLogFormat.V1)).close())
                .hasStackTraceContaining("rolling").hasStackTraceContaining("version 1");
    }

    @Test
    @DisplayName("with cli.sessionLogWriteFormat: v2 a rolling agent starts on the rolling engine")
    void rollingAgentStartsOnVersionTwo() {
        try (AgentSetup setup = new AgentSetupFactory(llmClientFactory,
                AgentSetupFactoryLogWriteFormatTest::rollingBundle).create(config(SessionLogFormat.V2))) {
            assertThat(setup.getAgentRuntime().getContextEngine()).isInstanceOf(RollingContextEngine.class);
        }
    }
}
