/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.InterruptAccess;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.scheduler.ScheduledFire;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Tracer;

/**
 * Pins the occurrence-targeted interrupt and the non-destructive drain view of {@link RoutineExecutor}.
 *
 * <p>
 * The step here is cooperative: it parks until the test releases it or the run's signal trips, so a run that is
 * interrupted ends as cancelled and one that is not ends as a success once released.
 */
class RoutineExecutorOccurrenceAndDrainTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private static final Instant SLOT_1 = Instant.parse("2026-01-01T09:00:00Z");
    private static final Instant SLOT_2 = Instant.parse("2026-01-01T09:05:00Z");

    private final DefaultAgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
    private RoutineExecutor executor;
    private ParkingTool tool;
    private ScheduledTask task;

    @BeforeEach
    void setUp() {
        executor = new RoutineExecutor(registry, new SimpleScheduledTaskEventPublisher());
        tool = new ParkingTool();
        final Agent agent = DefaultAgent.builder().name("occurrence-test").systemPrompt("test").build();
        final AgentRuntimeId runtimeId = AgentRuntimeId.from(agent);
        registry.register(new StubAgentRuntime(runtimeId, agent, List.of(tool)));
        task = ScheduledTask.builder().id(ScheduledTaskId.generate()).name("occurrence-task")
                .cronExpression("*/5 * * * *").owner(Principal.system()).boundRuntimeId(runtimeId)
                .routine(List.of(RoutineStep.builder().tool(ParkingTool.TOOL_NAME).toolParams("{}").maxRetries(0)
                        .timeout(Duration.ofSeconds(30)).build()))
                .enabled(true).build();
    }

    @AfterEach
    void tearDown() {
        tool.release.release(100);
        executor.shutdown();
    }

    @Test
    void aLateInterruptForAFinishedOccurrenceDoesNotStopTheNextOccurrencesRun() throws Exception {
        final ScheduledFire first = ScheduledFire.of(task.getId(), SLOT_1, SLOT_1);
        final ScheduledFire second = ScheduledFire.of(task.getId(), SLOT_2, SLOT_2);
        final CompletableFuture<RoutineResult> run = startAndAwaitEntry(second);

        // The cancel was aimed at the first occurrence, which has already finished; it arrives while the second runs.
        assertThat(executor.interrupt(task.getId(), first.getOccurrenceKey().orElseThrow(),
                InterruptReason.TASK_CANCELLED)).isFalse();

        tool.release.release();
        final RoutineResult result = run.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isCancelled()).isFalse();
    }

    @Test
    void anInterruptForTheRunningOccurrenceStopsIt() throws Exception {
        final ScheduledFire fire = ScheduledFire.of(task.getId(), SLOT_2, SLOT_2);
        final CompletableFuture<RoutineResult> run = startAndAwaitEntry(fire);

        assertThat(
                executor.interrupt(task.getId(), fire.getOccurrenceKey().orElseThrow(), InterruptReason.TASK_CANCELLED))
                .isTrue();

        final RoutineResult result = run.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        assertThat(result.isCancelled()).isTrue();
        assertThat(result.getInterruptReason()).contains(InterruptReason.TASK_CANCELLED);
    }

    @Test
    void aRunWithoutASlotIsNotMatchedByAnOccurrenceKey() throws Exception {
        final CompletableFuture<RoutineResult> run = startAndAwaitEntry(ScheduledFire.untimed(task.getId()));

        assertThat(executor.interrupt(task.getId(), task.getId().value() + "@" + SLOT_1.toEpochMilli(),
                InterruptReason.TASK_CANCELLED)).isFalse();

        tool.release.release();
        assertThat(run.get(PATIENCE.toSeconds(), TimeUnit.SECONDS).isSuccess()).isTrue();
    }

    @Test
    void theTaskWideInterruptStillStopsEveryRunOfTheTask() throws Exception {
        final CompletableFuture<RoutineResult> first = startAndAwaitEntry(
                ScheduledFire.of(task.getId(), SLOT_1, SLOT_1));
        final CompletableFuture<RoutineResult> second = startAndAwaitEntry(
                ScheduledFire.of(task.getId(), SLOT_2, SLOT_2));
        final CompletableFuture<RoutineResult> untimed = startAndAwaitEntry(null);
        assertThat(executor.inFlightRunCount()).isEqualTo(3);

        assertThat(executor.interrupt(task.getId(), InterruptReason.TASK_CANCELLED)).isTrue();

        assertThat(first.get(PATIENCE.toSeconds(), TimeUnit.SECONDS).isCancelled()).isTrue();
        assertThat(second.get(PATIENCE.toSeconds(), TimeUnit.SECONDS).isCancelled()).isTrue();
        assertThat(untimed.get(PATIENCE.toSeconds(), TimeUnit.SECONDS).isCancelled()).isTrue();
        assertThat(executor.awaitIdle(PATIENCE)).isTrue();
    }

    @Test
    void aFireOfAnotherTaskIsRefused() {
        final ScheduledFire foreign = ScheduledFire.of(ScheduledTaskId.generate(), SLOT_1, SLOT_1);

        assertThatThrownBy(() -> executor.execute(task, foreign)).isInstanceOf(IllegalArgumentException.class);
        assertThat(executor.inFlightRunCount()).isZero();
    }

    @Test
    void awaitIdleTimesOutWithoutInterruptingTheRunningRoutine() throws Exception {
        assertThat(executor.awaitIdle(Duration.ZERO)).isTrue();
        final CompletableFuture<RoutineResult> run = startAndAwaitEntry(ScheduledFire.of(task.getId(), SLOT_1, SLOT_1));

        assertThat(executor.inFlightRunCount()).isEqualTo(1);
        assertThat(executor.awaitIdle(Duration.ofMillis(200))).isFalse();
        // Still running, and its signal untouched: the wait only watched.
        assertThat(executor.isRunning(task.getId())).isTrue();
        assertThat(tool.sawCancellation).isFalse();

        tool.release.release();
        final RoutineResult result = run.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(executor.awaitIdle(PATIENCE)).isTrue();
        assertThat(executor.inFlightRunCount()).isZero();
    }

    @Test
    void aTracerThatThrowsOnCloseDoesNotLeaveTheRunRegistered() throws Exception {
        final Tracer.Span root = mock(Tracer.Span.class);
        doThrow(new IllegalStateException("tracer broke on close")).when(root).close();
        final Tracer tracer = mock(Tracer.class);
        when(tracer.startRoot(anyString(), any(SpanType.class), anyString(), anyMap())).thenReturn(root);
        executor.withTracer(tracer);
        tool.release.release();

        final ScheduledFire fire = ScheduledFire.of(task.getId(), SLOT_1, SLOT_1);
        assertThatThrownBy(() -> executor.execute(task, fire)).hasMessageContaining("tracer broke on close");

        // The run is gone from the registry despite the throw: the count is back to zero and a drain completes.
        assertThat(executor.inFlightRunCount()).isZero();
        assertThat(executor.isRunning(task.getId())).isFalse();
        assertThat(executor.awaitIdle(Duration.ZERO)).isTrue();
        assertThat(
                executor.interrupt(task.getId(), fire.getOccurrenceKey().orElseThrow(), InterruptReason.TASK_CANCELLED))
                .isFalse();
    }

    @Test
    void aBlankOccurrenceKeyIsRefused() {
        assertThatThrownBy(() -> executor.interrupt(task.getId(), "", InterruptReason.TASK_CANCELLED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.interrupt(task.getId(), "  ", InterruptReason.TASK_CANCELLED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void untimedAndDirectRunsAreTheSameEntryPoint() throws Exception {
        tool.release.release();
        assertThat(executor.execute(task).isSuccess()).isTrue();
        tool.release.release();
        assertThat(executor.execute(task, ScheduledFire.untimed(task.getId())).isSuccess()).isTrue();
        assertThat(executor.inFlightRunCount()).isZero();
    }

    @Test
    void aHugeTimeoutIsClampedRatherThanOverflowing() throws Exception {
        assertThat(executor.awaitIdle(Duration.ofSeconds(Long.MAX_VALUE))).isTrue();
        final CompletableFuture<RoutineResult> run = startAndAwaitEntry(ScheduledFire.of(task.getId(), SLOT_1, SLOT_1));
        final CompletableFuture<Boolean> idle = CompletableFuture.supplyAsync(() -> {
            try {
                return executor.awaitIdle(Duration.ofSeconds(Long.MAX_VALUE));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        // The huge wait waits (it neither throws nor returns at once) until the run ends.
        Thread.sleep(100);
        assertThat(idle).isNotDone();
        tool.release.release();
        assertThat(run.get(PATIENCE.toSeconds(), TimeUnit.SECONDS).isSuccess()).isTrue();
        assertThat(idle.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    private CompletableFuture<RoutineResult> startAndAwaitEntry(ScheduledFire fire) throws InterruptedException {
        final CompletableFuture<RoutineResult> run = CompletableFuture.supplyAsync(() -> executor.execute(task, fire));
        assertThat(tool.entered.tryAcquire(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)).as("step entered").isTrue();
        return run;
    }

    /** Parks until released, or until the run's signal trips — cooperative, so an interrupt ends it promptly. */
    private static final class ParkingTool extends AbstractTool {

        static final String TOOL_NAME = "parker";

        final Semaphore entered = new Semaphore(0);
        final Semaphore release = new Semaphore(0);
        volatile boolean sawCancellation;

        ParkingTool() {
            super(TOOL_NAME, "parks", Map.of("type", "object", "additionalProperties", false, "properties", Map.of()));
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.COOPERATIVE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            entered.release();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            try {
                while (System.nanoTime() < deadline) {
                    if (InterruptAccess.signalOf(context).isCancelled()) {
                        sawCancellation = true;
                        return ToolResult.error("cancelled");
                    }
                    if (release.tryAcquire(20, TimeUnit.MILLISECONDS)) {
                        return ToolResult.success("ok");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            }
            return ToolResult.error("never released");
        }
    }

    private static final class StubAgentRuntime implements AgentRuntime {

        private final AgentRuntimeId id;
        private final Agent agent;
        private final List<Tool> tools;

        StubAgentRuntime(AgentRuntimeId id, Agent agent, List<Tool> tools) {
            this.id = id;
            this.agent = agent;
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
