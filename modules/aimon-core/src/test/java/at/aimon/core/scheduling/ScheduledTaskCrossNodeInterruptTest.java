/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.quota.DefaultTaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

/**
 * Pins the reach of a stop request across nodes: cancelling on the node a user happens to be talking to stops the run
 * that a different node is holding.
 *
 * <p>
 * Two {@link SchedulingEngine}s stand in for two nodes. They share what a cluster shares — the task repository, the
 * history repository, the quota ledger and one {@link ScheduledTaskInterruptBus} — and keep separate what a cluster
 * keeps separate: their own {@link RoutineExecutor}, and with it the in-flight registry that made this a problem in
 * the first place. Only the <em>holder</em> can resolve the task's {@code boundRuntimeId}, which is what makes it the
 * only node a run can be on.
 *
 * <p>
 * The engines are built through the package-private constructor rather than the builder, because the test needs to
 * keep hold of each node's executor to run and observe against — but the subscription under test is the real one the
 * constructor makes, not a hand-made stand-in.
 */
class ScheduledTaskCrossNodeInterruptTest {

    private static final Duration TEST_PATIENCE = Duration.ofSeconds(10);

    private final Principal alice = Principal.user("alice");
    private final CountDownLatch stepEntered = new CountDownLatch(1);
    private final CountDownLatch neverCounted = new CountDownLatch(1);

    private final InMemoryScheduledTaskRepository taskRepo = new InMemoryScheduledTaskRepository();
    private final InMemoryScheduledTaskExecutionHistoryRepository historyRepo = new InMemoryScheduledTaskExecutionHistoryRepository();
    private final DefaultTaskQuotaManager quota = new DefaultTaskQuotaManager(5);

    private AgentRuntimeId runtimeId;

    /** The node a cron fired on, and therefore the only node with a run to stop. */
    private RoutineExecutor holderExecutor;
    private ScheduledTaskManager holder;
    private SchedulingEngine holderEngine;

    /** The node the cancellation is entered on, which is running nothing at all. */
    private RoutineExecutor entryExecutor;
    private ScheduledTaskManager entryPoint;
    private SchedulingEngine entryEngine;

    @AfterEach
    void tearDown() {
        neverCounted.countDown();
        if (holderEngine != null) {
            holderEngine.close();
        }
        if (entryEngine != null) {
            entryEngine.close();
        }
    }

    /**
     * The headline guarantee, and the whole point of the bus: the run stops on the node that has it.
     *
     * <p>
     * The blocking step parks for far longer than this test is willing to wait, so the run can only finish inside
     * {@link #TEST_PATIENCE} if the request actually crossed — a regression that dropped the fan-out fails here by
     * timing out rather than by an assertion, which is the honest shape for "the signal never arrived".
     */
    @Test
    void cancellingOnOneNodeStopsTheRunAnotherNodeIsHolding() throws Exception {
        wireTwoNodes(new InMemoryScheduledTaskInterruptBus());
        final ScheduledTask task = registerBlockingTask();
        final CompletableFuture<Void> run = startRunOnHolder(task);

        entryPoint.cancel(task.getId(), alice);
        run.get(TEST_PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(holderExecutor.isRunning(task.getId())).isFalse();
        assertThat(taskRepo.findById(task.getId())).isEmpty();
    }

    /**
     * {@code false} from {@link ScheduledTaskManager#interrupt} no longer means "nothing was stopped".
     *
     * <p>
     * It reports only what happened on the calling node, because a fan-out has no answer to bring back. Asserting the
     * two halves together is the point: the return value says nothing was running here, and the run elsewhere stops
     * anyway. A caller that reads that {@code false} as "the task was idle" is reading it wrong, and this is the test
     * that says so.
     */
    @Test
    void interruptReportsNothingLocallyWhileStillStoppingTheRunElsewhere() throws Exception {
        wireTwoNodes(new InMemoryScheduledTaskInterruptBus());
        final ScheduledTask task = registerBlockingTask();
        final CompletableFuture<Void> run = startRunOnHolder(task);

        assertThat(entryPoint.interrupt(task.getId(), alice)).isFalse();
        run.get(TEST_PATIENCE.toSeconds(), TimeUnit.SECONDS);

        // Interrupt is the narrower operation, so the task stays and the stopped run is recorded rather than lost.
        assertThat(taskRepo.findById(task.getId())).isPresent();
        assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 10)).singleElement()
                .satisfies(h -> assertThat(h.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.CANCELLED));
    }

    /**
     * The baseline the bus is measured against: with {@link ScheduledTaskInterruptBus#LOCAL_ONLY} the request reaches
     * nobody, and the far node keeps working on behalf of a task that has just been deleted.
     *
     * <p>
     * Asserted without waiting, and that is deliberate. {@code cancel} is synchronous and its publish is a no-op here,
     * so the moment it returns the far step is still parked on a latch nothing has touched — there is no interval in
     * which it could have stopped for some other reason, and therefore nothing to sleep for.
     */
    @Test
    void withoutABusTheRunOnTheOtherNodeIsLeftAlone() throws Exception {
        wireTwoNodes(ScheduledTaskInterruptBus.LOCAL_ONLY);
        final ScheduledTask task = registerBlockingTask();
        startRunOnHolder(task);

        entryPoint.cancel(task.getId(), alice);

        assertThat(holderExecutor.isRunning(task.getId())).isTrue();
    }

    /**
     * Builds both nodes over one bus and one set of repositories.
     *
     * <p>
     * Both are full engines rather than one engine and one bare manager, so the publisher hears its own broadcast the
     * way a real node does. That echo is not an artefact to be avoided — delivery is at-least-once and a broker may
     * well return a message to its sender — so having it in every test is what keeps the handler's idempotence honest.
     */
    private void wireTwoNodes(ScheduledTaskInterruptBus bus) {
        final Agent agent = DefaultAgent.builder().name("cross-node-interrupt").systemPrompt("test").build();
        runtimeId = AgentRuntimeId.from(agent);

        final DefaultAgentRuntimeRegistry holderRegistry = new DefaultAgentRuntimeRegistry();
        holderRegistry.register(new StubAgentRuntime(runtimeId, agent, List.of(new BlockingTool())));
        final SimpleScheduledTaskEventPublisher holderEvents = new SimpleScheduledTaskEventPublisher();
        holderExecutor = new RoutineExecutor(holderRegistry, holderEvents);
        holder = ScheduledTaskManager.builder().taskRepository(taskRepo).historyRepository(historyRepo)
                .routineExecutor(holderExecutor).taskScheduler(mock(TaskScheduler.class)).eventPublisher(holderEvents)
                .quotaManager(quota).interruptBus(bus).build();
        holderEngine = new SchedulingEngine(holder, holderExecutor, mock(TaskScheduler.class), holderEvents, bus);

        // No runtime registered here: this node could not run the task even if it were asked to, which is what makes
        // "stopped it anyway" attributable to the bus and nothing else.
        final SimpleScheduledTaskEventPublisher entryEvents = new SimpleScheduledTaskEventPublisher();
        entryExecutor = new RoutineExecutor(new DefaultAgentRuntimeRegistry(), entryEvents);
        entryPoint = ScheduledTaskManager.builder().taskRepository(taskRepo).historyRepository(historyRepo)
                .routineExecutor(entryExecutor).taskScheduler(mock(TaskScheduler.class)).eventPublisher(entryEvents)
                .quotaManager(quota).interruptBus(bus).build();
        entryEngine = new SchedulingEngine(entryPoint, entryExecutor, mock(TaskScheduler.class), entryEvents, bus);
    }

    private ScheduledTask registerBlockingTask() {
        final ScheduledTask task = ScheduledTask.builder().id(ScheduledTaskId.generate()).name("blocking-task")
                .cronExpression("* * * * *").owner(alice).boundRuntimeId(runtimeId)
                .routine(List.of(RoutineStep.builder().tool(BlockingTool.TOOL_NAME).toolParams("{}").maxRetries(0)
                        .timeout(Duration.ofSeconds(30)).build()))
                .enabled(true).build();
        holder.register(task);
        return task;
    }

    /** Fires the task on the holder and returns once its step is genuinely blocked. */
    private CompletableFuture<Void> startRunOnHolder(ScheduledTask task) throws InterruptedException {
        final CompletableFuture<Void> run = CompletableFuture.runAsync(() -> holder.executeTask(task.getId()));
        assertThat(stepEntered.await(TEST_PATIENCE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        return run;
    }

    /** A step that parks until it is terminated, declaring the behaviour that lets the coordinator terminate it. */
    private final class BlockingTool extends AbstractTool {

        static final String TOOL_NAME = "blocker";

        private BlockingTool() {
            super(TOOL_NAME, "blocks until terminated",
                    Map.of("type", "object", "additionalProperties", false, "properties", Map.of()));
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.THREAD_INTERRUPT;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            stepEntered.countDown();
            try {
                neverCounted.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("terminated");
            }
            return ToolResult.success("ok");
        }
    }

    /** Minimal {@link AgentRuntime} stub returning a fixed tool list. */
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
