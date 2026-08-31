package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.compact.DefaultPromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.PromptSizeRecoveryDecision;
import at.aimon.core.agent.compact.PromptSizeRecoveryStrategy;
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
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Verifies CONV-COMPACT-01 Phase 3-1 prompt-too-long recovery:
 * <ul>
 * <li>When the LLM provider raises {@link LlmPromptTooLongException} and a {@link PromptSizeRecoveryStrategy} returns
 * {@code RETRY}, {@link OrcaAgentExecutor} installs the shortened message list and re-issues the LLM call.
 * <li>When no strategy is wired, the framework default ({@code NoOpPromptSizeRecoveryStrategy}) lets the exception
 * propagate to the ReAct loop's standard error path.
 * <li>The default {@link DefaultPromptSizeRecoveryStrategy} declines (and the exception propagates) when the only user
 * message is the most recent one — the strategy refuses to drop the user's current turn.
 * </ul>
 */
class OrcaAgentExecutorPromptSizeRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void recoveryStrategyShortensMemoryAndExecutorRetries() {
        final FailFirstThenSucceedClient llmClient = new FailFirstThenSucceedClient();
        final RecordingRecoveryStrategy strategy = new RecordingRecoveryStrategy();

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(strategy),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.getFinalAnswer()).isEqualTo("recovered");
        // First call rejected with prompt-too-long, second call after recovery succeeded.
        assertThat(llmClient.callCount.get()).isEqualTo(2);
        assertThat(strategy.invocationCount.get()).isEqualTo(1);
    }

    @Test
    void noStrategyWiredCausesExceptionToPropagate() {
        final FailFirstThenSucceedClient llmClient = new FailFirstThenSucceedClient();

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(null),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        // No recovery strategy → no retry; the framework default is NoOp.
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("context exceeded");
        assertThat(llmClient.callCount.get()).isEqualTo(1);
    }

    @Test
    void defaultStrategyDeclinesWhenOnlyLatestUserMessageExists() {
        final FailFirstThenSucceedClient llmClient = new FailFirstThenSucceedClient();

        // The session only has one user message ("hi"); the default strategy refuses to drop it.
        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(new DefaultPromptSizeRecoveryStrategy()),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("context exceeded");
        // Strategy was consulted but returned NONE → no retry attempted.
        assertThat(llmClient.callCount.get()).isEqualTo(1);
    }

    private OrcaAgentRuntime createContext(PromptSizeRecoveryStrategy strategy) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).promptSizeRecoveryStrategy(strategy).build();
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
     * Throws {@link LlmPromptTooLongException} on the first {@code sendMessage}, then returns a successful
     * (no-tool-use) response. After the recovery retry the call count therefore reaches 2.
     */
    private static final class FailFirstThenSucceedClient implements LlmClient {
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                throw new LlmPromptTooLongException("context exceeded", 10000, 8192);
            }
            return LlmResponse.of("recovered", List.of(), TokenUsage.of(10, 5, 15));
        }

        @Override
        public String getProviderName() {
            return "FailFirstThenSucceed";
        }

    }

    /**
     * Recovery strategy stub that always returns {@code RETRY} with the current message list (i.e. doesn't actually
     * drop anything — sufficient to verify the executor's retry plumbing is wired).
     */
    private static final class RecordingRecoveryStrategy implements PromptSizeRecoveryStrategy {
        final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public PromptSizeRecoveryDecision recover(List<Message> messages, LlmPromptTooLongException error) {
            invocationCount.incrementAndGet();
            return PromptSizeRecoveryDecision.retry(new ArrayList<>(messages), "stub retry");
        }
    }
}
