package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.quota.QuotaGuardFactory;
import at.aimon.core.agent.quota.QuotaKey;
import at.aimon.core.agent.quota.QuotaLimit;
import at.aimon.core.agent.quota.QuotaScope;
import at.aimon.core.agent.quota.QuotaUsage;
import at.aimon.core.agent.quota.QuotaWindow;
import at.aimon.core.agent.quota.UsageLedger;
import at.aimon.core.agent.quota.impl.DefaultQuotaScopeResolver;
import at.aimon.core.agent.quota.impl.InMemoryUsageLedger;
import at.aimon.core.agent.quota.impl.StaticQuotaPolicy;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.execution.SubagentResultFormatter;

/**
 * Usage quotas end to end: a real executor, the real {@code Task} tool, the real subagent executor, and an in-memory
 * ledger. Every scripted LLM call reports 15 total tokens ({@link ScriptedLlmClient}), so the ledger counts in steps of
 * 15.
 *
 * <p>
 * What only this layer can show: that a fork's calls land on the <em>parent turn's</em> session key — the fork has no
 * session of its own — and that the fork is stopped by the same quota, which is what closes the bypass the design is
 * about (usage-quota §1, §4.3).
 */
@DisplayName("Usage quota across turns, sessions and forks")
class UsageQuotaIntegrationTest {

    private static final String WORKER = "it-worker";
    private static final String INNER = "it-inner";
    private static final long TOKENS_PER_CALL = 15;

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private InMemoryUsageLedger ledger;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        ledger = new InMemoryUsageLedger();
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private OrcaRuntimeItSupport.Node node(QuotaGuardFactory factory) {
        final InMemorySubagentRegistry subagents = new InMemorySubagentRegistry();
        subagents.register(Subagent.builder().name(WORKER).description("Quota integration-test worker")
                .systemPrompt("You are the worker.").maxIterations(6).build());
        subagents.register(Subagent.builder().name(INNER).description("Nested quota integration-test worker")
                .systemPrompt("You are the inner worker.").maxIterations(6).build());
        return support.newNode("agent-a", llm,
                OrcaRuntimeItSupport.options().codeSubagents(subagents).quotaGuardFactory(factory));
    }

    private QuotaGuardFactory factory(UsageLedger usageLedger, QuotaScope.Kind kind, QuotaLimit limit) {
        return QuotaGuardFactory.builder().ledger(usageLedger)
                .policy(StaticQuotaPolicy.builder().limit(kind, limit).build())
                .resolver(DefaultQuotaScopeResolver.create()).build();
    }

    private QuotaUsage sessionTotal(SessionId sessionId) {
        final QuotaKey key = QuotaKey.of(QuotaScope.session(sessionId), QuotaWindow.TOTAL, "");
        return ledger.get(List.of(key)).get(key);
    }

    private static Map<String, Object> task() {
        return task(WORKER);
    }

    private static Map<String, Object> task(String subagentName) {
        return Map.of("subagent_name", subagentName, "prompt", "do the work", "description", "work");
    }

    @Test
    @DisplayName("a session TOTAL token quota accumulates across turns; the turn after it is reached makes no LLM call")
    void sessionQuotaAccumulatesAcrossTurns() {
        final OrcaRuntimeItSupport.Node node = node(
                factory(ledger, QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(40).build()));
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("one"), ScriptedLlmClient.text("two"),
                ScriptedLlmClient.text("three"), ScriptedLlmClient.text("never sent"));

        // 15, 30, 45 — each turn is under the limit when it starts, so each makes its call; one ExecutionBudget per
        // turn would have reset at every one of these.
        assertThat(node.run(sessionId, "turn 1").getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(node.run(sessionId, "turn 2").getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(node.run(sessionId, "turn 3").getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(sessionTotal(sessionId).getTokens()).isEqualTo(3 * TOKENS_PER_CALL);

        final OrcaAgentExecutionResult fourth = node.run(sessionId, "turn 4");

        assertThat(fourth.getCompletionReason()).isEqualTo(CompletionReason.QUOTA_EXCEEDED);
        assertThat(fourth.isSuccess()).isFalse();
        assertThat(fourth.getIterationCount()).isZero();
        assertThat(llm.callCount(sessionId.value())).as("the stopped turn must not reach the LLM").isEqualTo(3);
        assertThat(fourth.getQuotaViolation()).hasValueSatisfying(violation -> {
            assertThat(violation.getKey().getScope()).isEqualTo(QuotaScope.session(sessionId));
            assertThat(violation.getKey().getWindow()).isEqualTo(QuotaWindow.TOTAL);
            assertThat(violation.getUsed().getTokens()).isEqualTo(45);
        });
        assertThat(fourth.getErrorMessage()).contains("quota", sessionId.value());
    }

    @Test
    @DisplayName("a fork's LLM calls are charged to the parent turn's session key")
    void forkUsageIsChargedToTheParentSession() {
        final OrcaRuntimeItSupport.Node node = node(
                factory(ledger, QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1_000).build()));
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", task()), ScriptedLlmClient.text("done"));
        llm.script(ScriptedLlmClient.forkRoute(sessionId.value(), WORKER),
                ScriptedLlmClient.callTool("Glob", Map.of("pattern", "*.none")), ScriptedLlmClient.text("worked"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "delegate");

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(llm.callCount(ScriptedLlmClient.forkRoute(sessionId.value(), WORKER))).isEqualTo(2);
        // Two parent calls and two fork calls. Without the guard on the launch context the fork's two would be lost —
        // the fork has no session id to be charged under.
        assertThat(sessionTotal(sessionId).getTokens()).isEqualTo(4 * TOKENS_PER_CALL);
    }

    @Test
    @DisplayName("a fork inside a fork is charged to the same session key")
    void nestedForkUsageIsChargedToTheParentSession() {
        final OrcaRuntimeItSupport.Node node = node(
                factory(ledger, QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1_000).build()));
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", task()), ScriptedLlmClient.text("done"));
        llm.script(ScriptedLlmClient.forkRoute(sessionId.value(), WORKER),
                ScriptedLlmClient.callTool("Task", task(INNER)), ScriptedLlmClient.text("outer done"));
        llm.script(ScriptedLlmClient.forkRoute(sessionId.value(), INNER), ScriptedLlmClient.text("inner done"));

        assertThat(node.run(sessionId, "delegate twice").getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);

        // 2 parent + 2 outer + 1 inner. The outer fork republishes the guard it inherited, so the inner one is metered
        // too — a guard that reached only one level would leave the second level as the bypass.
        assertThat(llm.callCount(ScriptedLlmClient.forkRoute(sessionId.value(), INNER))).isEqualTo(1);
        assertThat(sessionTotal(sessionId).getTokens()).isEqualTo(5 * TOKENS_PER_CALL);
    }

    @Test
    @DisplayName("a fork is stopped by the parent session's quota, and so is the parent after it")
    void forkIsStoppedByTheParentSessionQuota() {
        final OrcaRuntimeItSupport.Node node = node(
                factory(ledger, QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(30).build()));
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", task()), ScriptedLlmClient.text("never sent"));
        llm.script(forkRoute, ScriptedLlmClient.callTool("Glob", Map.of("pattern", "*.none")),
                ScriptedLlmClient.text("never sent"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "delegate");

        // Parent call (15) -> fork call (30). The fork's second iteration finds 30 >= 30 and stops before its LLM call;
        // the parent's second iteration finds the same and stops too.
        assertThat(llm.callCount(forkRoute)).as("the fork stops at its own iteration boundary").isEqualTo(1);
        assertThat(llm.callCount(sessionId.value())).isEqualTo(1);
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.QUOTA_EXCEEDED);
        assertThat(sessionTotal(sessionId).getTokens()).isEqualTo(2 * TOKENS_PER_CALL);
        // The Task result the parent recorded: the fork itself ended on the quota, and says so to the parent model.
        final String taskResult = result.getConversationHistory().stream()
                .flatMap(message -> message.getToolUseResults().stream()).map(ToolUseResult::getContent)
                .filter(content -> content.contains("Subagent Task Result")).findFirst().orElseThrow();
        assertThat(taskResult).contains("Completion reason: QUOTA_EXCEEDED")
                .contains(SubagentResultFormatter.QUOTA_EXCEEDED_HINT);
    }

    @Test
    @DisplayName("a PRINCIPAL quota accumulates across two sessions of the same caller")
    void principalQuotaSpansSessions() {
        final OrcaRuntimeItSupport.Node node = node(
                factory(ledger, QuotaScope.Kind.PRINCIPAL, QuotaLimit.total().maxTokens(30).build()));
        final Principal alice = Principal.user("alice");
        final SessionId first = OrcaRuntimeItSupport.newSession();
        final SessionId second = OrcaRuntimeItSupport.newSession();
        llm.script(first.value(), ScriptedLlmClient.text("one"), ScriptedLlmClient.text("two"));
        llm.script(second.value(), ScriptedLlmClient.text("never sent"));

        assertThat(node.execute(request(first, alice)).getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(node.execute(request(first, alice)).getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);

        // A new session is no way around a principal quota — the bypass a session-only limit would leave open.
        final OrcaAgentExecutionResult other = node.execute(request(second, alice));

        assertThat(other.getCompletionReason()).isEqualTo(CompletionReason.QUOTA_EXCEEDED);
        assertThat(other.getQuotaViolation()).hasValueSatisfying(
                violation -> assertThat(violation.getKey().getScope()).isEqualTo(QuotaScope.principal(alice)));
        assertThat(llm.callCount(second.value())).isZero();

        // A different caller is not affected.
        final SessionId third = OrcaRuntimeItSupport.newSession();
        llm.script(third.value(), ScriptedLlmClient.text("bob's"));
        assertThat(node.execute(request(third, Principal.user("bob"))).getCompletionReason())
                .isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    @DisplayName("a failing ledger fails open by default: the turn completes unmetered")
    void failingLedgerFailsOpen() {
        final OrcaRuntimeItSupport.Node node = node(
                factory(new FailingLedger(), QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1).build()));
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("answered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "hi");

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.getFinalAnswer()).isEqualTo("answered");
    }

    @Test
    @DisplayName("a failing ledger under failClosed(true) ends the turn with ERROR, not QUOTA_EXCEEDED, before the LLM")
    void failingLedgerFailsClosed() {
        final OrcaRuntimeItSupport.Node node = node(QuotaGuardFactory.builder().ledger(new FailingLedger())
                .policy(StaticQuotaPolicy.builder()
                        .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1_000).build()).build())
                .resolver(DefaultQuotaScopeResolver.create()).failClosed(true).build());
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("never sent"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "hi");

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(result.getQuotaViolation()).isEmpty();
        assertThat(llm.callCount(sessionId.value())).isZero();
    }

    @Test
    @DisplayName("a resolver that throws leaves the turn unmetered rather than failing it")
    void throwingResolverFailsSafe() {
        final OrcaRuntimeItSupport.Node node = node(QuotaGuardFactory.builder().ledger(ledger)
                .policy(StaticQuotaPolicy.builder()
                        .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1).build()).build())
                .resolver(context -> {
                    throw new IllegalStateException("resolver bug");
                }).build());
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("answered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "hi");

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(sessionTotal(sessionId)).isEqualTo(QuotaUsage.zero());
    }

    @Test
    @DisplayName("an executor with no quota factory never stops on quota")
    void noFactoryMeansNoMetering() {
        final OrcaRuntimeItSupport.Node node = support.newNode("agent-a", llm);
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("one"), ScriptedLlmClient.text("two"));

        assertThat(node.run(sessionId, "turn 1").getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(node.run(sessionId, "turn 2").getQuotaViolation()).isEmpty();
    }

    private static OrcaAgentExecutionRequest request(SessionId sessionId, Principal principal) {
        return OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(sessionId).principal(principal).build();
    }

    /** A ledger whose backend is down. */
    private static final class FailingLedger implements UsageLedger {
        @Override
        public void charge(Collection<QuotaKey> keys, QuotaUsage usage) {
            throw new IllegalStateException("ledger down");
        }

        @Override
        public Map<QuotaKey, QuotaUsage> get(Collection<QuotaKey> keys) {
            throw new IllegalStateException("ledger down");
        }
    }
}
