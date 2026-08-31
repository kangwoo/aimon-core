package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * CTX-05 behavioral test for {@link OrcaAgentExecutor}: verifies that the ReAct loop invokes the parts-aware
 * {@link LlmClient#sendMessage(SystemPromptParts, List, List, LlmModel, LlmCallMetadata)} overload rather than
 * the legacy {@code String}-based overload.
 */
@DisplayName("OrcaAgentExecutor dispatches to LlmClient.sendMessage(SystemPromptParts, ...)")
class OrcaAgentExecutorSendMessageOverloadTest {

    @TempDir
    Path tempDir;

    private RecordingLlmClient llmClient;
    private OrcaAgentExecutor executor;

    @BeforeEach
    void setUp() {
        llmClient = new RecordingLlmClient(LlmResponse.text("done"));
        executor = createExecutor(llmClient);
    }

    @Test
    @DisplayName("single-turn execution invokes the parts overload exactly once and not the String overload")
    void singleTurn_invokesPartsOverload_notStringOverload() {
        executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(llmClient.partsOverloadCalls).isEqualTo(1);
        assertThat(llmClient.stringOverloadCalls).isEqualTo(0);
        assertThat(llmClient.lastParts.get()).isNotNull();
        assertThat(llmClient.lastParts.get().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("parts passed to sendMessage contain the agent-content segment assembled by buildSystemPromptParts")
    void parts_containAgentContent() {
        executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        final SystemPromptParts parts = llmClient.lastParts.get();
        assertThat(parts).isNotNull();
        // The default agent's system prompt text flows through as part of the concatenated form.
        assertThat(parts.concatenated()).contains("You are a test agent");
    }

    private OrcaAgentRuntime createContext() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    /**
     * A fake {@link LlmClient} that implements BOTH the legacy String overload and the parts-aware overload so it
     * can record which entry point the caller used.
     */
    private static final class RecordingLlmClient implements LlmClient {
        private final LlmResponse response;
        int partsOverloadCalls;
        int stringOverloadCalls;
        final AtomicReference<SystemPromptParts> lastParts = new AtomicReference<>();

        RecordingLlmClient(LlmResponse response) {
            this.response = response;
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
            partsOverloadCalls++;
            lastParts.set(systemPromptParts);
            return response;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            stringOverloadCalls++;
            return response;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            stringOverloadCalls++;
            return response;
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }

    }
}
