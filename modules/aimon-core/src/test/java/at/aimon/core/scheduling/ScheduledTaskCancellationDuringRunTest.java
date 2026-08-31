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
import org.junit.jupiter.api.BeforeEach;
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
 * Pins what happens when a task is cancelled while one of its runs is still going, wired end to end — real
 * {@link RoutineExecutor}, real repositories, a step that genuinely blocks.
 *
 * <p>
 * The hazard is not the interrupt but what the run does on its way out. {@link ScheduledTaskManager#executeTask} reads
 * its task at fire time and writes it back when the run ends, so a plain {@code save} recreates whatever
 * {@link ScheduledTaskManager#cancel} deleted in between. Nothing about the ordering inside {@code cancel} can prevent
 * that — the run's write always happens after — so the guarantee lives on the write side, and this is where it is
 * checked. Cancelling a running task used to leave the task behind, and stopping the run promptly is exactly what
 * puts the write inside the delete's window.
 *
 * <p>
 * A mocked {@code RoutineExecutor} cannot host this test: the race is between a real run's teardown and the deletes,
 * and a mock has no teardown.
 */
class ScheduledTaskCancellationDuringRunTest {

    private static final Duration TEST_PATIENCE = Duration.ofSeconds(10);

    private final Principal alice = Principal.user("alice");
    private final CountDownLatch stepEntered = new CountDownLatch(1);
    private final CountDownLatch neverCounted = new CountDownLatch(1);

    private AgentRuntimeId runtimeId;
    private InMemoryScheduledTaskRepository taskRepo;
    private InMemoryScheduledTaskExecutionHistoryRepository historyRepo;
    private DefaultTaskQuotaManager quota;
    private RoutineExecutor routineExecutor;
    private ScheduledTaskManager manager;

    @BeforeEach
    void setUp() {
        final Agent agent = DefaultAgent.builder().name("cancel-during-run").systemPrompt("test").build();
        final AgentRuntimeId runtimeId = AgentRuntimeId.from(agent);

        final DefaultAgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
        registry.register(new StubAgentRuntime(runtimeId, agent, List.of(new BlockingTool())));

        taskRepo = new InMemoryScheduledTaskRepository();
        historyRepo = new InMemoryScheduledTaskExecutionHistoryRepository();
        quota = new DefaultTaskQuotaManager(5);

        final SimpleScheduledTaskEventPublisher publisher = new SimpleScheduledTaskEventPublisher();
        routineExecutor = new RoutineExecutor(registry, publisher);
        manager = new ScheduledTaskManager(taskRepo, historyRepo, routineExecutor, mock(TaskScheduler.class), publisher,
                quota);
        this.runtimeId = runtimeId;
    }

    @AfterEach
    void tearDown() {
        neverCounted.countDown();
        routineExecutor.shutdown();
    }

    /**
     * The headline guarantee: after cancelling a running task, the task is gone and stays gone.
     *
     * <p>
     * The failure this replaces was a task that came back — unscheduled, so it never fired again, yet still listed and
     * still resolvable by id, with its quota unit already refunded.
     */
    @Test
    void cancellingARunningTaskDoesNotResurrectItWhenTheRunUnwinds() throws Exception {
        final ScheduledTask task = registerBlockingTask();
        final CompletableFuture<Void> run = startRun(task);

        manager.cancel(task.getId(), alice);
        run.get(TEST_PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(taskRepo.findById(task.getId())).isEmpty();
        assertThat(taskRepo.findAll()).isEmpty();
    }

    /** The other half of the same write-back: no history row survives for a task that no longer exists. */
    @Test
    void aRunThatUnwindsAfterCancellationLeavesNoHistoryBehind() throws Exception {
        final ScheduledTask task = registerBlockingTask();
        final CompletableFuture<Void> run = startRun(task);

        manager.cancel(task.getId(), alice);
        run.get(TEST_PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 10)).isEmpty();
    }

    /**
     * Quota accounting and stored tasks have to agree, and the assertion is deliberately the relation between them
     * rather than either number on its own.
     *
     * <p>
     * The refund happens in {@code cancel} either way, so asserting "usage is zero" would pass even while a
     * resurrected task sat in the repository — the two numbers disagreeing is the whole defect, and only comparing
     * them catches it. An owner in that state is silently over the cap by one for as long as the phantom lives.
     */
    @Test
    void aCancelledRunningTaskLeavesQuotaAccountingLevelWithWhatIsStored() throws Exception {
        final ScheduledTask task = registerBlockingTask();
        final CompletableFuture<Void> run = startRun(task);

        manager.cancel(task.getId(), alice);
        run.get(TEST_PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(taskRepo.findAll()).hasSize(quota.getCurrentUsage(alice));
        assertThat(quota.getCurrentUsage(alice)).isZero();
    }

    /**
     * Interrupting is the narrower operation, so the write-back must still happen: the task stays, and the run that
     * was stopped is recorded as {@code CANCELLED} rather than lost or filed as a failure.
     */
    @Test
    void interruptingARunningTaskKeepsTheTaskAndRecordsTheStoppedRun() throws Exception {
        final ScheduledTask task = registerBlockingTask();
        final CompletableFuture<Void> run = startRun(task);

        assertThat(manager.interrupt(task.getId(), alice)).isTrue();
        run.get(TEST_PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(taskRepo.findById(task.getId())).isPresent();
        assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 10)).singleElement()
                .satisfies(h -> assertThat(h.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.CANCELLED));
    }

    private ScheduledTask registerBlockingTask() {
        final ScheduledTask task = ScheduledTask.builder().id(ScheduledTaskId.generate()).name("blocking-task")
                .cronExpression("* * * * *").owner(alice).boundRuntimeId(runtimeId)
                .routine(List.of(RoutineStep.builder().tool(BlockingTool.TOOL_NAME).toolParams("{}").maxRetries(0)
                        .timeout(Duration.ofSeconds(30)).build()))
                .enabled(true).build();
        manager.register(task);
        return task;
    }

    /** Fires the task and returns once its step is genuinely blocked, so the cancel below cannot land too early. */
    private CompletableFuture<Void> startRun(ScheduledTask task) throws InterruptedException {
        final CompletableFuture<Void> run = CompletableFuture.runAsync(() -> manager.executeTask(task.getId()));
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
