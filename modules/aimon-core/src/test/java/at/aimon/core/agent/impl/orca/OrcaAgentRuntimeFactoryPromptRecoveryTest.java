package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.compact.DefaultPromptSizeRecoveryStrategy;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
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

/**
 * Verifies that {@link OrcaAgentRuntimeFactory} wires a {@link DefaultPromptSizeRecoveryStrategy} into
 * every context it materializes, so a {@code PromptTooLong} error is recovered (oldest droppable user message dropped
 * and the turn retried) instead of aborting. The recovery behaviour itself is exercised by
 * {@code OrcaAgentExecutorPromptSizeRecoveryTest}; this test guards the factory <em>wiring</em> against regression.
 */
@DisplayName("OrcaAgentRuntimeFactory prompt-size recovery wiring")
class OrcaAgentRuntimeFactoryPromptRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("factory-created context carries a DefaultPromptSizeRecoveryStrategy")
    void factoryWiresDefaultPromptSizeRecoveryStrategy() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();

        final Agent agent = DefaultAgent.builder().name("TestAgent").maxIterations(10)
                .systemPrompt("You are a test agent").build();
        final OrcaAgentExecutor executor = createExecutor();

        final OrcaAgentRuntime context = new OrcaAgentRuntimeFactory().create(AgentRuntimeId.from(agent), executor,
                null, agent, fileSystem, null, List.of(), List.of());

        assertThat(context.getPromptSizeRecoveryStrategy()).isPresent().get()
                .isInstanceOf(DefaultPromptSizeRecoveryStrategy.class);
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
