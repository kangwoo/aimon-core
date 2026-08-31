package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.SubagentTaskCompleted;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.base.Principal;
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
import at.aimon.core.subagent.task.BackgroundTaskStore;
import at.aimon.core.subagent.task.InMemoryBackgroundTaskStore;
import at.aimon.core.subagent.task.InMemoryTaskResultStore;
import at.aimon.core.subagent.task.TaskQuery;
import at.aimon.core.subagent.task.TaskResult;
import at.aimon.core.subagent.task.TaskResultStore;

@DisplayName("DefaultSubagentExecutionManager — background lifecycle, stop, list/status")
class DefaultSubagentExecutionManagerBackgroundTest {

    private static final String SUBAGENT = "explore";

    private final SubagentExecutor reactExecutor = mock(SubagentExecutor.class);
    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private DefaultSubagentExecutionManager newManager(ExecutorService executorService) {
        return newManager(executorService, new InMemoryBackgroundTaskStore());
    }

    private DefaultSubagentExecutionManager newManager(ExecutorService executorService, BackgroundTaskStore taskStore) {
        this.pool = executorService;
        return new DefaultSubagentExecutionManager(reactExecutor, executorService, null,
                SubagentBehaviorRegistry.empty(), null, taskStore);
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

    /** Polls the store until the task reaches a terminal state, so we do not race the whenComplete finalizer. */
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

    @Test
    @DisplayName("a successful background task settles to COMPLETED with an endTime")
    void backgroundCompletes() throws InterruptedException {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("done"));
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());

        SubagentExecutionResult result = manager.executeInBackground(envBuilder(registryWithExplore()).build(), "t1",
                SUBAGENT, "go", "desc").join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(awaitTerminal(manager, "t1")).isEqualTo(BackgroundTaskState.COMPLETED);
        assertThat(manager.status("t1").orElseThrow().getEndTime()).isPresent();
    }

    @Test
    @DisplayName("a failing subagent result settles to FAILED")
    void backgroundFails() throws InterruptedException {
        when(reactExecutor.execute(any(), any()))
                .thenReturn(SubagentExecutionResult.emptyFailure("boom", Instant.now()));
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());

        manager.executeInBackground(envBuilder(registryWithExplore()).build(), "t1", SUBAGENT, "go", "").join();

        assertThat(awaitTerminal(manager, "t1")).isEqualTo(BackgroundTaskState.FAILED);
    }

    @Test
    @DisplayName("the durable snapshot records owner, agentRuntimeId, description and start time")
    void snapshotCarriesMetadata() throws InterruptedException {
        Principal alice = Principal.user("alice");
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("done"));
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());

        manager.executeInBackground(envBuilder(registryWithExplore()).principal(alice).build(), "t1", SUBAGENT, "go",
                "my task").join();
        awaitTerminal(manager, "t1");

        BackgroundTask snapshot = manager.status("t1").orElseThrow();
        assertThat(snapshot.getOwner()).contains(alice);
        assertThat(snapshot.getAgentRuntimeId()).contains(AgentRuntimeId.of("agent:test"));
        assertThat(snapshot.getDescription()).isEqualTo("my task");
        assertThat(snapshot.getSubagentName()).isEqualTo(SUBAGENT);
    }

    @Test
    @DisplayName("a queued task (worker busy) is observable as PENDING before it runs")
    void queuedTaskIsPending() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch firstRunning = new CountDownLatch(1);
        when(reactExecutor.execute(any(), any())).thenAnswer(inv -> {
            firstRunning.countDown();
            gate.await();
            return reactResult("done");
        });
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());

        // Occupy the single worker.
        manager.executeInBackground(envBuilder(registryWithExplore()).build(), "busy", SUBAGENT, "go", "");
        assertThat(firstRunning.await(2, TimeUnit.SECONDS)).isTrue();

        // Second task cannot start; it sits PENDING.
        manager.executeInBackground(envBuilder(registryWithExplore()).build(), "queued", SUBAGENT, "go", "");
        assertThat(manager.status("queued").orElseThrow().getState()).isEqualTo(BackgroundTaskState.PENDING);
        assertThat(manager.status("busy").orElseThrow().getState()).isEqualTo(BackgroundTaskState.RUNNING);

        gate.countDown();
        awaitTerminal(manager, "busy");
        awaitTerminal(manager, "queued");
    }

    @Test
    @DisplayName("stop() on a running task trips the per-task signal and settles it to KILLED")
    void stopKillsRunningTask() throws InterruptedException {
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
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());

        CompletableFuture<SubagentExecutionResult> future = manager
                .executeInBackground(envBuilder(registryWithExplore()).build(), "t-kill", SUBAGENT, "go", "");
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(manager.stop("t-kill")).isTrue();
        future.join();

        assertThat(awaitTerminal(manager, "t-kill")).isEqualTo(BackgroundTaskState.KILLED);
    }

    @Test
    @DisplayName("stop() of an unknown / already-evicted task returns false")
    void stopUnknownReturnsFalse() {
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());
        assertThat(manager.stop("ghost")).isFalse();
    }

    @Test
    @DisplayName("list(byState) and status() reflect the durable store")
    void listAndStatusReflectStore() throws InterruptedException {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("done"));
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());

        manager.executeInBackground(envBuilder(registryWithExplore()).build(), "t1", SUBAGENT, "go", "").join();
        awaitTerminal(manager, "t1");

        List<BackgroundTask> completed = manager.list(TaskQuery.byState(BackgroundTaskState.COMPLETED));
        assertThat(completed).extracting(BackgroundTask::getTaskId).contains("t1");
        assertThat(manager.list(TaskQuery.byState(BackgroundTaskState.RUNNING))).isEmpty();
        assertThat(manager.status("t1")).isPresent();
        assertThat(manager.status("nope")).isEmpty();
    }

    @Test
    @DisplayName("a saturated bounded pool rejects the overflow task as FAILED")
    void boundedPoolRejectsOverflow() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        when(reactExecutor.execute(any(), any())).thenAnswer(inv -> {
            running.countDown();
            gate.await();
            return reactResult("done");
        });
        DefaultSubagentExecutionManager manager = newManager(
                DefaultSubagentExecutionManager.newBackgroundExecutor(SubagentBackgroundConfig.of(1, 1)));

        // task1 occupies the single worker...
        manager.executeInBackground(envBuilder(registryWithExplore()).build(), "t1", SUBAGENT, "go", "");
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        // task2 fills the queue (capacity 1)...
        manager.executeInBackground(envBuilder(registryWithExplore()).build(), "t2", SUBAGENT, "go", "");
        // task3 has nowhere to go: it is rejected and settled as FAILED.
        CompletableFuture<SubagentExecutionResult> third = manager
                .executeInBackground(envBuilder(registryWithExplore()).build(), "t3", SUBAGENT, "go", "");

        SubagentExecutionResult result = third.join();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("saturated");
        assertThat(manager.status("t3").orElseThrow().getState()).isEqualTo(BackgroundTaskState.FAILED);

        gate.countDown();
        awaitTerminal(manager, "t1");
        awaitTerminal(manager, "t2");
    }

    /**
     * Polls until at least {@code expected} events/inputs are visible, so we do not race the whenComplete finalizer.
     */
    private static void awaitAtLeast(java.util.function.IntSupplier size, int expected) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (size.getAsInt() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("expected at least " + expected + " item(s), saw " + size.getAsInt());
    }

    @Test
    @DisplayName("a completed background task pushes a NEXT-priority queue notification AND emits a stream event")
    void backgroundCompletionNotifiesParent() throws InterruptedException {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("all done here"));
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());
        DefaultMessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        List<AgentExecutionEvent> events = new CopyOnWriteArrayList<>();

        manager.executeInBackground(
                envBuilder(registryWithExplore()).messageQueueManager(queue).parentEventSink(events::add).build(),
                "t1", SUBAGENT, "go", "").join();
        awaitTerminal(manager, "t1");
        awaitAtLeast(() -> queue.snapshot().size(), 1);
        awaitAtLeast(events::size, 1);

        // Exactly one notification per settle: no duplicate enqueue from the finalize + pool-rejection paths.
        assertThat(queue.snapshot()).hasSize(1);
        QueuedInput queued = queue.snapshot().get(0);
        assertThat(queued.getPriority()).isEqualTo(QueuedInputPriority.NEXT);
        assertThat(queued.getAgentRuntimeId()).isEqualTo(AgentRuntimeId.of("agent:test"));
        assertThat(queued.getSourceAgentId()).contains(SUBAGENT);
        assertThat(queued.getInputText()).doesNotContain("<").contains("completed").contains("all done here")
                .contains("AgentOutput").contains("taskId='t1'");
        assertThat(queued.getMetadata()).containsEntry("kind", "subagent-task-completed").containsEntry("taskId", "t1")
                .containsEntry("outcome", "COMPLETED");

        assertThat(events).hasSize(1).first().isInstanceOf(SubagentTaskCompleted.class);
        SubagentTaskCompleted event = (SubagentTaskCompleted) events.get(0);
        assertThat(event.getTaskId()).isEqualTo("t1");
        assertThat(event.getSubagentName()).isEqualTo(SUBAGENT);
        assertThat(event.getOutcome()).isEqualTo(SubagentTaskCompleted.Outcome.COMPLETED);
        assertThat(event.getAgentRuntimeId()).isEqualTo(AgentRuntimeId.of("agent:test"));
        assertThat(event.getDetail()).contains("all done here");
    }

    @Test
    @DisplayName("a failed background task notifies the parent with a FAILED outcome and the error detail")
    void backgroundFailureNotifiesParent() throws InterruptedException {
        when(reactExecutor.execute(any(), any()))
                .thenReturn(SubagentExecutionResult.emptyFailure("kaboom", Instant.now()));
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());
        DefaultMessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        List<AgentExecutionEvent> events = new CopyOnWriteArrayList<>();

        manager.executeInBackground(
                envBuilder(registryWithExplore()).messageQueueManager(queue).parentEventSink(events::add).build(),
                "t1", SUBAGENT, "go", "").join();
        awaitTerminal(manager, "t1");
        awaitAtLeast(() -> queue.snapshot().size(), 1);
        awaitAtLeast(events::size, 1);

        // Exactly one notification per settle over each channel: no duplicate enqueue / emit.
        assertThat(queue.snapshot()).hasSize(1);
        QueuedInput queued = queue.snapshot().get(0);
        assertThat(queued.getMetadata()).containsEntry("outcome", "FAILED");
        assertThat(queued.getInputText()).contains("failed").contains("kaboom");

        assertThat(events).hasSize(1);
        SubagentTaskCompleted event = (SubagentTaskCompleted) events.get(0);
        assertThat(event.getOutcome()).isEqualTo(SubagentTaskCompleted.Outcome.FAILED);
        assertThat(event.isSuccess()).isFalse();
        assertThat(event.getDetail()).contains("kaboom");
    }

    @Test
    @DisplayName("a stopped (KILLED) background task notifies the parent with a KILLED outcome")
    void backgroundKillNotifiesParent() throws InterruptedException {
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
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());
        DefaultMessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        List<AgentExecutionEvent> events = new CopyOnWriteArrayList<>();

        CompletableFuture<SubagentExecutionResult> future = manager.executeInBackground(
                envBuilder(registryWithExplore()).messageQueueManager(queue).parentEventSink(events::add).build(),
                "t-kill", SUBAGENT, "go", "");
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(manager.stop("t-kill")).isTrue();
        future.join();
        assertThat(awaitTerminal(manager, "t-kill")).isEqualTo(BackgroundTaskState.KILLED);
        awaitAtLeast(() -> queue.snapshot().size(), 1);
        awaitAtLeast(events::size, 1);

        // A stop settles to KILLED and still fires exactly one notification over each channel.
        assertThat(queue.snapshot()).hasSize(1);
        QueuedInput queued = queue.snapshot().get(0);
        assertThat(queued.getPriority()).isEqualTo(QueuedInputPriority.NEXT);
        assertThat(queued.getMetadata()).containsEntry("kind", "subagent-task-completed")
                .containsEntry("taskId", "t-kill").containsEntry("outcome", "KILLED");
        assertThat(queued.getInputText()).contains("killed").contains("taskId='t-kill'");

        assertThat(events).hasSize(1).first().isInstanceOf(SubagentTaskCompleted.class);
        SubagentTaskCompleted event = (SubagentTaskCompleted) events.get(0);
        assertThat(event.getOutcome()).isEqualTo(SubagentTaskCompleted.Outcome.KILLED);
        assertThat(event.getTaskId()).isEqualTo("t-kill");
        assertThat(event.getSubagentName()).isEqualTo(SUBAGENT);
        // KILLED carries no detail: the subagent produced no error and its success summary is not surfaced on a kill.
        assertThat(event.getDetail()).isEmpty();
    }

    @Test
    @DisplayName("background completion without a queue/sink wired does not fail the task")
    void backgroundCompletionWithoutNotificationChannelsIsHarmless() throws InterruptedException {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("done"));
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());

        manager.executeInBackground(envBuilder(registryWithExplore()).build(), "t1", SUBAGENT, "go", "").join();

        assertThat(awaitTerminal(manager, "t1")).isEqualTo(BackgroundTaskState.COMPLETED);
    }

    /**
     * Wraps a task store to record, at the instant a task is published as terminal, whether its result was already
     * readable.
     *
     * <p>
     * This is the ordering the store-backed {@code AgentOutput} poll rests on: the reader checks the state first and
     * loads the result second, so a terminal state must never become visible ahead of what produced it. Recording
     * happens <em>before</em> delegating, which is the last moment at which no other thread can yet observe the
     * terminal state.
     */
    private static final class TerminalOrderingProbe implements BackgroundTaskStore {

        private final BackgroundTaskStore delegate;
        private final TaskResultStore resultStore;
        private final Map<String, Boolean> resultVisibleAtTerminal = new ConcurrentHashMap<>();

        TerminalOrderingProbe(BackgroundTaskStore delegate, TaskResultStore resultStore) {
            this.delegate = Objects.requireNonNull(delegate);
            this.resultStore = Objects.requireNonNull(resultStore);
        }

        /** @return whether the result was already loadable when the task was published as terminal */
        boolean sawResultAtTerminal(String taskId) {
            return Boolean.TRUE.equals(resultVisibleAtTerminal.get(taskId));
        }

        boolean observedTerminal(String taskId) {
            return resultVisibleAtTerminal.containsKey(taskId);
        }

        @Override
        public Optional<BackgroundTask> transition(String taskId, BackgroundTaskState to) {
            if (to.isTerminal()) {
                resultVisibleAtTerminal.put(taskId, resultStore.load(taskId).isPresent());
            }
            return delegate.transition(taskId, to);
        }

        @Override
        public void put(BackgroundTask task) {
            delegate.put(task);
        }

        @Override
        public Optional<BackgroundTask> find(String taskId) {
            return delegate.find(taskId);
        }

        @Override
        public List<BackgroundTask> list(TaskQuery query) {
            return delegate.list(query);
        }

        @Override
        public Optional<BackgroundTask> heartbeat(String taskId, Instant at) {
            return delegate.heartbeat(taskId, at);
        }

        @Override
        public void remove(String taskId) {
            delegate.remove(taskId);
        }
    }

    @Test
    @DisplayName("a completed task's result is already readable when its terminal state is published")
    void resultIsSavedBeforeTheTerminalTransition() throws InterruptedException {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("the answer"));
        TaskResultStore resultStore = new InMemoryTaskResultStore();
        TerminalOrderingProbe probe = new TerminalOrderingProbe(new InMemoryBackgroundTaskStore(), resultStore);
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor(), probe);

        manager.executeInBackground(envBuilder(registryWithExplore()).taskResultStore(resultStore).build(), "t1",
                SUBAGENT, "go", "").join();
        awaitTerminal(manager, "t1");

        assertThat(probe.observedTerminal("t1")).isTrue();
        assertThat(probe.sawResultAtTerminal("t1")).isTrue();
        assertThat(resultStore.load("t1")).get()
                .satisfies(stored -> assertThat(stored.getSummary()).isEqualTo("the answer"));
    }

    @Test
    @DisplayName("a failed task's result is already readable when FAILED is published")
    void failureResultIsSavedBeforeTheFailedTransition() throws InterruptedException {
        when(reactExecutor.execute(any(), any()))
                .thenReturn(SubagentExecutionResult.emptyFailure("kaboom", Instant.now()));
        TaskResultStore resultStore = new InMemoryTaskResultStore();
        TerminalOrderingProbe probe = new TerminalOrderingProbe(new InMemoryBackgroundTaskStore(), resultStore);
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor(), probe);

        manager.executeInBackground(envBuilder(registryWithExplore()).taskResultStore(resultStore).build(), "t1",
                SUBAGENT, "go", "").join();
        awaitTerminal(manager, "t1");

        assertThat(probe.sawResultAtTerminal("t1")).isTrue();
        TaskResult stored = resultStore.load("t1").orElseThrow();
        assertThat(stored.isSuccess()).isFalse();
        assertThat(stored.getErrorMessage()).contains("kaboom");
    }

    @Test
    @DisplayName("a task rejected by a saturated pool saves why before it is published as FAILED")
    void rejectedTaskSavesItsFailureBeforeTheFailedTransition() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        when(reactExecutor.execute(any(), any())).thenAnswer(inv -> {
            running.countDown();
            gate.await();
            return reactResult("done");
        });
        TaskResultStore resultStore = new InMemoryTaskResultStore();
        TerminalOrderingProbe probe = new TerminalOrderingProbe(new InMemoryBackgroundTaskStore(), resultStore);
        DefaultSubagentExecutionManager manager = newManager(
                DefaultSubagentExecutionManager.newBackgroundExecutor(SubagentBackgroundConfig.of(1, 1)), probe);
        SubagentExecutionEnvironment env = envBuilder(registryWithExplore()).taskResultStore(resultStore).build();

        manager.executeInBackground(env, "t1", SUBAGENT, "go", "");
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        manager.executeInBackground(env, "t2", SUBAGENT, "go", "");
        manager.executeInBackground(env, "t3", SUBAGENT, "go", "").join();

        // The rejection path never reaches the finalizer, so it has to honour the ordering on its own.
        assertThat(probe.sawResultAtTerminal("t3")).isTrue();
        assertThat(resultStore.load("t3")).get()
                .satisfies(stored -> assertThat(stored.getSummary()).contains("saturated"));

        gate.countDown();
        awaitTerminal(manager, "t1");
        awaitTerminal(manager, "t2");
    }

    @Test
    @DisplayName("a task with no result store wired still settles — persistence is optional")
    void terminalTransitionIsUnaffectedWhenNoResultStoreIsWired() throws InterruptedException {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("done"));
        TerminalOrderingProbe probe = new TerminalOrderingProbe(new InMemoryBackgroundTaskStore(),
                new InMemoryTaskResultStore());
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor(), probe);

        manager.executeInBackground(envBuilder(registryWithExplore()).build(), "t1", SUBAGENT, "go", "").join();

        assertThat(awaitTerminal(manager, "t1")).isEqualTo(BackgroundTaskState.COMPLETED);
        // No store on the environment: nothing was saved, and the terminal transition happened all the same.
        assertThat(probe.sawResultAtTerminal("t1")).isFalse();
    }

    @Test
    @DisplayName("the Task tool's model override is forwarded into the subagent runtime")
    void modelOverrideForwarded() {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("done"));
        DefaultSubagentExecutionManager manager = newManager(Executors.newSingleThreadExecutor());

        manager.execute(envBuilder(registryWithExplore()).modelOverride("haiku").build(), "t1", SUBAGENT, "go", "");

        org.mockito.ArgumentCaptor<SubagentExecutionContext> captor = org.mockito.ArgumentCaptor
                .forClass(SubagentExecutionContext.class);
        org.mockito.Mockito.verify(reactExecutor).execute(captor.capture(), any());
        assertThat(captor.getValue().getModelOverride()).contains("haiku");
    }
}
