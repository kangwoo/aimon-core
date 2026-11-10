/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
import at.aimon.core.scheduling.exception.TaskSchedulerException;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.InMemoryTaskScheduler;
import at.aimon.core.scheduling.scheduler.ScheduledFire;
import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;

/**
 * Pins the drain seam of {@link SchedulingEngine}: pausing fires stops new runs on this node while the run already in
 * progress completes untouched, and waiting for idle never interrupts.
 */
class SchedulingEngineDrainTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);
    private static final Instant SLOT_1 = Instant.parse("2026-01-01T09:00:00Z");
    private static final Instant SLOT_2 = Instant.parse("2026-01-01T09:05:00Z");

    private final Principal alice = Principal.user("alice");
    private final InMemoryScheduledTaskExecutionHistoryRepository history = new InMemoryScheduledTaskExecutionHistoryRepository();
    private final GateTool tool = new GateTool();
    private final DefaultAgentRuntimeRegistry runtimes = new DefaultAgentRuntimeRegistry();
    private SchedulingEngine engine;
    private ScheduledTask task;

    @BeforeEach
    void setUp() {
        final Agent agent = DefaultAgent.builder().name("drain-test").systemPrompt("test").build();
        final AgentRuntimeId runtimeId = AgentRuntimeId.from(agent);
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

        assertThat(engine.pauseFires()).as("the in-memory scheduler can pause").isTrue();
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

        // Resuming admits fires again — and the very occurrence refused while paused runs now, which shows the
        // refusal never claimed it.
        engine.resumeFires();
        assertThat(engine.isAcceptingFires()).isTrue();
        tool.release.release();
        manager.executeFire(ScheduledFire.of(task.getId(), SLOT_2, SLOT_2));
        assertThat(tool.invocations).hasValue(2);
        assertThat(history.findByTaskIdOrderByStartedAtDesc(task.getId(), 10)).hasSize(2);
    }

    @Test
    void aSchedulerThatFailsToPauseStillLeavesTheManagerRefusingFires() {
        final SchedulingEngine failing = SchedulingEngineBuilder.create()
                .taskRepository(new InMemoryScheduledTaskRepository())
                .taskSchedulerFactory(executor -> new InMemoryTaskScheduler(executor) {
                    @Override
                    public boolean pauseFiring() {
                        throw new TaskSchedulerException("standby failed");
                    }
                }).build();
        try {
            failing.start();
            assertThatThrownBy(failing::pauseFires).hasMessageContaining("standby failed");
            assertThat(failing.isAcceptingFires()).isFalse();
        } finally {
            failing.close();
        }
    }

    @Test
    void aSchedulerThatCannotPauseIsReportedAndTheManagerStillRefusesFires() {
        final SchedulingEngine unpausable = SchedulingEngineBuilder.create()
                .taskRepository(new InMemoryScheduledTaskRepository()).historyRepository(history)
                .agentRuntimeRegistry(runtimes).taskSchedulerFactory(executor -> new InMemoryTaskScheduler(executor) {
                    @Override
                    public boolean pauseFiring() {
                        return false; // the TaskScheduler default: cannot pause
                    }
                }).build();
        try {
            unpausable.start();
            unpausable.getTaskManager().register(task);

            assertThat(unpausable.pauseFires()).as("the caller learns the scheduler kept firing").isFalse();
            assertThat(unpausable.isAcceptingFires()).isFalse();

            // A fire the scheduler still hands this node is refused before any run starts.
            unpausable.getTaskManager().executeFire(ScheduledFire.of(task.getId(), SLOT_1, SLOT_1));
            assertThat(tool.invocations).hasValue(0);
            assertThat(history.findByTaskIdOrderByStartedAtDesc(task.getId(), 10)).isEmpty();
        } finally {
            unpausable.close();
        }
    }

    @Test
    void aResumeRacingAPauseWaitsForThePauseToFinishOnBothHalves() throws Exception {
        final CountDownLatch pauseEntered = new CountDownLatch(1);
        final CountDownLatch finishPause = new CountDownLatch(1);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final SchedulingEngine racing = SchedulingEngineBuilder.create()
                .taskRepository(new InMemoryScheduledTaskRepository())
                .taskSchedulerFactory(executor -> new RecordingScheduler(executor, calls) {
                    @Override
                    public boolean pauseFiring() {
                        final boolean paused = super.pauseFiring();
                        pauseEntered.countDown();
                        try {
                            finishPause.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return paused;
                    }
                }).build();
        try {
            racing.start();
            final CompletableFuture<Boolean> pause = CompletableFuture.supplyAsync(racing::pauseFires);
            assertThat(pauseEntered.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            // The scheduler half of the pause is done, the manager half is not. A resume now must not slip in between:
            // it would restart the scheduler and then be undone by the manager half, leaving fires refused.
            final CompletableFuture<Void> resume = CompletableFuture.runAsync(racing::resumeFires);
            Thread.sleep(200L);
            assertThat(resume).as("resume waits for the pause to finish").isNotDone();

            finishPause.countDown();
            assertThat(pause.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
            resume.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

            // Resume ran last on both halves: the manager admits and the scheduler fires again.
            assertThat(racing.isAcceptingFires()).isTrue();
            assertThat(calls).containsExactly("start", "pause", "resume");
        } finally {
            finishPause.countDown();
            racing.close();
        }
    }

    @Test
    void aPauseBeforeStartIsStillInEffectAfterStart() {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final SchedulingEngine early = SchedulingEngineBuilder.create()
                .taskRepository(new InMemoryScheduledTaskRepository())
                .taskSchedulerFactory(executor -> new RecordingScheduler(executor, calls)).build();
        try {
            early.pauseFires();
            early.start();

            assertThat(early.isAcceptingFires()).isFalse();
            // Paused before start, and asked again once started, for a scheduler that forgot the first request.
            assertThat(calls).containsExactly("pause", "start", "pause");

            early.resumeFires();
            assertThat(early.isAcceptingFires()).isTrue();
            assertThat(calls).endsWith("resume");
        } finally {
            early.close();
        }
    }

    @Test
    void aHugeTimeoutIsClampedRatherThanThrowing() throws Exception {
        assertThat(engine.awaitIdle(Duration.ofSeconds(Long.MAX_VALUE))).isTrue();
    }

    /** Records the lifecycle calls the engine makes on its scheduler, in order. */
    private static class RecordingScheduler extends InMemoryTaskScheduler {

        private final List<String> calls;

        RecordingScheduler(ScheduledTaskExecutor executor, List<String> calls) {
            super(executor);
            this.calls = calls;
        }

        @Override
        public void start() {
            calls.add("start");
            super.start();
        }

        @Override
        public boolean pauseFiring() {
            calls.add("pause");
            return super.pauseFiring();
        }

        @Override
        public void resumeFiring() {
            calls.add("resume");
            super.resumeFiring();
        }
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
