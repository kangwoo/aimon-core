package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.behavior.SubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.InMemoryBackgroundTaskStore;
import at.aimon.core.subagent.task.InMemoryTaskStopSignal;

/**
 * Cross-node stop wiring (design §5.3.2 ②, §7): a stop issued on a node that does not own the running task is broadcast
 * over the shared {@link at.aimon.core.subagent.task.TaskStopSignal} and honoured on the owning node.
 *
 * <p>
 * Two managers ({@code nodeA}, {@code nodeB}) model two instances: they share one
 * {@link InMemoryBackgroundTaskStore metadata store} (so both observe the same task) and one
 * {@link InMemoryTaskStopSignal loopback signal} (the in-process stand-in for a Redis pub/sub bus). The task runs on
 * {@code nodeB}; the stop is issued on {@code nodeA}.
 */
@DisplayName("DefaultSubagentExecutionManager — cross-node stop propagation")
class DefaultSubagentExecutionManagerCrossNodeStopTest {

    private static final String SUBAGENT = "explore";

    private final SubagentExecutor reactExecutor = mock(SubagentExecutor.class);

    private InMemoryBackgroundTaskStore store;
    private InMemoryTaskStopSignal stopSignal;
    private ExecutorService poolA;
    private ExecutorService poolB;
    private DefaultSubagentExecutionManager nodeA;
    private DefaultSubagentExecutionManager nodeB;

    @BeforeEach
    void setUp() {
        store = new InMemoryBackgroundTaskStore();
        stopSignal = new InMemoryTaskStopSignal();
        poolA = Executors.newSingleThreadExecutor();
        poolB = Executors.newSingleThreadExecutor();
        nodeA = newNode(poolA);
        nodeB = newNode(poolB);
    }

    @AfterEach
    void tearDown() {
        if (nodeA != null) {
            nodeA.close();
        }
        if (nodeB != null) {
            nodeB.close();
        }
        if (poolA != null) {
            poolA.shutdownNow();
        }
        if (poolB != null) {
            poolB.shutdownNow();
        }
    }

    private DefaultSubagentExecutionManager newNode(ExecutorService pool) {
        return new DefaultSubagentExecutionManager(reactExecutor, null, SubagentBehaviorRegistry.empty(), null,
                SubagentBackgroundExecutionOptions.builder().executorService(pool).taskStore(store)
                        .taskStopSignal(stopSignal).build());
    }

    @Test
    @DisplayName("stop() on a non-owning node broadcasts to the owning node, which trips the task to KILLED")
    void stopFromNonOwningNodeReachesOwner() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        when(reactExecutor.execute(any(), any())).thenAnswer(inv -> {
            SubagentExecutionContext ctx = inv.getArgument(0);
            started.countDown();
            while (!ctx.getParentCancellationSignal().isCancelled() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return reactResult("unwound");
        });

        // The task runs on node B: node B owns the local execution handle.
        CompletableFuture<SubagentExecutionResult> future = nodeB
                .executeInBackground(envBuilder(registryWithExplore()).build(), "t-x", SUBAGENT, "go", "");
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        // Node A must observe RUNNING in the shared store before it decides to broadcast rather than reject.
        awaitState("t-x", BackgroundTaskState.RUNNING);

        // Node A has no local handle for t-x; it broadcasts the stop, which node B honours.
        assertThat(nodeA.stop("t-x")).isTrue();
        future.join();

        assertThat(awaitTerminal(nodeB, "t-x")).isEqualTo(BackgroundTaskState.KILLED);
    }

    @Test
    @DisplayName("stop() of a task unknown to the whole cluster returns false (nothing to broadcast)")
    void stopOfClusterUnknownReturnsFalse() {
        assertThat(nodeA.stop("ghost")).isFalse();
    }

    @Test
    @DisplayName("stop() of an already-terminal task returns false even from a non-owning node")
    void stopOfTerminalTaskReturnsFalse() {
        store.put(BackgroundTask.builder().taskId("done").subagentName(SUBAGENT).description("d")
                .state(BackgroundTaskState.COMPLETED).startTime(Instant.now()).endTime(Instant.now()).build());

        assertThat(nodeA.stop("done")).isFalse();
    }

    private void awaitState(String taskId, BackgroundTaskState expected) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<BackgroundTask> snapshot = store.find(taskId);
            if (snapshot.isPresent() && snapshot.get().getState() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("task " + taskId + " did not reach " + expected + " in time");
    }

    private static BackgroundTaskState awaitTerminal(DefaultSubagentExecutionManager manager, String taskId)
            throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            Optional<BackgroundTask> snapshot = manager.status(taskId);
            if (snapshot.isPresent() && snapshot.get().getState().isTerminal()) {
                return snapshot.get().getState();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("task " + taskId + " did not reach a terminal state in time");
    }

    private static InMemorySubagentRegistry registryWithExplore() {
        InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name(SUBAGENT).systemPrompt("(data)").build());
        return registry;
    }

    private static SubagentExecutionEnvironment.Builder envBuilder(SubagentRegistry registry) {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(registry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build());
    }

    private static SubagentExecutionResult reactResult(String answer) {
        Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }
}
