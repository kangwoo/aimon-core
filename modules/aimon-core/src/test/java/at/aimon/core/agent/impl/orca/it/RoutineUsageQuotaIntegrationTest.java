package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.quota.QuotaGuardFactory;
import at.aimon.core.agent.quota.QuotaKey;
import at.aimon.core.agent.quota.QuotaLimit;
import at.aimon.core.agent.quota.QuotaScope;
import at.aimon.core.agent.quota.QuotaUsage;
import at.aimon.core.agent.quota.QuotaWindow;
import at.aimon.core.agent.quota.impl.DefaultQuotaScopeResolver;
import at.aimon.core.agent.quota.impl.InMemoryUsageLedger;
import at.aimon.core.agent.quota.impl.StaticQuotaPolicy;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.scheduling.RoutineExecutor;
import at.aimon.core.scheduling.RoutineResult;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

/**
 * A scheduled routine's {@code Task} steps against a real runtime: the real {@code Task} tool, the real subagent
 * executor, and an in-memory ledger. Every scripted LLM call reports 15 total tokens ({@link ScriptedLlmClient}).
 *
 * <p>
 * The routine runs without a tracer, so its forks carry no trace id and their calls land on
 * {@link ScriptedLlmClient#UNROUTED}.
 *
 * <p>
 * What only this layer shows: that a fork started by a routine step lands on the task owner's principal key — the run
 * has no session and the fork never resolves a guard of its own — and that the owner's quota then refuses the next
 * step before its fork reaches the LLM. This is the bypass issue #267 closed (usage-quota §4.3, UQ-2).
 */
@DisplayName("Usage quota across a scheduled routine's forks")
class RoutineUsageQuotaIntegrationTest {

    private static final String WORKER = "it-worker";
    private static final long TOKENS_PER_CALL = 15;
    private static final Principal OWNER = Principal.user("alice");
    private static final String TASK_PARAMS = "{\"subagent_name\":\"" + WORKER
            + "\",\"prompt\":\"do the work\",\"description\":\"work\"}";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private InMemoryUsageLedger ledger;
    private DefaultAgentRuntimeRegistry registry;
    private RoutineExecutor routines;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        ledger = new InMemoryUsageLedger();
        final InMemorySubagentRegistry subagents = new InMemorySubagentRegistry();
        subagents.register(Subagent.builder().name(WORKER).description("Routine quota integration-test worker")
                .systemPrompt("You are the worker.").maxIterations(6).build());
        node = support.newNode("agent-a", llm, OrcaRuntimeItSupport.options().codeSubagents(subagents));
        registry = new DefaultAgentRuntimeRegistry();
        registry.register(node.runtime());
        routines = new RoutineExecutor(registry, new SimpleScheduledTaskEventPublisher());
    }

    @AfterEach
    void tearDown() {
        routines.shutdown();
        support.close();
    }

    private static QuotaGuardFactory principalLimit(InMemoryUsageLedger usageLedger, long maxTokens) {
        return QuotaGuardFactory.builder().ledger(usageLedger)
                .policy(StaticQuotaPolicy.builder()
                        .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.total().maxTokens(maxTokens).build()).build())
                .resolver(DefaultQuotaScopeResolver.create()).build();
    }

    private QuotaUsage ownerTotal() {
        final QuotaKey key = QuotaKey.of(QuotaScope.principal(OWNER), QuotaWindow.TOTAL, "");
        return ledger.get(List.of(key)).getOrDefault(key, QuotaUsage.zero());
    }

    private ScheduledTask twoTaskSteps() {
        return ScheduledTask.builder().id(ScheduledTaskId.of("routine-quota")).name("routine-quota")
                .cronExpression("*/5 * * * *")
                .routine(List.of(RoutineStep.of("Task", TASK_PARAMS), RoutineStep.of("Task", TASK_PARAMS))).owner(OWNER)
                .boundRuntimeId(node.runtime().getId()).enabled(true).build();
    }

    @Test
    @DisplayName("a routine fork is charged to the owner, and the owner's quota refuses the next fork step")
    void routineForkIsChargedToTheOwnerAndTheNextStepIsRefused() {
        routines.withQuotaGuardFactory(principalLimit(ledger, TOKENS_PER_CALL));
        llm.script(ScriptedLlmClient.UNROUTED, ScriptedLlmClient.text("worked"), ScriptedLlmClient.text("never sent"));

        final RoutineResult result = routines.execute(twoTaskSteps());

        // Step 0's fork made one call (15 = the limit) on the owner's key; step 1 is refused before its fork starts.
        assertThat(llm.callCount(ScriptedLlmClient.UNROUTED)).as("the refused step must not reach the LLM")
                .isEqualTo(1);
        assertThat(ownerTotal().getTokens()).isEqualTo(TOKENS_PER_CALL);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStepResults()).hasSize(2);
        assertThat(result.getStepResults().get(0).isSuccess()).isTrue();
        assertThat(result.getStepResults().get(1).getAttemptCount()).isZero();
        assertThat(result.getErrorMessage()).hasValueSatisfying(
                message -> assertThat(message).startsWith("Execution stopped by quota:").contains(OWNER.getId()));
    }

    @Test
    @DisplayName("an owner already over quota cannot start a routine's fork step at all")
    void ownerOverQuotaCannotRunAForkStep() {
        routines.withQuotaGuardFactory(principalLimit(ledger, TOKENS_PER_CALL));
        ledger.charge(List.of(QuotaKey.of(QuotaScope.principal(OWNER), QuotaWindow.TOTAL, "")),
                QuotaUsage.of(TOKENS_PER_CALL, Money.zeroUsd()));
        llm.script(ScriptedLlmClient.UNROUTED, ScriptedLlmClient.text("never sent"));

        final RoutineResult result = routines.execute(twoTaskSteps());

        assertThat(llm.callCount(ScriptedLlmClient.UNROUTED)).isZero();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStepResults()).singleElement()
                .satisfies(step -> assertThat(step.getAttemptCount()).isZero());
    }

    @Test
    @DisplayName("without a quota factory a routine's forks run unmetered, as before")
    void withoutAFactoryRoutineForksAreUnmetered() {
        llm.script(ScriptedLlmClient.UNROUTED, ScriptedLlmClient.text("one"), ScriptedLlmClient.text("two"));

        final RoutineResult result = routines.execute(twoTaskSteps());

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.callCount(ScriptedLlmClient.UNROUTED)).isEqualTo(2);
        assertThat(ownerTotal()).isEqualTo(QuotaUsage.zero());
    }
}
