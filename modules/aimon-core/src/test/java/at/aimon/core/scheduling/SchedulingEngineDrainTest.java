/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.ScheduledFire;

/**
 * Pins the drain seam of {@link SchedulingEngine}: pausing fires stops new runs on this node while the run already in
 * progress completes untouched, and waiting for idle never interrupts.
 */
class SchedulingEngineDrainTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);
    private static final Instant SLOT_1 = Instant.parse("2026-01-01T09:00:00Z");
    private static final Instant SLOT_2 = Instant.parse("2026-01-01T09:05:00Z");
    private static final Instant SLOT_3 = Instant.parse("2026-01-01T09:10:00Z");

    private final Principal alice = Principal.user("alice");
    private final InMemoryScheduledTaskExecutionHistoryRepository history = new InMemoryScheduledTaskExecutionHistoryRepository();
    private final GateTool tool = new GateTool();
    private SchedulingEngine engine;
    private ScheduledTask task;

    @BeforeEach
    void setUp() {
        final Agent agent = DefaultAgent.builder().name("drain-test").systemPrompt("test").build();
        final AgentRuntimeId runtimeId = AgentRuntimeId.from(agent);
        final DefaultAgentRuntimeRegistry runtimes = new DefaultAgentRuntimeRegistry();
        runtimes.register(new StubAgentRuntime(runtimeId, agent, List.of(tool)));
        engine = SchedulingEngineBuilder.create().taskRepository(new InMemoryScheduledTaskRepository())
                .historyRepository(history).agentRuntimeRegistry(runtimes).build();
        engine.start();
        // Once a year, so the only runs are the ones fired by hand below.
        task = ScheduledTask.builder().id(ScheduledTaskId.generate()).name("drain-task").cronExpression("0 0 1 1 *")
                .owner(alice).boundRuntimeId(runtimeId).routine(List.of(RoutineStep.builder().tool(GateTool.TOOL_NAME)
                        .toolParams("{}").maxRetries(0).timeout(Duration.ofSeconds(30)).build()))
                .enabled(true).build();
        engine.getTaskManager().register(task);
    }

    @AfterEach
    void tearDown() {
        tool.release.release(100);
        engine.close();
    }

    @Test
    void pausingFiresStopsNewRunsWhileTheRunInProgressCompletes() throws Exception {
        final ScheduledTaskManager manager = engine.getTaskManager();
        final CompletableFuture<Void> inFlight = CompletableFuture
                .runAsync(() -> manager.executeFire(ScheduledFire.of(task.getId(), SLOT_1, SLOT_1)));
        assertThat(tool.entered.tryAcquire(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        engine.pauseFires();
        assertThat(engine.isAcceptingFires()).isFalse();

        // A fire that arrives while paused is refused at once: no run, no claim on the occurrence.
        manager.executeFire(ScheduledFire.of(task.getId(), SLOT_2, SLOT_2));
        assertThat(tool.invocations).hasValue(1);
        assertThat(engine.inFlightRunCount()).isEqualTo(1);

        // Waiting does not interrupt: it times out and the run is still going.
        assertThat(engine.awaitIdle(Duration.ofMillis(200))).isFalse();
        assertThat(engine.inFlightRunCount()).isEqualTo(1);

        tool.release.release();
        inFlight.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        assertThat(engine.awaitIdle(PATIENCE)).isTrue();
        assertThat(history.findByTaskIdOrderByStartedAtDesc(task.getId(), 10)).singleElement()
                .satisfies(h -> assertThat(h.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.SUCCESS));

        // Resuming admits fires again.
        engine.resumeFires();
        assertThat(engine.isAcceptingFires()).isTrue();
        tool.release.release();
        manager.executeFire(ScheduledFire.of(task.getId(), SLOT_3, SLOT_3));
        assertThat(tool.invocations).hasValue(2);
    }

    @Test
    void pausingFiresIsNotClosing() throws Exception {
        engine.pauseFires();
        engine.pauseFires();

        // The engine is application-scoped and still serves its task manager while paused.
        assertThat(engine.getTaskManager().listAll()).hasSize(1);
        assertThat(engine.awaitIdle(Duration.ZERO)).isTrue();
        engine.resumeFires();
        tool.release.release();
        engine.getTaskManager().executeTask(task.getId());
        assertThat(tool.invocations).hasValue(1);
    }

    /** A step that waits for the test to let it finish. */
    private static final class GateTool extends AbstractTool {

        static final String TOOL_NAME = "gate";

        final Semaphore entered = new Semaphore(0);
        final Semaphore release = new Semaphore(0);
        final AtomicInteger invocations = new AtomicInteger();

        GateTool() {
            super(TOOL_NAME, "waits for the gate",
                    Map.of("type", "object", "additionalProperties", false, "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            invocations.incrementAndGet();
            entered.release();
            try {
                return release.tryAcquire(30, TimeUnit.SECONDS)
                        ? ToolResult.success("ok")
                        : ToolResult.error("never released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            }
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
