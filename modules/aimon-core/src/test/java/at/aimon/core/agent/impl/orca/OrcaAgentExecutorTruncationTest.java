package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
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
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Verifies that a final turn cut off at the provider's max-output-token limit is surfaced as a flagged partial
 * answer (marked with {@link OrcaAgentExecutor#TRUNCATION_MARKER}) and terminates with
 * {@link CompletionReason#TRUNCATED}, instead of being silently treated as a normal {@link CompletionReason#COMPLETED}
 * finish.
 */
@DisplayName("OrcaAgentExecutor max_tokens truncation handling")
class OrcaAgentExecutorTruncationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("MAX_TOKENS stop with no tool uses surfaces flagged partial answer as TRUNCATED")
    void maxTokensWithoutToolUsesIsFlaggedAsTruncated() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("partial text", List.of(), TokenUsage.of(10, 5, 15), StopReason.MAX_TOKENS));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TRUNCATED);
        assertThat(result.getCompletionReason().isSuccessful()).isFalse();
        // The flagged partial text is still surfaced to the caller - the underlying result stays "successful" even
        // though the completion reason signals an incomplete answer.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).startsWith("partial text");
        assertThat(result.getFinalAnswer()).endsWith(OrcaAgentExecutor.TRUNCATION_MARKER);
        assertThat(result.getFinalAnswer()).contains("[System: response truncated at max_tokens]");
    }

    @Test
    @DisplayName("normal END_TURN final response completes cleanly without the truncation marker")
    void normalFinalResponseCompletesCleanly() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(10, 5, 15), StopReason.END_TURN));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(result.getFinalAnswer()).doesNotContain(OrcaAgentExecutor.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("legacy 3-arg factory (no stop reason captured) also completes cleanly")
    void legacyResponseWithoutStopReasonCompletesCleanly() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(10, 5, 15)));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(result.getFinalAnswer()).doesNotContain(OrcaAgentExecutor.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("MAX_TOKENS response WITH tool uses is not treated as a truncated terminal answer")
    void maxTokensWithToolUsesIsNotTreatedAsTruncated() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        // The truncation branch only applies to the no-tool-uses path; a MAX_TOKENS response that still requests a
        // tool goes through the normal tool-execution path instead.
        llmClient.enqueue(LlmResponse.of("acting", List.of(ToolUse.of("tu-1", "NoopTool", Map.of())),
                TokenUsage.of(10, 10, 20), StopReason.MAX_TOKENS));
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10), StopReason.END_TURN));

        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new NoopTool());

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(result.getFinalAnswer()).doesNotContain(OrcaAgentExecutor.TRUNCATION_MARKER);
    }

    private OrcaAgentRuntime createContext() {
        return createContext(new DefaultToolRegistry());
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
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
     * Minimal LLM client that returns pre-queued responses in sequence. Supports an optional delay applied once per
     * response, allowing wall-clock budget scenarios to exercise the elapsed-time check without relying on real LLM
     * latency.
     */
    private static final class SequencedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private Duration delayBeforeResponse = Duration.ZERO;

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        void setDelayBeforeResponse(Duration delay) {
            this.delayBeforeResponse = delay;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            if (!delayBeforeResponse.isZero()) {
                try {
                    Thread.sleep(delayBeforeResponse.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (responses.isEmpty()) {
                return LlmResponse.text("unexpected-extra-call");
            }
            return responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Sequenced";
        }

    }

    /** Minimal tool used to keep the ReAct loop running for a single extra iteration. */
    private static final class NoopTool extends AbstractTool {
        static final String TOOL_NAME = "NoopTool";

        NoopTool() {
            super(TOOL_NAME, "no-op tool for truncation tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("noop");
        }
    }
}
