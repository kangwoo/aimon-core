/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.quota.QuotaGuard;
import at.aimon.core.agent.quota.QuotaGuardFactory;
import at.aimon.core.agent.quota.QuotaKey;
import at.aimon.core.agent.quota.QuotaLimit;
import at.aimon.core.agent.quota.QuotaResolutionContext;
import at.aimon.core.agent.quota.QuotaScope;
import at.aimon.core.agent.quota.QuotaUsage;
import at.aimon.core.agent.quota.QuotaWindow;
import at.aimon.core.agent.quota.UsageLedger;
import at.aimon.core.agent.quota.impl.DefaultQuotaScopeResolver;
import at.aimon.core.agent.quota.impl.InMemoryUsageLedger;
import at.aimon.core.agent.quota.impl.StaticQuotaPolicy;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.StepFailedEvent;
import at.aimon.core.scheduling.event.TaskFailedEvent;
import at.aimon.core.tools.ToolContextKeys;

/**
 * A routine run resolves one usage-quota guard for its owner, publishes it to its steps so the forks they start are
 * metered, and refuses a step at the step boundary once the guard reports a violation (issue #267, UQ-2).
 *
 * <p>
 * The steps here stand in for a {@code Task} / {@code Workflow} / fork-mode skill step: they read the guard off their
 * context and charge it the way a fork's LLM calls would. The real forks are exercised end to end in
 * {@code RoutineUsageQuotaIntegrationTest}.
 */
class RoutineExecutorQuotaTest {

    private static final Principal OWNER = Principal.user("alice");
    private static final long LIMIT = 100;

    private DefaultAgentRuntimeRegistry agentRuntimeRegistry;
    private SimpleScheduledTaskEventPublisher eventPublisher;
    private RecordingListener events;
    private RoutineExecutor executor;
    private InMemoryUsageLedger ledger;
    private AgentRuntimeId boundRuntimeId;
    private ScriptedTool step;

    @BeforeEach
    void setUp() {
        agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        eventPublisher = new SimpleScheduledTaskEventPublisher();
        events = new RecordingListener();
        eventPublisher.addListener(events);
        executor = new RoutineExecutor(agentRuntimeRegistry, eventPublisher);
        ledger = new InMemoryUsageLedger();
        step = new ScriptedTool((input, context) -> ToolResult.success("ok"));
        final Agent agent = DefaultAgent.builder().name("orca-quota").systemPrompt("test").build();
        boundRuntimeId = AgentRuntimeId.from(agent);
        agentRuntimeRegistry.register(new FakeRuntime(boundRuntimeId, agent, List.of(step)));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void anOwnerAlreadyOverQuotaCannotRunAStep() {
        executor.withQuotaGuardFactory(factory(ledger));
        spend(LIMIT);

        final RoutineResult result = executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.getStepResults()).singleElement().satisfies(stepResult -> {
            assertThat(stepResult.isSuccess()).isFalse();
            assertThat(stepResult.getAttemptCount()).isZero();
            assertThat(stepResult.getErrorMessage()).hasValueSatisfying(message -> assertThat(message)
                    .startsWith("Execution stopped by quota:").contains("principal").doesNotContain("last attempt"));
        });
        assertThat(result.getErrorMessage())
                .hasValueSatisfying(message -> assertThat(message).startsWith("Execution stopped by quota:"));
        assertThat(step.calls).as("a refused step never reaches its tool").isEmpty();
        assertThat(events.stepFailed).hasSize(1);
        assertThat(events.taskFailed).hasSize(1);
    }

    @Test
    void withoutAFactoryTheRunIsUnmeteredAndItsContextHasNoGuard() {
        spend(LIMIT);

        final RoutineResult result = executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(step.calls).singleElement()
                .satisfies(context -> assertThat(context.get(ToolContextKeys.QUOTA_GUARD)).isEmpty());
    }

    @Test
    void theDisabledFactoryBehavesExactlyAsNoFactory() {
        executor.withQuotaGuardFactory(QuotaGuardFactory.disabled());
        spend(LIMIT);

        final RoutineResult result = executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(step.calls).singleElement()
                .satisfies(context -> assertThat(context.get(ToolContextKeys.QUOTA_GUARD)).isEmpty());
    }

    @Test
    void aNullFactoryIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> executor.withQuotaGuardFactory(null));
    }

    @Test
    void oneGuardPerRunReachesEveryStepAndRetryAndEachRunGetsItsOwn() {
        executor.withQuotaGuardFactory(factory(ledger));
        final List<Boolean> failFirst = new CopyOnWriteArrayList<>(List.of(true));
        step.behaviour = (input, context) -> failFirst.isEmpty() || !failFirst.remove(0)
                ? ToolResult.success("ok")
                : ToolResult.error("transient");
        final ScheduledTask task = task(RoutineStep.withRetry(ScriptedTool.NAME, "{}", 1, Duration.ZERO),
                RoutineStep.of(ScriptedTool.NAME, "{}"));

        assertThat(executor.execute(task).isSuccess()).isTrue();
        assertThat(executor.execute(task).isSuccess()).isTrue();

        // Run 1: step 0 twice (one retry), step 1 once. Run 2: one call per step. Compare the guards the tools were
        // handed, not the contexts: a terminable step's context is rebuilt per attempt.
        final List<QuotaGuard> guards = new ArrayList<>();
        step.calls.forEach(context -> guards.add(context.get(ToolContextKeys.QUOTA_GUARD).orElseThrow()));
        assertThat(guards).hasSize(5);
        assertThat(guards.get(0).isEnabled()).isTrue();
        assertThat(guards.subList(0, 3)).allSatisfy(guard -> assertThat(guard).isSameAs(guards.get(0)));
        assertThat(guards.get(3)).isNotSameAs(guards.get(0)).isSameAs(guards.get(4));
    }

    @Test
    void theResolverIsToldTheBoundRuntimeNoSessionTheOwnerAndTheRunsAttributes() {
        final List<QuotaResolutionContext> asked = new CopyOnWriteArrayList<>();
        executor.withExecutionAttributes((task, executionId) -> Map.of("tenant", "acme"));
        executor.withQuotaGuardFactory(QuotaGuardFactory.builder().ledger(ledger).policy(policy()).resolver(context -> {
            asked.add(context);
            return DefaultQuotaScopeResolver.create().resolve(context);
        }).build());

        executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}"), RoutineStep.of(ScriptedTool.NAME, "{}")));

        assertThat(asked).as("once per run, not per step").singleElement().satisfies(context -> {
            assertThat(context.getAgentRuntimeId()).isEqualTo(boundRuntimeId);
            assertThat(context.getSessionId()).isEmpty();
            assertThat(context.getPrincipal()).contains(OWNER);
            assertThat(context.getAttributes()).containsExactly(Map.entry("tenant", "acme"));
        });
    }

    @Test
    void aForkThatCrossesTheLimitAndFailsIsNotRetriedAndKeepsItsOwnError() {
        executor.withQuotaGuardFactory(factory(ledger));
        step.behaviour = (input, context) -> {
            charge(context, LIMIT);
            return ToolResult.error("fork stopped: QUOTA_EXCEEDED");
        };
        // A retry delay long enough that waiting it out would time the test out: the refusal must come before it.
        final ScheduledTask task = task(RoutineStep.withRetry(ScriptedTool.NAME, "{}", 2, Duration.ofMinutes(5)));

        final Instant before = Instant.now();
        final RoutineResult result = executor.execute(task);

        assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(30));
        assertThat(step.calls).hasSize(1);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStepResults()).singleElement().satisfies(stepResult -> {
            assertThat(stepResult.getAttemptCount()).isEqualTo(1);
            assertThat(stepResult.getErrorMessage())
                    .hasValueSatisfying(message -> assertThat(message).startsWith("Execution stopped by quota:")
                            .endsWith("(last attempt: fork stopped: QUOTA_EXCEEDED)"));
        });
    }

    @Test
    void theStepAfterAForkCrossedTheLimitIsRefused() {
        executor.withQuotaGuardFactory(factory(ledger));
        step.behaviour = (input, context) -> {
            charge(context, LIMIT);
            return ToolResult.success("forked work done");
        };

        final RoutineResult result = executor
                .execute(task(RoutineStep.of(ScriptedTool.NAME, "{}"), RoutineStep.of(ScriptedTool.NAME, "{}")));

        assertThat(step.calls).hasSize(1);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStepResults()).hasSize(2);
        assertThat(result.getStepResults().get(0).isSuccess()).isTrue();
        assertThat(result.getStepResults().get(1).getAttemptCount()).isZero();
        assertThat(result.getStepResults().get(1).getErrorMessage())
                .hasValueSatisfying(message -> assertThat(message).startsWith("Execution stopped by quota:"));
        assertThat(usage().getTokens()).isEqualTo(LIMIT);
    }

    @Test
    void aForkThatCrossesTheLimitOnTheLastStepLeavesTheRunSuccessful() {
        // Soft-limit overshoot (usage-quota §5): there is no later boundary to refuse at, as for a turn whose last
        // iteration crossed the limit.
        executor.withQuotaGuardFactory(factory(ledger));
        step.behaviour = (input, context) -> {
            charge(context, LIMIT * 2);
            return ToolResult.success("forked work done");
        };

        assertThat(executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}"))).isSuccess()).isTrue();
        assertThat(usage().getTokens()).isEqualTo(LIMIT * 2);
    }

    @Test
    void aResolverThatThrowsLeavesTheRunUnmeteredButStillFillsTheSlot() {
        executor.withQuotaGuardFactory(QuotaGuardFactory.builder().ledger(ledger).policy(policy()).resolver(context -> {
            throw new IllegalStateException("resolver bug");
        }).build());
        spend(LIMIT);

        final RoutineResult result = executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(step.calls).singleElement().satisfies(context -> assertThat(context.get(ToolContextKeys.QUOTA_GUARD))
                .hasValueSatisfying(guard -> assertThat(guard.isEnabled()).isFalse()));
    }

    @Test
    void aFailClosedLedgerThatFailsAtTheGateFailsTheStepWithoutRetriesAndIsNotWordedAsAQuotaStop() {
        executor.withQuotaGuardFactory(QuotaGuardFactory.builder().ledger(new FailingLedger()).policy(policy())
                .resolver(DefaultQuotaScopeResolver.create()).failClosed(true).build());

        final RoutineResult result = executor
                .execute(task(RoutineStep.withRetry(ScriptedTool.NAME, "{}", 2, Duration.ofMinutes(5))));

        assertThat(step.calls).isEmpty();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.getStepResults()).singleElement().satisfies(stepResult -> {
            assertThat(stepResult.getAttemptCount()).isZero();
            assertThat(stepResult.getErrorMessage()).hasValueSatisfying(message -> assertThat(message)
                    .startsWith("Usage ledger unavailable:").doesNotContain("stopped by quota"));
        });
    }

    @Test
    void aFailOpenLedgerThatFailsLetsTheStepRun() {
        executor.withQuotaGuardFactory(QuotaGuardFactory.builder().ledger(new FailingLedger()).policy(policy())
                .resolver(DefaultQuotaScopeResolver.create()).build());

        assertThat(executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}"))).isSuccess()).isTrue();
        assertThat(step.calls).hasSize(1);
    }

    @Test
    void aRunThatIsBothCancelledAndOverQuotaIsFiledAsCancelled() {
        executor.withQuotaGuardFactory(factory(ledger));
        final AtomicReference<ScheduledTaskId> taskId = new AtomicReference<>();
        step.behaviour = (input, context) -> {
            charge(context, LIMIT);
            executor.interrupt(taskId.get(), InterruptReason.TASK_CANCELLED);
            return ToolResult.error("stopped");
        };
        final ScheduledTask task = task(RoutineStep.withRetry(ScriptedTool.NAME, "{}", 2, Duration.ofMinutes(5)));
        taskId.set(task.getId());

        final RoutineResult result = executor.execute(task);

        assertThat(result.isCancelled()).isTrue();
        assertThat(step.calls).hasSize(1);
        assertThat(events.taskFailed).isEmpty();
    }

    @Test
    void aStepThatStartsNoLlmWorkIsRefusedToo() {
        // Design decision Q1: the executor cannot tell which tools fork, so every step is gated, as a turn of an
        // over-quota principal runs no tool at all. Reversing this should be a visible change to this test.
        executor.withQuotaGuardFactory(factory(ledger));
        spend(LIMIT);
        final ScriptedTool bash = new ScriptedTool("Bash", (input, context) -> ToolResult.success("ls output"));
        final Agent agent = DefaultAgent.builder().name("orca-quota-bash").systemPrompt("test").build();
        final AgentRuntimeId bashRuntime = AgentRuntimeId.from(agent);
        agentRuntimeRegistry.register(new FakeRuntime(bashRuntime, agent, List.of(bash)));

        final RoutineResult result = executor.execute(ScheduledTask.builder().id(ScheduledTaskId.of("quota-bash"))
                .name("quota-bash").cronExpression("*/5 * * * *").routine(List.of(RoutineStep.of("Bash", "{}")))
                .owner(OWNER).boundRuntimeId(bashRuntime).enabled(true).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(bash.calls).isEmpty();
    }

    @Test
    void anotherOwnerIsNotAffected() {
        executor.withQuotaGuardFactory(factory(ledger));
        spend(LIMIT);

        final RoutineResult result = executor
                .execute(ScheduledTask.builder().id(ScheduledTaskId.of("quota-bob")).name("quota-bob")
                        .cronExpression("*/5 * * * *").routine(List.of(RoutineStep.of(ScriptedTool.NAME, "{}")))
                        .owner(Principal.user("bob")).boundRuntimeId(boundRuntimeId).enabled(true).build());

        assertThat(result.isSuccess()).isTrue();
    }

    private static QuotaGuardFactory factory(UsageLedger usageLedger) {
        return QuotaGuardFactory.builder().ledger(usageLedger).policy(policy())
                .resolver(DefaultQuotaScopeResolver.create()).build();
    }

    private static StaticQuotaPolicy policy() {
        return StaticQuotaPolicy.builder().limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.total().maxTokens(LIMIT).build())
                .build();
    }

    private static QuotaKey ownerKey() {
        return QuotaKey.of(QuotaScope.principal(OWNER), QuotaWindow.TOTAL, "");
    }

    /** What the owner has spent elsewhere — in sessions, say — before the routine fires. */
    private void spend(long tokens) {
        ledger.charge(List.of(ownerKey()), QuotaUsage.of(tokens, Money.zeroUsd()));
    }

    private QuotaUsage usage() {
        return ledger.get(List.of(ownerKey())).getOrDefault(ownerKey(), QuotaUsage.zero());
    }

    /** What a fork's LLM call does with the guard it inherited. */
    private static void charge(ToolContext context, long tokens) {
        context.get(ToolContextKeys.QUOTA_GUARD).orElseThrow().charge("test-model",
                TokenUsage.of((int) tokens, 0, (int) tokens), null);
    }

    private ScheduledTask task(RoutineStep... steps) {
        return ScheduledTask.builder().id(ScheduledTaskId.of("quota-task")).name("quota-task")
                .cronExpression("*/5 * * * *").routine(List.of(steps)).owner(OWNER).boundRuntimeId(boundRuntimeId)
                .enabled(true).build();
    }

    /** A step tool whose behaviour each test sets, recording the context of every call. */
    static final class ScriptedTool extends AbstractTool {
        static final String NAME = "Fork";
        final List<ToolContext> calls = new CopyOnWriteArrayList<>();
        volatile BiFunction<ToolInput, ToolContext, ToolResult> behaviour;

        ScriptedTool(BiFunction<ToolInput, ToolContext, ToolResult> behaviour) {
            this(NAME, behaviour);
        }

        ScriptedTool(String name, BiFunction<ToolInput, ToolContext, ToolResult> behaviour) {
            super(name, "test tool", Map.of("type", "object", "properties", Map.of()));
            this.behaviour = behaviour;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            calls.add(context);
            return behaviour.apply(input, context);
        }
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

    private static final class RecordingListener implements ScheduledTaskEventListener {
        final List<StepFailedEvent> stepFailed = new CopyOnWriteArrayList<>();
        final List<TaskFailedEvent> taskFailed = new CopyOnWriteArrayList<>();

        @Override
        public void onStepFailed(StepFailedEvent event) {
            stepFailed.add(event);
        }

        @Override
        public void onTaskFailed(TaskFailedEvent event) {
            taskFailed.add(event);
        }
    }

    private static final class FakeRuntime implements AgentRuntime {
        private final AgentRuntimeId id;
        private final Agent agent;
        private final List<Tool> tools;

        FakeRuntime(AgentRuntimeId id, Agent agent, List<Tool> tools) {
            this.id = Objects.requireNonNull(id);
            this.agent = Objects.requireNonNull(agent);
            this.tools = List.copyOf(tools);
        }

        @Override
        public AgentRuntimeId getId() {
            return id;
        }

        @Override
        public Agent getAgent() {
            return agent;
        }

        @Override
        public List<Tool> getAvailableTools() {
            return tools;
        }
    }
}
