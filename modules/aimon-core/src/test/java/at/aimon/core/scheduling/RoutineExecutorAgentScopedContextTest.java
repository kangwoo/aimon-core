/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentDefinitionVersion;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.tools.ToolContextKeys;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Integration tests verifying the agent-scoped {@link AgentRuntime} contract under {@link RoutineExecutor}.
 *
 * <p>
 * The scenarios exercised here are deterministic: virtual time is supplied via a controllable {@link Clock}, and cron
 * re-fires are simulated by directly invoking the routine executor (the production scheduler is not required for the
 * resolution behavior under test).
 */
class RoutineExecutorAgentScopedContextTest {

    private DefaultAgentRuntimeRegistry agentRuntimeRegistry;
    private SimpleScheduledTaskEventPublisher eventPublisher;
    private MutableClock clock;
    private RoutineExecutor executor;

    @BeforeEach
    void setUp() {
        agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        eventPublisher = new SimpleScheduledTaskEventPublisher();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        executor = new RoutineExecutor(agentRuntimeRegistry, eventPublisher, clock);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /**
     * Replicates the realistic lifecycle: an agent is registered up-front; a routine is captured against the
     * agent-derived context id; the originating "session" is closed (no-op for the agent-scoped context); virtual
     * time advances to the next cron tick; and the cron re-fire still resolves the same context and runs successfully.
     */
    @Test
    void cronReFireResolvesAgentScopedContextAfterSessionEnds() {
        // 1. Bootstrap: register an agent-scoped AgentRuntime once.
        final Agent agent = DefaultAgent.builder().name("orca-test").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);

        final AtomicInteger invocationCount = new AtomicInteger();
        final Tool capturingTool = new RecordingTool("noop", invocationCount);
        final AgentRuntime agentContext = new StubAgentRuntime(boundRuntimeId, agent, List.of(capturingTool));
        agentRuntimeRegistry.register(agentContext);

        // 2. Capture a ScheduledTask whose boundRuntimeId is the agent-scoped id.
        final ScheduledTask task = newTask(boundRuntimeId, "noop");

        // 3. Run once (simulating a cron fire while the originating "session" is still active).
        final RoutineResult firstRun = executor.execute(task);
        assertThat(firstRun.isSuccess()).isTrue();
        assertThat(invocationCount.get()).isEqualTo(1);

        // 4. The session ends -- this must NOT close the agent-scoped context.
        // We assert the registry entry is still resolvable to the same instance.
        assertThat(agentRuntimeRegistry.get(boundRuntimeId)).containsSame(agentContext);

        // 5. Advance virtual clock to the next cron tick.
        clock.advance(Duration.ofMinutes(5));

        // 6. Simulate cron re-fire AFTER session has ended.
        final RoutineResult secondRun = executor.execute(task);

        // 7. The re-fire must resolve the same agent-scoped context and succeed.
        assertThat(secondRun.isSuccess()).isTrue();
        assertThat(invocationCount.get()).isEqualTo(2);

        // Sanity: started/completed timestamps move with the virtual clock.
        assertThat(secondRun.getStartedAt()).isAfterOrEqualTo(firstRun.getCompletedAt());
    }

    @Test
    void executionFailsWhenContextIsNotRegistered() {
        final Agent agent = DefaultAgent.builder().name("orca-missing").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);

        final ScheduledTask task = newTask(boundRuntimeId, "noop");

        final RoutineResult result = executor.execute(task);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isPresent()
                .hasValueSatisfying(msg -> assertThat(msg).contains("No agent runtime registered"));
    }

    @Test
    void timestampsAreDerivedFromInjectedClock() {
        final Agent agent = DefaultAgent.builder().name("orca-clock").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);

        final AtomicInteger invocationCount = new AtomicInteger();
        agentRuntimeRegistry.register(
                new StubAgentRuntime(boundRuntimeId, agent, List.of(new RecordingTool("noop", invocationCount))));

        final Instant fixedStart = clock.instant();
        final RoutineResult result = executor.execute(newTask(boundRuntimeId, "noop"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStartedAt()).isEqualTo(fixedStart);
        assertThat(result.getCompletedAt()).isEqualTo(fixedStart);
    }

    /**
     * A routine step is the only caller that cannot look its own scope up: it runs with no session and no turn
     * behind it, so whatever {@link RoutineExecutor} puts in the {@link ToolContext} is all a tool will ever see.
     *
     * <p>
     * The runtime id in particular has to be there for the scheduling tools to work at all from a routine —
     * {@code ScheduleTask} refuses the call without it, and {@code Task} / {@code TaskList} / {@code TaskStop} /
     * {@code AgentOutput} either throw or fall back to an unscoped view. Asserting on the value, not merely on its
     * presence, is what makes this a test: the executor resolves the tool from this same id, so a context carrying
     * some <i>other</i> runtime's id would still run the step successfully and hand the tool a scope it is not in.
     */
    @Test
    void routineStepsSeeTheBoundAgentRuntimeId() {
        final Agent agent = DefaultAgent.builder().name("orca-ctx").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);

        final ContextCapturingTool tool = new ContextCapturingTool("noop");
        agentRuntimeRegistry.register(new StubAgentRuntime(boundRuntimeId, agent, List.of(tool)));

        assertThat(executor.execute(newTask(boundRuntimeId, "noop")).isSuccess()).isTrue();

        assertThat(tool.captured()).isNotNull();
        assertThat(tool.captured().get(ToolContextKeys.AGENT_RUNTIME_ID)).contains(boundRuntimeId);
    }

    /**
     * Pins the owner as the task's, not {@code Principal.system()}.
     *
     * <p>
     * The distinction is invisible unless the task is owned by someone other than the system: {@code ScheduleTaskTool}
     * falls back to {@code Principal.system()} when the key is absent, so a system-owned fixture passes whether the
     * executor publishes the principal or not. Hence the deliberately non-system owner here — a routine step that
     * schedules follow-up work used to record the fallback and lose the human it was scheduled for.
     */
    @Test
    void routineStepsSeeTheTaskOwnerRatherThanTheSystemFallback() {
        final Agent agent = DefaultAgent.builder().name("orca-owner").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);
        final Principal owner = Principal.user("alice");

        final ContextCapturingTool tool = new ContextCapturingTool("noop");
        agentRuntimeRegistry.register(new StubAgentRuntime(boundRuntimeId, agent, List.of(tool)));

        assertThat(executor.execute(newTask(boundRuntimeId, "noop", owner)).isSuccess()).isTrue();

        assertThat(tool.captured().get(ToolContextKeys.PRINCIPAL)).contains(owner)
                .hasValueSatisfying(p -> assertThat(p).isNotEqualTo(Principal.system()));
    }

    /**
     * Pins the two keys the executor deliberately leaves out.
     *
     * <p>
     * A scheduled run has no session — nobody is waiting on it and there is no transcript to attribute it to —
     * and the absence is load-bearing rather than an omission. Both keys scope decisions a user made in a session
     * (skill approvals, for one); minting a synthetic id to fill them would key those decisions on a value no user
     * ever saw and that changes on every fire. This test exists because adding them looks like completing the wiring.
     */
    @Test
    void routineStepsCarryNoSessionIdentity() {
        final Agent agent = DefaultAgent.builder().name("orca-noconv").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);

        final ContextCapturingTool tool = new ContextCapturingTool("noop");
        agentRuntimeRegistry.register(new StubAgentRuntime(boundRuntimeId, agent, List.of(tool)));

        assertThat(executor.execute(newTask(boundRuntimeId, "noop")).isSuccess()).isTrue();

        assertThat(tool.captured().get(ToolContextKeys.SESSION_ID)).isEmpty();
        assertThat(tool.captured().get(ToolContextKeys.INVOKING_SESSION_ID)).isEmpty();
    }

    /**
     * The counterpart to the test above: the run is anonymous as to <i>who</i>, not as to <i>which run</i>.
     *
     * <p>
     * A step that needs to correlate its own log lines or partition per-run state has to have something to key on, and
     * an {@link ExecutionId} is the honest something — node-local, unleased, never mistaken for a session a user was
     * part of. Asserting freshness per fire is the substance here: the runtime id and the owner are stable across
     * fires by design, so a shared execution id would silently merge two runs' state.
     */
    @Test
    void routineStepsCarryAFreshExecutionIdPerFire() {
        final Agent agent = DefaultAgent.builder().name("orca-execid").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);

        final ContextCapturingTool tool = new ContextCapturingTool("noop");
        agentRuntimeRegistry.register(new StubAgentRuntime(boundRuntimeId, agent, List.of(tool)));

        final ScheduledTask task = newTask(boundRuntimeId, "noop");

        assertThat(executor.execute(task).isSuccess()).isTrue();
        final ExecutionId first = tool.captured().get(ToolContextKeys.EXECUTION_ID).orElseThrow();

        assertThat(executor.execute(task).isSuccess()).isTrue();
        final ExecutionId second = tool.captured().get(ToolContextKeys.EXECUTION_ID).orElseThrow();

        assertThat(first.value()).startsWith("routine:" + task.getId().value() + ":");
        assertThat(second).isNotEqualTo(first);
    }

    /**
     * Every step of one run must see the same context instance — the executor builds it once per routine, not per
     * step, so a later step cannot be handed a scope the earlier ones did not have.
     */
    @Test
    void allStepsOfOneRoutineShareTheSameContext() {
        final Agent agent = DefaultAgent.builder().name("orca-multistep").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);

        final ContextCapturingTool first = new ContextCapturingTool("first");
        final ContextCapturingTool second = new ContextCapturingTool("second");
        agentRuntimeRegistry.register(new StubAgentRuntime(boundRuntimeId, agent, List.of(first, second)));

        final ScheduledTask task = ScheduledTask.builder().id(ScheduledTaskId.generate()).name("two-step-task")
                .cronExpression("*/5 * * * *")
                .routine(List.of(
                        RoutineStep.builder().tool("first").toolParams("{}").maxRetries(0)
                                .timeout(Duration.ofSeconds(5)).build(),
                        RoutineStep.builder().tool("second").toolParams("{}").maxRetries(0)
                                .timeout(Duration.ofSeconds(5)).build()))
                .owner(Principal.user("alice")).boundRuntimeId(boundRuntimeId).enabled(true).build();

        assertThat(executor.execute(task).isSuccess()).isTrue();

        assertThat(second.captured()).isSameAs(first.captured());
    }

    /**
     * The whole point of recording a definition version: a task scheduled against one prompt fires against whatever
     * the runtime carries later, and the run must say so rather than pass silently.
     */
    @Test
    void driftBetweenScheduledAndCurrentDefinitionIsWarned() {
        final Agent scheduledAgainst = DefaultAgent.builder().name("orca-drift").systemPrompt("version one").build();
        final Agent editedSince = DefaultAgent.builder().name("orca-drift").systemPrompt("version two").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(scheduledAgainst);

        // Same name, so the same runtime id resolves — only the definition behind it changed.
        agentRuntimeRegistry.register(new StubAgentRuntime(boundRuntimeId, editedSince,
                List.of(new RecordingTool("noop", new AtomicInteger()))));

        final ScheduledTask task = newTask(boundRuntimeId, "noop", AgentDefinitionVersion.from(scheduledAgainst));

        final List<ILoggingEvent> events = captureLogsOf(() -> assertThat(executor.execute(task).isSuccess()).isTrue());

        assertThat(events)
                .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("changed since task")
                        && e.getFormattedMessage().contains(AgentDefinitionVersion.from(scheduledAgainst).value())
                        && e.getFormattedMessage().contains(AgentDefinitionVersion.from(editedSince).value()));
    }

    @Test
    void anUnchangedDefinitionIsNotWarnedAbout() {
        final Agent agent = DefaultAgent.builder().name("orca-nodrift").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);
        agentRuntimeRegistry.register(
                new StubAgentRuntime(boundRuntimeId, agent, List.of(new RecordingTool("noop", new AtomicInteger()))));

        final ScheduledTask task = newTask(boundRuntimeId, "noop", AgentDefinitionVersion.from(agent));

        final List<ILoggingEvent> events = captureLogsOf(() -> assertThat(executor.execute(task).isSuccess()).isTrue());

        assertThat(events).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    /**
     * An absent version disables the comparison — it must not fail the run, and it must not warn either, since there
     * is nothing to compare against. Tasks predating the field land here.
     */
    @Test
    void aTaskWithoutARecordedVersionRunsAndSaysNothing() {
        final Agent agent = DefaultAgent.builder().name("orca-noversion").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);
        agentRuntimeRegistry.register(new StubAgentRuntime(boundRuntimeId,
                DefaultAgent.builder().name("orca-noversion").systemPrompt("edited since").build(),
                List.of(new RecordingTool("noop", new AtomicInteger()))));

        final List<ILoggingEvent> events = captureLogsOf(
                () -> assertThat(executor.execute(newTask(boundRuntimeId, "noop")).isSuccess()).isTrue());

        assertThat(events).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    private static List<ILoggingEvent> captureLogsOf(Runnable action) {
        final Logger executorLogger = (Logger) LoggerFactory.getLogger(RoutineExecutor.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        executorLogger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            executorLogger.detachAppender(appender);
        }
    }

    private static ScheduledTask newTask(AgentRuntimeId boundRuntimeId, String toolName) {
        return newTask(boundRuntimeId, toolName, Principal.system());
    }

    private static ScheduledTask newTask(AgentRuntimeId boundRuntimeId, String toolName,
            AgentDefinitionVersion version) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("test-task").cronExpression("*/5 * * * *")
                .routine(List.of(RoutineStep.builder().tool(toolName).toolParams("{}").maxRetries(0)
                        .timeout(Duration.ofSeconds(5)).build()))
                .owner(Principal.system()).boundRuntimeId(boundRuntimeId).agentDefinitionVersion(version).enabled(true)
                .build();
    }

    private static ScheduledTask newTask(AgentRuntimeId boundRuntimeId, String toolName, Principal owner) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("test-task").cronExpression("*/5 * * * *")
                .routine(List.of(RoutineStep.builder().tool(toolName).toolParams("{}").maxRetries(0)
                        .timeout(Duration.ofSeconds(5)).build()))
                .owner(owner).boundRuntimeId(boundRuntimeId).enabled(true).build();
    }

    /**
     * Minimal {@link AgentRuntime} stub returning a fixed tool list. The lifecycle contract under test is
     * <i>registry-level</i> (the context survives across simulated cron re-fires), so no executor wiring is needed.
     */
    private static final class StubAgentRuntime implements AgentRuntime {

        private final AgentRuntimeId id;
        private final Agent agent;
        private final List<Tool> tools;

        StubAgentRuntime(AgentRuntimeId id, Agent agent, List<Tool> tools) {
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

    /**
     * Test tool that records each invocation. Always succeeds.
     */
    private static final class RecordingTool extends AbstractTool {

        private final AtomicInteger invocationCount;

        RecordingTool(String name, AtomicInteger invocationCount) {
            super(name, "test tool", Map.of("type", "object", "properties", Map.of()));
            this.invocationCount = invocationCount;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            invocationCount.incrementAndGet();
            return ToolResult.success("ok");
        }
    }

    /**
     * Test tool that records the {@link ToolContext} it was handed. Always succeeds.
     *
     * <p>
     * The capture goes through an {@link AtomicReference} because steps run on the executor's timeout pool, not on the
     * test thread, so a plain field would be a data race rather than merely untidy.
     */
    private static final class ContextCapturingTool extends AbstractTool {

        private final AtomicReference<ToolContext> seen = new AtomicReference<>();

        ContextCapturingTool(String name) {
            super(name, "test tool", Map.of("type", "object", "properties", Map.of()));
        }

        ToolContext captured() {
            return seen.get();
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            seen.set(context);
            return ToolResult.success("ok");
        }
    }

    /**
     * Step-advancing virtual {@link Clock}. {@code instant()} is deterministic; {@code advance(Duration)} moves time
     * forward in the test thread. Not thread-safe by design — production tests run synchronously, and the read race for
     * the timeout-thread side is benign (wall-clock semantics are not asserted there).
     */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            now = new AtomicReference<>(Objects.requireNonNull(initial));
        }

        void advance(Duration delta) {
            now.updateAndGet(current -> current.plus(delta));
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
