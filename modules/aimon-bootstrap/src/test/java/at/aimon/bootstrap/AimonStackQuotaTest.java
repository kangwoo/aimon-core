package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.ExecutorSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.QuotaSpec;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.quota.QuotaKey;
import at.aimon.core.agent.quota.QuotaLimit;
import at.aimon.core.agent.quota.QuotaScope;
import at.aimon.core.agent.quota.QuotaUsage;
import at.aimon.core.agent.quota.QuotaWindow;
import at.aimon.core.agent.quota.impl.InMemoryUsageLedger;
import at.aimon.core.agent.quota.impl.StaticQuotaPolicy;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.cost.CostEstimator;
import at.aimon.core.llm.cost.Money;

/**
 * The bootstrap half of usage quotas (usage-quota §7.1): a {@link QuotaSpec} reaches the executor, its absence leaves
 * the executor unmetered, and a cost limit the stack can see would never fire is reported.
 */
@DisplayName("AimonStack usage quota wiring")
class AimonStackQuotaTest {

    /** Answers every call with 10 tokens and counts the calls. */
    private static final class CountingLlm implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            calls.incrementAndGet();
            return LlmResponse.of("ok", List.of(), TokenUsage.of(5, 5, 10));
        }

        @Override
        public String getProviderName() {
            return "counting";
        }
    }

    private static AimonStackSpec.Builder specFor(Path workspace, LlmClient llm) {
        return AimonStackSpec
                .builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(llm)).agent(
                        AgentSpec
                                .of(AgentBundle
                                        .builder().agent(DefaultAgent.builder().name("ops")
                                                .systemPrompt("You are a test agent.").maxIterations(5).build())
                                        .build()));
    }

    private static OrcaAgentExecutionResult runTurn(AimonStack stack, SessionId sessionId) {
        return stack.agentExecutor().execute(stack.runtime(stack.primaryRuntimeId()).orElseThrow(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(sessionId).build());
    }

    @Test
    @DisplayName("without a QuotaSpec the spec reports quotas off and turns are not metered")
    void noQuotaSpecMeansOff(@TempDir Path workspace) {
        final CountingLlm llm = new CountingLlm();
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, llm).build())) {
            assertThat(stack.spec().getQuota().getLedger()).isEmpty();

            final OrcaAgentExecutionResult result = runTurn(stack, SessionId.generate());

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(result.getQuotaViolation()).isEmpty();
            assertThat(stack.degradations().has("quota-cost")).isFalse();
        }
    }

    @Test
    @DisplayName("a QuotaSpec with a ledger reaches the executor: a spent session quota stops the turn before the LLM")
    void quotaSpecReachesTheExecutor(@TempDir Path workspace) {
        final CountingLlm llm = new CountingLlm();
        final InMemoryUsageLedger ledger = new InMemoryUsageLedger();
        final SessionId sessionId = SessionId.generate();
        // Spent before the stack exists — by another node, or an earlier process, on a shared ledger.
        ledger.charge(List.of(QuotaKey.of(QuotaScope.session(sessionId), QuotaWindow.TOTAL, "")),
                QuotaUsage.of(100, Money.zeroUsd()));
        final QuotaSpec quota = QuotaSpec.builder().ledger(ledger).policy(StaticQuotaPolicy.builder()
                .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(100).build()).build()).build();

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, llm).quota(quota).build())) {
            final OrcaAgentExecutionResult stopped = runTurn(stack, sessionId);
            final OrcaAgentExecutionResult other = runTurn(stack, SessionId.generate());

            assertThat(stopped.getCompletionReason()).isEqualTo(CompletionReason.QUOTA_EXCEEDED);
            assertThat(other.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(llm.calls).hasValue(1);
        }
    }

    @Test
    @DisplayName("a cost limit under a NOOP cost estimator is reported as the quota-cost degradation")
    void costLimitWithoutPricingDegrades(@TempDir Path workspace) {
        final QuotaSpec quota = QuotaSpec.builder().ledger(new InMemoryUsageLedger())
                .policy(StaticQuotaPolicy.builder()
                        .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.daily().maxCost(Money.usd(20)).build()).build())
                .build();
        final ExecutorSpec unpriced = ExecutorSpec.builder().costEstimator(CostEstimator.NOOP).build();

        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace, new CountingLlm()).quota(quota).executor(unpriced).build())) {
            assertThat(stack.degradations().has("quota-cost")).isTrue();
            assertThat(stack.health().isServing()).isTrue();
        }
    }

    @Test
    @DisplayName("a token-only limit, or a cost limit with the default estimator, is not a degradation")
    void pricedOrTokenOnlyIsClean(@TempDir Path workspace) {
        final QuotaSpec costLimit = QuotaSpec.builder().ledger(new InMemoryUsageLedger())
                .policy(StaticQuotaPolicy.builder()
                        .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.daily().maxCost(Money.usd(20)).build()).build())
                .build();
        final QuotaSpec tokenLimit = QuotaSpec.builder().ledger(new InMemoryUsageLedger()).policy(StaticQuotaPolicy
                .builder().limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1_000).build()).build()).build();
        final ExecutorSpec unpriced = ExecutorSpec.builder().costEstimator(CostEstimator.NOOP).build();

        try (AimonStack priced = AimonStackBuilder
                .build(specFor(workspace.resolve("a"), new CountingLlm()).quota(costLimit).build());
                AimonStack tokensOnly = AimonStackBuilder.build(specFor(workspace.resolve("b"), new CountingLlm())
                        .quota(tokenLimit).executor(unpriced).build())) {
            assertThat(priced.degradations().has("quota-cost")).isFalse();
            assertThat(tokensOnly.degradations().has("quota-cost")).isFalse();
        }
    }

    @Test
    @DisplayName("QuotaSpec defaults: no ledger means the disabled factory; resolver and policy are filled in")
    void specDefaults() {
        assertThat(QuotaSpec.disabled().toGuardFactory().isEnabled()).isFalse();

        final QuotaSpec withLedger = QuotaSpec.builder().ledger(new InMemoryUsageLedger()).build();
        assertThat(withLedger.toGuardFactory().isEnabled()).isTrue();
        assertThat(withLedger.getPolicy().limitsFor(QuotaScope.tenant("acme"))).isEmpty();
        assertThat(withLedger.getResolver()).isNotNull();
        assertThat(withLedger.isFailClosed()).isFalse();
    }
}
