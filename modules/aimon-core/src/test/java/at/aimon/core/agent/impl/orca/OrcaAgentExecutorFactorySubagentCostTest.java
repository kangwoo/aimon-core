package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

/**
 * Verifies that {@link OrcaAgentExecutorFactory#withCostEstimator(at.aimon.core.llm.cost.CostEstimator)} is threaded
 * into the {@code DefaultSubagentExecutor} the factory builds inside its subagent execution manager — the seam an
 * workflow run drives its subagents through. Without this wiring the aggregate USD cost of a run
 * would always be zero regardless of the estimator installed on the main executor.
 */
@DisplayName("OrcaAgentExecutorFactory threads the cost estimator into the subagent execution path")
class OrcaAgentExecutorFactorySubagentCostTest {

    @Test
    @DisplayName("with a cost estimator wired, the factory's subagent executor prices calls (non-zero cost)")
    void subagentExecutorIsPriced() {
        final SubagentExecutionResult result = runSubagent(
                new OrcaAgentExecutorFactory().withCostEstimator((model, usage) -> Money.usd(0.02)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCost()).isEqualTo(Money.usd(0.02));
    }

    @Test
    @DisplayName("without a cost estimator, the factory's subagent executor keeps the NOOP zero cost")
    void subagentExecutorZeroByDefault() {
        final SubagentExecutionResult result = runSubagent(new OrcaAgentExecutorFactory());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCost()).isEqualTo(Money.zeroUsd());
    }

    private SubagentExecutionResult runSubagent(OrcaAgentExecutorFactory factory) {
        final SubagentExecutionManager manager = factory.createDefaultSubagentExecutionManager(
                new SingleAnswerLlmClient(), new DefaultToolExecutionManager(), new DefaultHookExecutionManager());

        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1"))
                .subagent(Subagent.of("explorer", SubagentMetadata.builder().description("d").maxIterations(3).build(),
                        SubagentContent.of("you are explorer")))
                .defaultModel(LlmModel.builder().name("gpt-4o").build()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("t").goal("do it").build();

        return ((DefaultSubagentExecutionManager) manager).getSubagentExecutor().execute(context, request);
    }

    /** Minimal LLM client that always returns a final answer with fixed token usage (one ReAct iteration). */
    private static final class SingleAnswerLlmClient implements LlmClient {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return LlmResponse.of("done", List.of(), TokenUsage.of(10, 10, 20));
        }

        @Override
        public String getProviderName() {
            return "SingleAnswer";
        }

    }
}
