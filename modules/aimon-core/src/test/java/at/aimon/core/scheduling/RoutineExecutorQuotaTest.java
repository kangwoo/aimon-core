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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
import at.aimon.core.agent.quota.QuotaViolation;
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
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A routine run resolves one usage-quota guard for its owner and publishes it to its steps, so the forks they start are
 * metered and stopped by it (issue #267, UQ-2). The executor refuses no step itself: a step that calls no LLM runs
 * whatever the owner has spent, and only a retry of an attempt the quota stopped is skipped.
 *
 * <p>
 * The fork-like steps here stand in for a {@code Task} / {@code Workflow} / fork-mode skill step: they check the guard
 * off their context before "calling the LLM" and charge it afterwards, as a fork does ({@link #forkLike}). The real
 * forks are exercised end to end in {@code RoutineUsageQuotaIntegrationTest}.
 */
class RoutineExecutorQuotaTest {

    private static final Principal OWNER = Principal.user("alice");
    private static final long LIMIT = 100;
    /**
     * How a fork's and an inline skill's quota stop opens ({@code DefaultSubagentExecutor}, {@code LlmSkillExecutor}).
     */
    private static final String QUOTA_STOP = "Execution stopped: QUOTA_EXCEEDED — ";

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
    void anOwnerAlreadyOverQuotaHasItsForkStoppedByTheGuardTheStepInherits() {
        executor.withQuotaGuardFactory(factory(ledger));
        spend(LIMIT);
        step.behaviour = (input, context) -> forkLike(context, 10);

        final RoutineResult result = executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.getStepResults()).singleElement().satisfies(stepResult -> {
            assertThat(stepResult.isSuccess()).isFalse();
            // The fork's own check stopped it; the default retries are not spent on the same wall.
            assertThat(stepResult.getAttemptCount()).as("the step itself is not refused").isEqualTo(1);
            assertThat(stepResult.getErrorMessage()).hasValueSatisfying(
                    message -> assertThat(message).startsWith("Execution stopped by quota:").contains("principal")
                            .endsWith("(last attempt: Execution stopped: QUOTA_EXCEEDED — " + overQuota() + ")"));
        });
        assertThat(usage().getTokens()).as("the fork stopped before spending anything").isEqualTo(LIMIT);
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
            return ToolResult.error(QUOTA_STOP + overQuota());
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
                            .endsWith("(last attempt: " + QUOTA_STOP + overQuota() + ")"));
        });
    }

    @Test
    void aFailedAttemptThatIsNotAQuotaStopKeepsItsRetriesWhileTheOwnerIsOverQuota() {
        executor.withQuotaGuardFactory(factory(ledger));
        spend(LIMIT);
        final List<Boolean> failFirst = new CopyOnWriteArrayList<>(List.of(true));
        step.behaviour = (input, context) -> failFirst.isEmpty() || !failFirst.remove(0)
                ? ToolResult.success("ok")
                : ToolResult.error("connection reset");

        final RoutineResult result = executor
                .execute(task(RoutineStep.withRetry(ScriptedTool.NAME, "{}", 1, Duration.ZERO)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(step.calls).hasSize(2);
    }

    @Test
    void theForkInTheStepAfterTheLimitWasCrossedIsStoppedByTheSameGuard() {
        executor.withQuotaGuardFactory(factory(ledger));
        step.behaviour = (input, context) -> forkLike(context, LIMIT);

        final RoutineResult result = executor
                .execute(task(RoutineStep.of(ScriptedTool.NAME, "{}"), RoutineStep.of(ScriptedTool.NAME, "{}")));

        assertThat(step.calls).hasSize(2);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStepResults()).hasSize(2);
        assertThat(result.getStepResults().get(0).isSuccess()).isTrue();
        assertThat(result.getStepResults().get(1).getAttemptCount()).isEqualTo(1);
        assertThat(result.getStepResults().get(1).getErrorMessage()).hasValueSatisfying(
                message -> assertThat(message).contains("(last attempt: Execution stopped: QUOTA_EXCEEDED"));
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
    void aFailClosedLedgerThatFailsDoesNotStopAStepThatSpendsNoUsage() {
        executor.withQuotaGuardFactory(QuotaGuardFactory.builder().ledger(new FailingLedger()).policy(policy())
                .resolver(DefaultQuotaScopeResolver.create()).failClosed(true).build());

        final RoutineResult result = executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(step.calls).hasSize(1);
    }

    @Test
    void aFailClosedLedgerThatFailsAtTheRetryBoundaryLetsTheRetryGoAhead() {
        // The guard cannot answer, so the retry is not refused for quota: it meets the ledger itself.
        executor.withQuotaGuardFactory(QuotaGuardFactory.builder().ledger(new FailingLedger()).policy(policy())
                .resolver(DefaultQuotaScopeResolver.create()).failClosed(true).build());
        step.behaviour = (input, context) -> ToolResult.error(QUOTA_STOP + "ledger down");

        final RoutineResult result = executor
                .execute(task(RoutineStep.withRetry(ScriptedTool.NAME, "{}", 1, Duration.ZERO)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(step.calls).hasSize(2);
        assertThat(result.getStepResults()).singleElement()
                .satisfies(stepResult -> assertThat(stepResult.getErrorMessage())
                        .hasValueSatisfying(message -> assertThat(message).isEqualTo(QUOTA_STOP + "ledger down")));
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
    void aStepThatStartsNoLlmWorkRunsWhileItsOwnerIsOverQuota() {
        // Design Q1, reversed in review: a usage quota limits LLM spend, and every LLM call a step can reach checks the
        // guard itself. A step that spends none is not refused.
        executor.withQuotaGuardFactory(factory(ledger));
        spend(LIMIT);
        final ScriptedTool bash = new ScriptedTool("Bash", (input, context) -> ToolResult.success("ls output"));
        final Agent agent = DefaultAgent.builder().name("orca-quota-bash").systemPrompt("test").build();
        final AgentRuntimeId bashRuntime = AgentRuntimeId.from(agent);
        agentRuntimeRegistry.register(new FakeRuntime(bashRuntime, agent, List.of(bash)));

        final RoutineResult result = executor.execute(ScheduledTask.builder().id(ScheduledTaskId.of("quota-bash"))
                .name("quota-bash").cronExpression("*/5 * * * *").routine(List.of(RoutineStep.of("Bash", "{}")))
                .owner(OWNER).boundRuntimeId(bashRuntime).enabled(true).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(bash.calls).singleElement()
                .satisfies(context -> assertThat(context.get(ToolContextKeys.QUOTA_GUARD)).isPresent());
    }

    @Test
    void aStepErrorThatMerelyNamesQuotaExceededIsNotTakenForAUsageQuotaStop() {
        // A cloud CLI's resource quota (Compute Engine says QUOTA_EXCEEDED) has nothing to do with LLM usage: the step
        // keeps its retries even while its owner is over the usage quota.
        executor.withQuotaGuardFactory(factory(ledger));
        spend(LIMIT);
        final List<Boolean> failFirst = new CopyOnWriteArrayList<>(List.of(true));
        step.behaviour = (input, context) -> failFirst.isEmpty() || !failFirst.remove(0)
                ? ToolResult.success("instance created")
                : ToolResult.error("ERROR: (gcloud.compute.instances.create) QUOTA_EXCEEDED: Quota 'CPUS' exceeded");

        final RoutineResult result = executor
                .execute(task(RoutineStep.withRetry(ScriptedTool.NAME, "{}", 1, Duration.ZERO)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(step.calls).hasSize(2);
    }

    @Test
    void aRunFiledAsASuccessWhileTheOwnerIsOverQuotaLogsAWarn() {
        executor.withQuotaGuardFactory(factory(ledger));
        spend(LIMIT);
        step.behaviour = (input, context) -> taskLike(context);

        final List<ILoggingEvent> logged = captureLogs(() -> {
            final RoutineResult result = executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}")));
            assertThat(result.isSuccess()).as("Task reports a quota-stopped fork as a success").isTrue();
        });

        assertThat(logged).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("quota-task")
                        .contains("usage quota is reached").contains(overQuota()));
    }

    @Test
    void aRunUnderQuotaLogsNoQuotaWarn() {
        executor.withQuotaGuardFactory(factory(ledger));
        step.behaviour = (input, context) -> taskLike(context);

        final List<ILoggingEvent> logged = captureLogs(
                () -> assertThat(executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}"))).isSuccess()).isTrue());

        assertThat(logged).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void withoutAFactoryAnOverQuotaOwnerLogsNoQuotaWarn() {
        spend(LIMIT);
        step.behaviour = (input, context) -> taskLike(context);

        final List<ILoggingEvent> logged = captureLogs(
                () -> assertThat(executor.execute(task(RoutineStep.of(ScriptedTool.NAME, "{}"))).isSuccess()).isTrue());

        assertThat(logged).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void anotherOwnerIsNotAffected() {
        executor.withQuotaGuardFactory(factory(ledger));
        spend(LIMIT);
        step.behaviour = (input, context) -> forkLike(context, 10);

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

    /** What the guard says about the owner once the limit is reached. */
    private static String overQuota() {
        return "quota principal:user/alice:total reached: " + LIMIT + " tokens / " + LIMIT + " tokens";
    }

    private QuotaUsage usage() {
        return ledger.get(List.of(ownerKey())).getOrDefault(ownerKey(), QuotaUsage.zero());
    }

    /**
     * What a fork does with the guard it inherited: checks it before its first LLM call, stopping with the fork's
     * {@code QUOTA_EXCEEDED} wording, and charges it afterwards.
     */
    private static ToolResult forkLike(ToolContext context, long tokens) {
        final Optional<QuotaViolation> violation = context.get(ToolContextKeys.QUOTA_GUARD)
                .orElseGet(QuotaGuard::disabled).check();
        if (violation.isPresent()) {
            return ToolResult.error(QUOTA_STOP + violation.get().describe());
        }
        charge(context, tokens);
        return ToolResult.success("forked work done");
    }

    /**
     * What a {@code Task} step does: its fork checks the guard and stops before calling the LLM, but {@code Task}
     * reports that stop as a successful result carrying a {@code Completion reason:} line.
     */
    private static ToolResult taskLike(ToolContext context) {
        final Optional<QuotaViolation> violation = context.get(ToolContextKeys.QUOTA_GUARD)
                .orElseGet(QuotaGuard::disabled).check();
        if (violation.isPresent()) {
            return ToolResult.success("Status: FAILED\nCompletion reason: QUOTA_EXCEEDED");
        }
        // Unmetered without a factory: nothing to charge.
        context.get(ToolContextKeys.QUOTA_GUARD).ifPresent(guard -> charge(context, 10));
        return ToolResult.success("Status: COMPLETED");
    }

    /** Runs {@code action} with this executor's logger captured, and returns what it logged. */
    private static List<ILoggingEvent> captureLogs(Runnable action) {
        final Logger logger = (Logger) LoggerFactory.getLogger(RoutineExecutor.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return List.copyOf(appender.list);
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
