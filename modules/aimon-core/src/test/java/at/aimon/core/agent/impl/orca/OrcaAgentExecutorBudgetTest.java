package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionBudget;
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
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

@DisplayName("OrcaAgentExecutor ExecutionBudget enforcement")
class OrcaAgentExecutorBudgetTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("No budget set (legacy behavior)")
    class UnlimitedBudget {

        @Test
        @DisplayName("unset budget => execution completes normally with CompletionReason.COMPLETED")
        void unsetBudgetPreservesLegacyBehavior() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("final", List.of(), TokenUsage.of(10, 5, 15)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(result.getFinalAnswer()).isEqualTo("final");
        }
    }

    @Nested
    @DisplayName("maxIterations budget dimension")
    class MaxIterationsBudget {

        @Test
        @DisplayName("budget.maxIterations=1 stops before second iteration with MAX_ITERATIONS")
        void stopsAfterOneIteration() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            // First response requests a tool use so the loop would continue.
            llmClient.enqueue(LlmResponse.of("keep going", List.of(ToolUse.of("tu-1", "NoopTool", Map.of())),
                    TokenUsage.of(10, 10, 20)));
            // Safety net in case the loop runs another round (should not happen under budget).
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate())
                            .budget(ExecutionBudget.builder().maxIterations(1).build()).build());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
            assertThat(result.getErrorMessage()).contains("MAX_ITERATIONS");
            assertThat(result.getMetadata().getIterationCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("maxTokens budget dimension")
    class MaxTokensBudget {

        @Test
        @DisplayName("accumulated tokens >= maxTokens stops with TOKEN_BUDGET_EXCEEDED")
        void stopsWhenTokensExhausted() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            // One iteration consumes 100 tokens, exceeding the 50 budget.
            llmClient.enqueue(LlmResponse.of("keep going", List.of(ToolUse.of("tu-1", "NoopTool", Map.of())),
                    TokenUsage.of(60, 40, 100)));
            // Safety net — loop should terminate before this one is consumed.
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate())
                            .budget(ExecutionBudget.builder().maxTokens(50).build()).build());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TOKEN_BUDGET_EXCEEDED);
            assertThat(result.getErrorMessage()).contains("TOKEN_BUDGET_EXCEEDED");
            assertThat(result.getMetadata().getTokenUsage().getTotalTokens()).isEqualTo(100);
        }

        @Test
        @DisplayName("accumulated tokens below maxTokens allows execution to complete")
        void doesNotStopWhenUnderBudget() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(10, 10, 20)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate())
                            .budget(ExecutionBudget.builder().maxTokens(1_000).build()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        }
    }

    @Nested
    @DisplayName("maxWallClockDuration budget dimension")
    class WallClockBudget {

        @Test
        @DisplayName("elapsed wall-clock >= duration stops with WALL_CLOCK_EXCEEDED")
        void stopsWhenWallClockExhausted() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            // Simulate slow LLM: the response arrives after the budgeted duration has passed.
            llmClient.enqueue(LlmResponse.of("keep going", List.of(ToolUse.of("tu-1", "NoopTool", Map.of())),
                    TokenUsage.of(10, 10, 20)));
            llmClient.setDelayBeforeResponse(Duration.ofMillis(150));
            // Safety net — loop should stop before this is consumed.
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate())
                            .budget(ExecutionBudget.builder().maxWallClockDuration(Duration.ofMillis(50)).build())
                            .build());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
            assertThat(result.getErrorMessage()).contains("WALL_CLOCK_EXCEEDED");
        }
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

    /** Minimal tool used to keep the ReAct loop running so budget limits can trip. */
    private static final class NoopTool extends AbstractTool {
        static final String TOOL_NAME = "NoopTool";

        NoopTool() {
            super(TOOL_NAME, "no-op tool for budget tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("noop");
        }
    }
}
