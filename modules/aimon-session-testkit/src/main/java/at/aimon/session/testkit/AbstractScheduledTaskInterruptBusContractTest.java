package at.aimon.session.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskExecutionHistory;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;
import at.aimon.core.scheduling.SchedulingEngine;
import at.aimon.core.scheduling.SchedulingEngineBuilder;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;

/**
 * What every {@link ScheduledTaskInterruptBus} that claims to cross a node boundary owes the two nodes on either side
 * of it.
 *
 * <p>
 * The contract is the one the SPI's javadoc states — a stop request published on one node reaches the listeners of
 * every other, at least once, and a listener that throws does not cost the rest their delivery. It lives here rather
 * than beside one implementation because the reference implementation ({@code InMemoryScheduledTaskInterruptBus})
 * and a broker-backed one have to answer it identically: the first is what {@code aimon-core} tests its scheduling
 * against, and a backend that drifted from it would pass its own suite while failing the engine.
 *
 * <p>
 * A backend joins by subclassing, handing out one bus per node from {@link #busFor(String)}, and tagging itself
 * {@code @Tag("docker")} if it needs a daemon. The in-memory subclass returns the same instance for both names, which
 * is exactly how that bus is meant to be shared.
 *
 * <h2>What is deliberately not asserted</h2>
 *
 * <p>
 * Whether the publishing node hears its own request. The SPI allows the echo and does not require it — the in-memory
 * bus echoes, a backend may filter by origin — so every assertion here is made on the <em>other</em> node, and the
 * engine-level scenario runs with whatever the backend does.
 */
public abstract class AbstractScheduledTaskInterruptBusContractTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    /** Long enough for a delivery that was going to happen to have happened; used only to assert absence. */
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(2);

    private final Principal alice = Principal.user("alice");
    private final CountDownLatch stepEntered = new CountDownLatch(1);
    private final CountDownLatch neverCounted = new CountDownLatch(1);
    private final List<AutoCloseable> toClose = new ArrayList<>();

    /**
     * @param nodeName
     *            a stable name for one of the two nodes ({@code "node-A"} or {@code "node-B"})
     * @return that node's handle on the bus — the same underlying channel for both names, a separate connection where
     *         the backend has connections. Called once per node per test; the subclass owns the bus's lifecycle
     */
    protected abstract ScheduledTaskInterruptBus busFor(String nodeName);

    /**
     * Blocks until subscriptions made so far are live on the backend.
     *
     * <p>
     * A backend whose {@code subscribe} returns before its receive loop is attached — a change stream opened on a
     * background thread, say — overrides this. The default is a no-op, which is right for a synchronous bus.
     *
     * @throws InterruptedException
     *             if interrupted while waiting
     */
    protected void awaitSubscriptionsLive() throws InterruptedException {
        // Synchronous buses are live the moment subscribe returns.
    }

    @AfterEach
    void releaseEverything() throws Exception {
        neverCounted.countDown();
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
    }

    @Test
    @DisplayName("a request published on one node reaches a listener on the other, id and reason intact")
    void requestCrossesToTheOtherNode() throws Exception {
        final ScheduledTaskInterruptBus nodeA = busFor("node-A");
        final ScheduledTaskInterruptBus nodeB = busFor("node-B");
        final BlockingQueue<Received> heardOnB = listenOn(nodeB);
        awaitSubscriptionsLive();

        final ScheduledTaskId taskId = ScheduledTaskId.generate();
        nodeA.publish(taskId, InterruptReason.TASK_CANCELLED);

        assertThat(heardOnB.poll(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new Received(taskId, InterruptReason.TASK_CANCELLED));
    }

    @Test
    @DisplayName("every reason survives the crossing")
    void everyReasonSurvivesTheCrossing() throws Exception {
        final ScheduledTaskInterruptBus nodeA = busFor("node-A");
        final ScheduledTaskInterruptBus nodeB = busFor("node-B");
        final BlockingQueue<Received> heardOnB = listenOn(nodeB);
        awaitSubscriptionsLive();

        final ScheduledTaskId taskId = ScheduledTaskId.generate();
        for (InterruptReason reason : InterruptReason.values()) {
            nodeA.publish(taskId, reason);
        }

        final List<InterruptReason> heard = new ArrayList<>();
        while (heard.size() < InterruptReason.values().length) {
            final Received next = heardOnB.poll(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(next).as("delivery %d of %d", heard.size() + 1, InterruptReason.values().length).isNotNull();
            heard.add(next.reason);
        }
        // Order is not part of the contract; at-least-once is, so compare as a set of what must have arrived.
        assertThat(heard).contains(InterruptReason.values());
    }

    @Test
    @DisplayName("a listener that throws does not cost the next listener its delivery")
    void aThrowingListenerDoesNotSwallowTheFanOut() throws Exception {
        final ScheduledTaskInterruptBus nodeA = busFor("node-A");
        final ScheduledTaskInterruptBus nodeB = busFor("node-B");
        toClose.add(nodeB.subscribe((id, reason) -> {
            throw new IllegalStateException("listener failure the bus must contain");
        }));
        final BlockingQueue<Received> heardOnB = listenOn(nodeB);
        awaitSubscriptionsLive();

        final ScheduledTaskId first = ScheduledTaskId.generate();
        final ScheduledTaskId second = ScheduledTaskId.generate();
        nodeA.publish(first, InterruptReason.TASK_CANCELLED);
        nodeA.publish(second, InterruptReason.TASK_CANCELLED);

        // The second publish is the one that shows the receive loop itself survived the first throw.
        assertThat(drainIds(heardOnB, 2)).contains(first, second);
    }

    @Test
    @DisplayName("a closed subscription hears nothing more, and its sibling still does")
    void closingOneSubscriptionLeavesTheOther() throws Exception {
        final ScheduledTaskInterruptBus nodeA = busFor("node-A");
        final ScheduledTaskInterruptBus nodeB = busFor("node-B");
        final BlockingQueue<Received> closed = new LinkedBlockingQueue<>();
        final ScheduledTaskInterruptBus.Subscription toDrop = nodeB
                .subscribe((id, reason) -> closed.add(new Received(id, reason)));
        final BlockingQueue<Received> kept = listenOn(nodeB);
        awaitSubscriptionsLive();

        toDrop.close();
        final ScheduledTaskId taskId = ScheduledTaskId.generate();
        nodeA.publish(taskId, InterruptReason.USER_SIGINT);

        assertThat(kept.poll(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new Received(taskId, InterruptReason.USER_SIGINT));
        // The sibling has already heard it, so anything the closed one was going to get has had its chance.
        assertThat(closed.poll(QUIET_PERIOD.toMillis(), TimeUnit.MILLISECONDS)).isNull();
    }

    /**
     * The scenario the bus exists for, on real engines: the cancellation is entered on a node that is running nothing,
     * and the run stops on the node that has it.
     *
     * <p>
     * The two engines share a task repository and a history repository — standing in for a shared store, which is a
     * separate seam from the one under test — and nothing else. Only the holder can resolve the task's
     * {@code boundRuntimeId}, so the entry node could not have stopped the run by any route but the bus. The blocking
     * step parks for longer than {@link #PATIENCE}, so a bus that drops the request fails here by timing out.
     */
    @Test
    @DisplayName("cancelling on one node stops the run another node is holding")
    void cancellingOnOneNodeStopsTheRunAnotherNodeIsHolding() throws Exception {
        final InMemoryScheduledTaskRepository tasks = new InMemoryScheduledTaskRepository();
        final InMemoryScheduledTaskExecutionHistoryRepository history;
        history = new InMemoryScheduledTaskExecutionHistoryRepository();
        final Agent agent = DefaultAgent.builder().name("interrupt-bus-contract").systemPrompt("test").build();
        final AgentRuntimeId runtimeId = AgentRuntimeId.from(agent);

        final DefaultAgentRuntimeRegistry holderRuntimes = new DefaultAgentRuntimeRegistry();
        holderRuntimes.register(new StubAgentRuntime(runtimeId, agent, List.of(new BlockingTool())));
        final SchedulingEngine holder = engine(tasks, history, holderRuntimes, busFor("node-A"));
        final SchedulingEngine entry = engine(tasks, history, new DefaultAgentRuntimeRegistry(), busFor("node-B"));
        // Registering needs a running scheduler. The cron below is once a year, so the only run is the one fired by
        // hand further down — a per-minute expression would let a minute boundary start a second one mid-test.
        holder.start();
        awaitSubscriptionsLive();

        final ScheduledTask task = ScheduledTask.builder().id(ScheduledTaskId.generate()).name("blocking-task")
                .cronExpression("0 0 1 1 *").owner(alice).boundRuntimeId(runtimeId)
                .routine(List.of(RoutineStep.builder().tool(BlockingTool.TOOL_NAME).toolParams("{}").maxRetries(0)
                        .timeout(Duration.ofSeconds(30)).build()))
                .enabled(true).build();
        holder.getTaskManager().register(task);
        final CompletableFuture<Void> run = CompletableFuture
                .runAsync(() -> holder.getTaskManager().executeTask(task.getId()));
        assertThat(stepEntered.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        // Interrupt rather than cancel: the task stays, so the stopped run has somewhere to be recorded.
        assertThat(entry.getTaskManager().interrupt(task.getId(), alice)).isFalse();
        run.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(history.findByTaskIdOrderByStartedAtDesc(task.getId(), 10)).singleElement()
                .satisfies(h -> assertThat(h.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.CANCELLED));
    }

    private SchedulingEngine engine(InMemoryScheduledTaskRepository tasks,
            InMemoryScheduledTaskExecutionHistoryRepository history, DefaultAgentRuntimeRegistry runtimes,
            ScheduledTaskInterruptBus bus) {
        final SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(tasks)
                .historyRepository(history).agentRuntimeRegistry(runtimes).interruptBus(bus).build();
        toClose.add(engine);
        return engine;
    }

    private BlockingQueue<Received> listenOn(ScheduledTaskInterruptBus bus) {
        final BlockingQueue<Received> heard = new LinkedBlockingQueue<>();
        toClose.add(bus.subscribe((id, reason) -> heard.add(new Received(id, reason))));
        return heard;
    }

    private static List<ScheduledTaskId> drainIds(BlockingQueue<Received> queue, int count)
            throws InterruptedException {
        final List<ScheduledTaskId> ids = new ArrayList<>();
        while (ids.size() < count) {
            final Received next = queue.poll(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(next).as("delivery %d of %d", ids.size() + 1, count).isNotNull();
            ids.add(next.taskId);
        }
        return ids;
    }

    /** One delivery as a listener saw it. */
    private static final class Received {

        private final ScheduledTaskId taskId;
        private final InterruptReason reason;

        Received(ScheduledTaskId taskId, InterruptReason reason) {
            this.taskId = taskId;
            this.reason = reason;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Received other && taskId.equals(other.taskId) && reason == other.reason;
        }

        @Override
        public int hashCode() {
            return 31 * taskId.hashCode() + reason.hashCode();
        }

        @Override
        public String toString() {
            return taskId + "/" + reason;
        }
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
