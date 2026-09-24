package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ContextEngineKind;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.context.DefaultContextEngine;
import at.aimon.core.agent.context.RollingContextEngine;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.tools.session.SessionHistoryTool;

/**
 * Which context engine {@link OrcaAgentRuntimeFactory} builds (context-engine §10): the deployment default, an agent's
 * own {@code context-engine}, and the refusal of rolling on a node that writes version-1 logs.
 */
@DisplayName("OrcaAgentRuntimeFactory context engine selection")
class OrcaAgentRuntimeFactoryContextEngineTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;

    @BeforeEach
    void setUp() {
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
    }

    private OrcaAgentRuntime create(OrcaAgentRuntimeFactory factory, Agent agent) {
        return factory.create(AgentRuntimeId.from(agent), createExecutor(), null, agent, fileSystem, null, List.of(),
                List.of());
    }

    private static Agent agent(ContextEngineKind kind) {
        return DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                .contextEngine(kind).build();
    }

    @Test
    @DisplayName("without any choice the agent gets the default engine and no SessionHistory tool")
    void theDefaultEngineByDefault() {
        final OrcaAgentRuntime runtime = create(new OrcaAgentRuntimeFactory(), agent(null));

        assertThat(runtime.getContextEngine()).isInstanceOf(DefaultContextEngine.class);
        assertThat(runtime.getToolRegistry().findByName(SessionHistoryTool.TOOL_NAME)).isEmpty();
    }

    @Test
    @DisplayName("rolling on a version-2 node builds the rolling engine and registers SessionHistory")
    void rollingOnAVersionTwoNode() {
        final OrcaAgentRuntime runtime = create(new OrcaAgentRuntimeFactory()
                .withContextEngine(ContextEngineKind.ROLLING).withSessionLogWriteFormat(SessionLogFormat.V2),
                agent(null));

        assertThat(runtime.getContextEngine()).isInstanceOf(RollingContextEngine.class);
        assertThat(runtime.getToolRegistry().findByName(SessionHistoryTool.TOOL_NAME)).isPresent();
    }

    @Test
    @DisplayName("an agent's own context-engine wins over the deployment default, both ways")
    void theAgentsChoiceWins() {
        final OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory()
                .withSessionLogWriteFormat(SessionLogFormat.V2);

        assertThat(create(factory, agent(ContextEngineKind.ROLLING)).getContextEngine())
                .isInstanceOf(RollingContextEngine.class);
        assertThat(create(factory.withContextEngine(ContextEngineKind.ROLLING), agent(ContextEngineKind.DEFAULT))
                .getContextEngine()).isInstanceOf(DefaultContextEngine.class);
    }

    @Test
    @DisplayName("rolling on a version-1 node fails when the runtime is built")
    void rollingOnAVersionOneNodeFails() {
        assertThatThrownBy(() -> create(new OrcaAgentRuntimeFactory(), agent(ContextEngineKind.ROLLING)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version 1")
                .hasMessageContaining("rolling");
    }

    private OrcaAgentExecutor createExecutor() {
        final StubLlmClient client = new StubLlmClient();
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    /** Minimal LLM client — never invoked in this test; the factory only reads plumbing accessors off the executor. */
    private static final class StubLlmClient implements LlmClient {
        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("unused");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return LlmResponse.text("unused");
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }

    }
}
