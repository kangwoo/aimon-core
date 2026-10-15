package at.aimon.core.skill.execution.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.quota.QuotaGuard;
import at.aimon.core.agent.quota.QuotaKey;
import at.aimon.core.agent.quota.QuotaLimit;
import at.aimon.core.agent.quota.QuotaScope;
import at.aimon.core.agent.quota.QuotaUsage;
import at.aimon.core.agent.quota.QuotaWindow;
import at.aimon.core.agent.quota.impl.InMemoryUsageLedger;
import at.aimon.core.agent.quota.impl.StaticQuotaPolicy;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.tools.ToolContextKeys;

/**
 * An inline skill typed as {@code /my-skill} runs its own LLM loop inside the turn, so it answers to the turn's usage
 * quota: checked before every LLM call, charged after it. Before this, the loop neither checked nor charged, and a user
 * who had spent the quota could keep calling the LLM by typing a slash.
 */
@DisplayName("LlmSkillExecutor usage quota")
class LlmSkillExecutorQuotaTest {

    private static final QuotaScope SESSION = QuotaScope.session(SessionId.of("s-1"));
    private static final QuotaKey SESSION_TOTAL = QuotaKey.of(SESSION, QuotaWindow.TOTAL, "");
    private static final TokenUsage USAGE = TokenUsage.of(10, 10, 20);

    private final QueueLlmClient llm = new QueueLlmClient();
    private final LlmSkillExecutor executor = new LlmSkillExecutor(llm, new DefaultSkillContentRenderer(),
            new DefaultToolExecutionManager());
    private final InMemoryUsageLedger ledger = new InMemoryUsageLedger();

    @Test
    @DisplayName("a spent quota stops the skill before its first LLM call, naming QUOTA_EXCEEDED")
    void spentQuotaMakesNoCall() {
        ledger.charge(List.of(SESSION_TOTAL), QuotaUsage.of(100, Money.zeroUsd()));

        final SkillExecutionResult result = execute(guard(100));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResponse()).startsWith("Execution stopped: QUOTA_EXCEEDED");
        assertThat(llm.calls).isZero();
    }

    @Test
    @DisplayName("every call's total tokens are charged, and a quota reached mid-loop stops before the next call")
    void chargesEachCallAndStopsMidLoop() {
        llm.responses.add(
                LlmResponse.of("", List.of(ToolUse.of("t1", NoopTool.NAME, Map.of())), USAGE, StopReason.TOOL_USE));
        llm.responses.add(
                LlmResponse.of("", List.of(ToolUse.of("t2", NoopTool.NAME, Map.of())), USAGE, StopReason.TOOL_USE));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));

        // 40 tokens: the first two calls spend it, so the third is never made.
        final SkillExecutionResult result = execute(guard(40));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResponse()).startsWith("Execution stopped: QUOTA_EXCEEDED");
        assertThat(llm.calls).isEqualTo(2);
        assertThat(ledger.get(List.of(SESSION_TOTAL)).get(SESSION_TOTAL).getTokens()).isEqualTo(40);
    }

    @Test
    @DisplayName("under the limit the skill runs to its answer and every call is charged")
    void underTheLimitRunsToTheAnswer() {
        llm.responses.add(
                LlmResponse.of("", List.of(ToolUse.of("t1", NoopTool.NAME, Map.of())), USAGE, StopReason.TOOL_USE));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));

        final SkillExecutionResult result = execute(guard(1_000));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("done");
        assertThat(ledger.get(List.of(SESSION_TOTAL)).get(SESSION_TOTAL).getTokens()).isEqualTo(40);
    }

    private QuotaGuard guard(long maxTokens) {
        final StaticQuotaPolicy policy = StaticQuotaPolicy.builder()
                .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(maxTokens).build()).build();
        return QuotaGuard.builder().ledger(ledger).policy(policy).scopes(List.of(SESSION)).build();
    }

    private SkillExecutionResult execute(QuotaGuard guard) {
        final ToolRegistry registry = new DefaultToolRegistry();
        registry.register(new NoopTool());
        final Skill skill = Skill.builder().name("audit")
                .metadata(SkillMetadata.builder().name("audit").description("quota fixture").maxIterations(10).build())
                .content(SkillContent.of("Audit the module")).build();
        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(registry)
                .executionId(ExecutionId.generate("skill:test"))
                .toolContext(ToolContext.builder().put(ToolContextKeys.QUOTA_GUARD, guard).build()).build();
        return executor.execute(context, SkillExecutionRequest.builder().build());
    }

    /** Succeeds without doing anything. */
    private static final class NoopTool implements Tool {
        static final String NAME = "Noop";

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of(NAME, "Does nothing", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("ok");
        }
    }

    /** Returns queued responses in order and counts the calls. */
    private static final class QueueLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private int calls;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            calls++;
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Queue";
        }
    }
}
