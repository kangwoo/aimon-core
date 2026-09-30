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
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.ContextEngineKind;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.context.RollingContextEngine;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogPage;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;

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

    /**
     * The end-to-end claim behind the switch: a rolling agent does not only start on the CLI with {@code v2}, it runs
     * turns there. One real turn goes through the CLI's own live session against a stub model; then the turn's opening
     * entries — with a bulky message after them, to clear the minimum sealable size — are hidden and saved, which seals
     * them into the in-memory segment store the CLI paired with the in-memory
     * records, and the log reader reads the whole session back without a gap. The seal is driven by hand rather than
     * by the rolling engine's own compaction, which needs a context window the stub turn does not fill.
     */
    @Test
    @DisplayName("with v2 a rolling agent runs a real turn on the CLI, and its log seals and reads back whole")
    void rollingAgentRunsATurnOnVersionTwo() {
        final List<Integer> calls = new ArrayList<>();
        final LlmClientFactory stubModel = new LlmClientFactory() {
            @Override
            public LlmClient create(LlmProviderConfig config) {
                return new LlmClient() {
                    @Override
                    public LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                            List<ToolDefinition> tools, LlmModel modelConfig) {
                        calls.add(messages.size());
                        return LlmResponse.text("pong");
                    }

                    @Override
                    public String getProviderName() {
                        return "stub";
                    }
                };
            }
        };

        try (AgentSetup setup = new AgentSetupFactory(stubModel, AgentSetupFactoryLogWriteFormatTest::rollingBundle)
                .create(config(SessionLogFormat.V2))) {
            assertThat(setup.getAgentRuntime().getContextEngine()).isInstanceOf(RollingContextEngine.class);
            final LiveSession session = setup.getLiveSession();

            final AgentExecutionResult result = session.submit("ping");

            assertThat(result.isSuccess()).as(String.valueOf(result.getErrorMessage())).isTrue();
            assertThat(result.getFinalAnswer()).isEqualTo("pong");
            assertThat(calls).as("the stub model answered the turn").hasSize(1);

            final TranscriptManager manager = setup.getAgentExecutor().getTranscriptManager();
            final TranscriptBuffer saved = manager.initialize(session.getSessionId(), "You are a probe.");
            assertThat(saved.toSnapshot().getLogState().getFormat()).isEqualTo(SessionLogFormat.V2);
            final long entries = saved.liveEntryCount();
            assertThat(entries).as("the turn's user message and answer are in the record").isGreaterThanOrEqualTo(2);

            // Large enough to clear the default minimum sealable run (SessionLogStorage.DEFAULT_MIN_SEAL_TOKENS).
            final String bulky = "context ".repeat(40_000);
            saved.addUserMessage(bulky);
            saved.addAssistantMessage("again");
            saved.summarizeView(SummarySpan.builder().fromSeq(0).toSeq(entries + 1).summaryText("the first turn")
                    .boundaryId("cli-probe").trigger("AUTO").build());
            manager.saveSilently(saved);

            final SessionLogState stored = manager.initialize(session.getSessionId(), "You are a probe.").toSnapshot()
                    .getLogState();
            assertThat(stored.getManifest()).as("the hidden range was sealed into a segment").isNotEmpty();
            final SessionLogPage page = manager.getLogReader().orElseThrow().read(session.getSessionId(), 0,
                    Long.MAX_VALUE);
            assertThat(page.getGaps()).isEmpty();
            // Compared by content without printing it: a failure would otherwise dump the bulky message.
            final List<String> read = page.getEntries().stream().map(entry -> entry.getMessage().getContent()).toList();
            assertThat(read.subList(0, 2)).as("the sealed turn reads back from the segment").containsExactly("ping",
                    "pong");
            assertThat(read.contains(bulky)).as("the bulky sealed entry reads back").isTrue();
        }
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
