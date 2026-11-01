/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.IdempotencyBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.StepFailedEvent;
import at.aimon.core.scheduling.event.TaskFailedEvent;
import at.aimon.core.scheduling.event.TaskHaltedEvent;

/**
 * Which failed steps are retried (side-effect-axes §8.2: only read-only and idempotent tools), and how a step that
 * halts its routine ends the run.
 */
class RoutineExecutorRetrySafetyAndHaltTest {

    private static final Duration NO_DELAY = Duration.ofMillis(1);

    private DefaultAgentRuntimeRegistry registry;
    private RoutineExecutor executor;
    private Events events;
    private AgentRuntimeId runtimeId;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRuntimeRegistry();
        final SimpleScheduledTaskEventPublisher publisher = new SimpleScheduledTaskEventPublisher();
        events = new Events();
        publisher.addListener(events);
        executor = new RoutineExecutor(registry, publisher);
        runtimeId = AgentRuntimeId.fromName("retry-safety");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Nested
    @DisplayName("retries")
    class Retries {

        @Test
        @DisplayName("a tool that declares nothing writes and is not idempotent, so it runs once whatever max_retries says")
        void undeclaredToolIsNotRetried() {
            final CountingTool tool = new CountingTool("Send", () -> ToolResult.error("boom"));
            register(tool);

            final RoutineResult result = executor.execute(task(RoutineStep.withRetry("Send", "{}", 3, NO_DELAY)));

            assertThat(tool.calls).hasValue(1);
            assertThat(result.getStepResults()).singleElement()
                    .satisfies(step -> assertThat(step.getAttemptCount()).isEqualTo(1));
            assertThat(events.failed).hasSize(1);
        }

        @Test
        @DisplayName("a writing tool that declares itself idempotent keeps every retry")
        void idempotentToolIsRetried() {
            final CountingTool tool = new CountingTool("Upsert", () -> ToolResult.error("boom"))
                    .idempotency(IdempotencyBehavior.IDEMPOTENT);
            register(tool);

            executor.execute(task(RoutineStep.withRetry("Upsert", "{}", 2, NO_DELAY)));

            assertThat(tool.calls).hasValue(3);
        }

        @Test
        @DisplayName("a read-only tool keeps every retry; idempotency is not even consulted below MUTATING")
        void readOnlyToolIsRetried() {
            final CountingTool tool = new CountingTool("Peek", () -> ToolResult.error("boom"))
                    .level(SideEffectLevel.READ_ONLY);
            register(tool);

            executor.execute(task(RoutineStep.withRetry("Peek", "{}", 2, NO_DELAY)));

            assertThat(tool.calls).hasValue(3);
        }

        @Test
        @DisplayName("a failure the tool marked not retryable is not retried, even for an idempotent tool")
        void notRetryableFailureIsNotRetried() {
            final CountingTool tool = new CountingTool("Upsert", () -> ToolResult.error("too large").notRetryable())
                    .idempotency(IdempotencyBehavior.IDEMPOTENT);
            register(tool);

            executor.execute(task(RoutineStep.withRetry("Upsert", "{}", 3, NO_DELAY)));

            assertThat(tool.calls).hasValue(1);
        }

        @Test
        @DisplayName("an attempt that failed before its tool was found ran nothing and keeps its retries")
        void unresolvedToolKeepsRetries() {
            // No runtime registered at all: every attempt fails at resolution.
            final RoutineResult result = executor.execute(task(RoutineStep.withRetry("Send", "{}", 2, NO_DELAY)));

            assertThat(result.getStepResults()).singleElement().satisfies(step -> {
                assertThat(step.getAttemptCount()).isEqualTo(3);
                assertThat(step.getErrorMessage()).hasValueSatisfying(
                        message -> assertThat(message).contains("No agent runtime registered for binding"));
            });
        }

        @Test
        @DisplayName("a step whose last attempt timed out is recorded as outcome unknown")
        void timeoutIsOutcomeUnknown() {
            final CountingTool tool = new CountingTool("Slow", () -> {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.success("late");
            });
            register(tool);

            final RoutineResult result = executor.execute(task(RoutineStep.builder().tool("Slow").toolParams("{}")
                    .maxRetries(3).retryDelay(NO_DELAY).timeout(Duration.ofMillis(50)).build()));

            assertThat(tool.calls).as("a timed-out write is not repeated").hasValue(1);
            assertThat(result.getStepResults()).singleElement().satisfies(step -> {
                assertThat(step.isOutcomeUnknown()).isTrue();
                assertThat(step.getErrorMessage()).hasValueSatisfying(
                        message -> assertThat(message).contains("timed out").contains("outcome unknown"));
            });
        }

        @Test
        @DisplayName("an ordinary failure is not outcome unknown")
        void ordinaryFailureIsKnown() {
            register(new CountingTool("Send", () -> ToolResult.error("boom")));

            final RoutineResult result = executor.execute(task(RoutineStep.of("Send", "{}")));

            assertThat(result.getStepResults()).singleElement()
                    .satisfies(step -> assertThat(step.isOutcomeUnknown()).isFalse());
        }
    }

    @Nested
    @DisplayName("halting")
    class Halting {

        @Test
        @DisplayName("a halting step ends the run as halted: no retry, no later step, a halt event and no failure")
        void haltEndsTheRunNormally() {
            final CountingTool gate = new CountingTool("Approve",
                    () -> ToolResult.success("approval requested").haltingRoutine("awaiting approval #42"))
                    .idempotency(IdempotencyBehavior.IDEMPOTENT);
            final CountingTool next = new CountingTool("Apply", () -> ToolResult.success("applied"));
            register(gate, next);

            final RoutineResult result = executor
                    .execute(task(RoutineStep.withRetry("Approve", "{}", 3, NO_DELAY), RoutineStep.of("Apply", "{}")));

            assertThat(gate.calls).hasValue(1);
            assertThat(next.calls).as("the step after the halt never starts").hasValue(0);
            assertThat(result.isHalted()).isTrue();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isCancelled()).isFalse();
            assertThat(result.getHaltReason()).hasValue("awaiting approval #42");
            assertThat(result.getStepResults()).singleElement().satisfies(step -> {
                assertThat(step.isHalted()).isTrue();
                assertThat(step.getStdout()).hasValue("approval requested");
            });
            assertThat(events.halted).singleElement()
                    .satisfies(event -> assertThat(event.getReason()).isEqualTo("awaiting approval #42"));
            assertThat(events.failed).isEmpty();
            assertThat(events.stepFailed).isEmpty();
        }

        @Test
        @DisplayName("a halt on an error result still halts rather than failing and retrying")
        void haltWinsOverError() {
            final CountingTool gate = new CountingTool("Approve",
                    () -> ToolResult.error("pending").haltingRoutine("awaiting approval"))
                    .idempotency(IdempotencyBehavior.IDEMPOTENT);
            register(gate);

            final RoutineResult result = executor.execute(task(RoutineStep.withRetry("Approve", "{}", 3, NO_DELAY)));

            assertThat(gate.calls).hasValue(1);
            assertThat(result.isHalted()).isTrue();
            assertThat(events.failed).isEmpty();
        }

        @Test
        @DisplayName("a stop that landed while the halting step ran wins: the run is cancelled, not halted")
        void cancelWinsOverHalt() {
            final ScheduledTaskId id = ScheduledTaskId.generate();
            register(new CountingTool("Approve", () -> {
                executor.interrupt(id, at.aimon.core.agent.interrupt.InterruptReason.TASK_CANCELLED);
                return ToolResult.success("ok").haltingRoutine("awaiting approval");
            }));

            final RoutineResult result = executor.execute(ScheduledTask.builder().id(id).name("t")
                    .cronExpression("* * * * *").routine(List.of(RoutineStep.of("Approve", "{}")))
                    .owner(Principal.user("alice")).boundRuntimeId(runtimeId).enabled(true).build());

            assertThat(result.isCancelled()).isTrue();
            assertThat(result.isHalted()).isFalse();
            assertThat(events.halted).isEmpty();
        }

        @Test
        @DisplayName("the history of a halted run is HALTED and carries the reason")
        void historyIsHalted() {
            register(new CountingTool("Approve", () -> ToolResult.success("ok").haltingRoutine("awaiting approval")));

            final RoutineResult result = executor.execute(task(RoutineStep.of("Approve", "{}")));
            final ScheduledTaskExecutionHistory history = ScheduledTaskExecutionHistory.fromRoutineResult("h1", result);

            assertThat(history.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.HALTED);
            assertThat(history.getErrorMessage()).hasValue("awaiting approval");
        }
    }

    private void register(Tool... tools) {
        final Agent agent = DefaultAgent.builder().name("retry-safety").systemPrompt("test").build();
        registry.register(new FakeRuntime(runtimeId, agent, List.of(tools)));
    }

    private ScheduledTask task(RoutineStep... steps) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("t").cronExpression("* * * * *")
                .routine(List.of(steps)).owner(Principal.user("alice")).boundRuntimeId(runtimeId).enabled(true).build();
    }

    private static final class CountingTool extends AbstractTool {
        final AtomicInteger calls = new AtomicInteger();
        private final Supplier<ToolResult> body;
        private SideEffectLevel level = SideEffectLevel.MUTATING;
        private IdempotencyBehavior idempotency = IdempotencyBehavior.NON_IDEMPOTENT;

        CountingTool(String name, Supplier<ToolResult> body) {
            super(name, "test tool", Map.of("type", "object", "properties", Map.of()));
            this.body = body;
        }

        CountingTool level(SideEffectLevel level) {
            this.level = level;
            return this;
        }

        CountingTool idempotency(IdempotencyBehavior idempotency) {
            this.idempotency = idempotency;
            return this;
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return level;
        }

        @Override
        public IdempotencyBehavior getIdempotencyBehavior() {
            return idempotency;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            calls.incrementAndGet();
            return body.get();
        }
    }

    private static final class Events implements ScheduledTaskEventListener {
        final List<TaskHaltedEvent> halted = new CopyOnWriteArrayList<>();
        final List<TaskFailedEvent> failed = new CopyOnWriteArrayList<>();
        final List<StepFailedEvent> stepFailed = new CopyOnWriteArrayList<>();

        @Override
        public void onTaskHalted(TaskHaltedEvent event) {
            halted.add(event);
        }

        @Override
        public void onTaskFailed(TaskFailedEvent event) {
            failed.add(event);
        }

        @Override
        public void onStepFailed(StepFailedEvent event) {
            stepFailed.add(event);
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
