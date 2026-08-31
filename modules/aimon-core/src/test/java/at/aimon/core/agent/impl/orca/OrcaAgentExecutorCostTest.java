package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
import at.aimon.core.llm.cost.CostEstimator;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

@DisplayName("OrcaAgentExecutor cost tracking")
class OrcaAgentExecutorCostTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Default (no cost estimator wired)")
    class DefaultNoEstimator {

        @Test
        @DisplayName("cost summary is empty and zero when the default NOOP estimator is used")
        void costSummaryEmptyByDefault() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("final", List.of(), TokenUsage.of(10, 5, 15)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getCostSummary().isEmpty()).isTrue();
            assertThat(result.getCostSummary().getTotalCost().isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("With a cost estimator wired")
    class WithEstimator {

        @Test
        @DisplayName("cost accumulates onto the result's cost summary across iterations")
        void costAccumulatesOnResult() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            // Two LLM calls: one tool-use round, then a final answer.
            llmClient.enqueue(LlmResponse.of("keep going", List.of(ToolUse.of("tu-1", "NoopTool", Map.of())),
                    TokenUsage.of(10, 10, 20)));
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            // Fixed $0.02 per LLM call regardless of model — isolates the accumulation contract from any price table.
            executor.costEstimator = fixedCostPerCall(Money.usd(0.02));

            final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getCostSummary().isEmpty()).isFalse();
            // Two LLM calls * $0.02 each.
            assertThat(result.getCostSummary().getTotalCost().getAmount()).isEqualByComparingTo("0.04");
            assertThat(result.getCostSummary().getTotalTokenUsage().getTotalTokens()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("Cost budget dimension")
    class CostBudget {

        @Test
        @DisplayName("accumulated cost >= maxCostUsd stops with COST_BUDGET_EXCEEDED")
        void stopsWhenCostExhausted() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            // First call costs $0.10, exceeding the $0.05 budget.
            llmClient.enqueue(LlmResponse.of("keep going", List.of(ToolUse.of("tu-1", "NoopTool", Map.of())),
                    TokenUsage.of(10, 10, 20)));
            // Safety net — loop should terminate before this is consumed.
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            executor.costEstimator = fixedCostPerCall(Money.usd(0.10));

            final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate())
                            .budget(ExecutionBudget.builder().maxCostUsd(Money.usd(0.05)).build()).build());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COST_BUDGET_EXCEEDED);
            assertThat(result.getErrorMessage()).contains("COST_BUDGET_EXCEEDED");
            assertThat(result.getCostSummary().getTotalCost().getAmount()).isEqualByComparingTo("0.10");
        }

        @Test
        @DisplayName("cost below maxCostUsd allows execution to complete")
        void completesWhenUnderCostBudget() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(10, 10, 20)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            executor.costEstimator = fixedCostPerCall(Money.usd(0.01));

            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate())
                            .budget(ExecutionBudget.builder().maxCostUsd(Money.usd(1.00)).build()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(result.getCostSummary().getTotalCost().getAmount()).isEqualByComparingTo("0.01");
        }
    }

    private static CostEstimator fixedCostPerCall(Money perCall) {
        return (modelName, usage) -> perCall;
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

    /** Minimal LLM client that returns pre-queued responses in sequence. */
    private static final class SequencedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
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

    /** Minimal tool used to keep the ReAct loop running so a second LLM call happens. */
    private static final class NoopTool extends AbstractTool {
        static final String TOOL_NAME = "NoopTool";

        NoopTool() {
            super(TOOL_NAME, "no-op tool for cost tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("noop");
        }
    }
}
