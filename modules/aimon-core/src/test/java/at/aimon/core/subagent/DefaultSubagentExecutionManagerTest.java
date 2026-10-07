package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.event.SubagentStopHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.DeclarativeSubagentStartHook;
import at.aimon.core.skill.hook.declarative.DeclarativeSubagentStopHook;
import at.aimon.core.skill.hook.declarative.HostShellActionExecutor;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.subagent.task.InMemorySessionSnapshotStore;
import at.aimon.core.subagent.task.SessionSnapshotStore;

@DisplayName("DefaultSubagentExecutionManager — code-behavior dispatch fork")
class DefaultSubagentExecutionManagerTest {

    private final SubagentExecutor reactExecutor = mock(SubagentExecutor.class);
    /** How long the fake hook command "runs" when nothing stops it: a hang guard, not the proof of a stop. */
    private static final Duration HANG_GUARD = Duration.ofSeconds(30);
    private static final ShellAction AUDIT = new ShellAction("audit.sh", Duration.ofSeconds(60));

    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
    }

    @Test
    @DisplayName("a registered behavior replaces the ReAct loop; the executor is never called")
    void behaviorReplacesReActLoop() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());

        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("clock", (ctx, req, support) -> support.success("tick"));

        DefaultSubagentExecutionManager manager = newManager(behaviorRegistry);

        SubagentExecutionResult result = manager.execute(env(dataRegistry), "task-1", "clock", "what time?", "");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("tick");
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("with no behavior for the name, the unchanged ReAct executor runs (regression guard)")
    void noBehaviorRunsReActLoop() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("explore").systemPrompt("(data)").build());

        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("from-react"));

        DefaultSubagentExecutionManager manager = newManager(new InMemorySubagentBehaviorRegistry());

        SubagentExecutionResult result = manager.execute(env(dataRegistry), "task-1", "explore", "go", "");

        assertThat(result.getFinalAnswer()).isEqualTo("from-react");
        verify(reactExecutor).execute(any(), any());
    }

    @Test
    @DisplayName("default empty() behavior registry preserves the unchanged data path")
    void defaultEmptyRegistryRunsReActLoop() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("explore").systemPrompt("(data)").build());

        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("from-react"));

        // 4-arg constructor → defaults to SubagentBehaviorRegistry.empty()
        DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(reactExecutor, bgPool, null);

        SubagentExecutionResult result = manager.execute(env(dataRegistry), "task-1", "explore", "go", "");

        assertThat(result.getFinalAnswer()).isEqualTo("from-react");
        verify(reactExecutor).execute(any(), any());
    }

    @Test
    @DisplayName("a behavior name with no data entry fails fast (SubagentNotFound → failure), executor not called")
    void behaviorWithoutDataEntryFailsFast() {
        InMemorySubagentRegistry emptyData = new InMemorySubagentRegistry();

        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("ghost", (ctx, req, support) -> support.success("never"));

        DefaultSubagentExecutionManager manager = newManager(behaviorRegistry);

        SubagentExecutionResult result = manager.execute(env(emptyData), "task-1", "ghost", "go", "");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("ghost");
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("a registered behavior still fires manager-level SubagentStart/Stop hooks (around the fork)")
    void behaviorFiresSubagentHooks() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());

        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("clock", (ctx, req, support) -> support.success("tick"));

        HookExecutionManager hooks = mock(HookExecutionManager.class);
        DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(reactExecutor, bgPool, hooks,
                behaviorRegistry);

        SubagentExecutionResult result = manager.execute(env(dataRegistry), "task-1", "clock", "go", "");

        assertThat(result.getFinalAnswer()).isEqualTo("tick");
        verify(hooks).executeSubagentStart(any());
        verify(hooks).executeSubagentStop(any());
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("executeInBackground runs the code behavior and returns its result")
    void backgroundRunsCodeBehavior() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());

        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("clock", (ctx, req, support) -> support.success("bg-tick"));

        DefaultSubagentExecutionManager manager = newManager(behaviorRegistry);

        SubagentExecutionResult result = manager.executeInBackground(env(dataRegistry), "task-bg", "clock", "go", "")
                .join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("bg-tick");
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("on background completion the manager saves the non-empty snapshot keyed by taskId, tagged with "
            + "the subagent")
    void savesConversationSnapshotOnCompletion() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("explore").systemPrompt("(data)").build());

        SubagentExecutionResult reactResult = reactResultWithHistory("done");
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult);

        InMemorySessionSnapshotStore snapshotStore = new InMemorySessionSnapshotStore();
        DefaultSubagentExecutionManager manager = newManager(new InMemorySubagentBehaviorRegistry());

        manager.executeInBackground(envWithSnapshotStore(dataRegistry, snapshotStore, null), "task-9", "explore", "go",
                "").join();

        assertThat(snapshotStore.load("task-9")).get().satisfies(resumable -> {
            assertThat(resumable.getSnapshot()).isSameAs(reactResult.getSnapshot());
            assertThat(resumable.getSubagentName()).isEqualTo("explore");
        });
    }

    @Test
    @DisplayName("a foreground run persists nothing — its taskId is never surfaced, so a snapshot would be "
            + "unreachable")
    void foregroundRunDoesNotPersistSnapshot() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("explore").systemPrompt("(data)").build());

        when(reactExecutor.execute(any(), any())).thenReturn(reactResultWithHistory("done"));

        InMemorySessionSnapshotStore snapshotStore = new InMemorySessionSnapshotStore();
        DefaultSubagentExecutionManager manager = newManager(new InMemorySubagentBehaviorRegistry());

        SubagentExecutionResult result = manager.execute(envWithSnapshotStore(dataRegistry, snapshotStore, null),
                "task-fg", "explore", "go", "");

        assertThat(result.isSuccess()).isTrue();
        assertThat(snapshotStore.load("task-fg")).isEmpty();
    }

    @Test
    @DisplayName("an empty transcript (e.g. dispatch failure) is not persisted, so its id stays unresumable")
    void emptySnapshotIsNotPersisted() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("explore").systemPrompt("(data)").build());

        // reactResult(...) carries an empty-history snapshot, mirroring an emptyFailure/early-exit result.
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("done"));

        InMemorySessionSnapshotStore snapshotStore = new InMemorySessionSnapshotStore();
        DefaultSubagentExecutionManager manager = newManager(new InMemorySubagentBehaviorRegistry());

        manager.executeInBackground(envWithSnapshotStore(dataRegistry, snapshotStore, null), "task-empty", "explore",
                "go", "").join();

        assertThat(snapshotStore.load("task-empty")).isEmpty();
    }

    @Test
    @DisplayName("with no snapshot store on the env, a background run still completes — save is a no-op")
    void noSnapshotStoreMeansNoPersistence() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("explore").systemPrompt("(data)").build());

        when(reactExecutor.execute(any(), any())).thenReturn(reactResultWithHistory("done"));

        DefaultSubagentExecutionManager manager = newManager(new InMemorySubagentBehaviorRegistry());

        // env() builds no snapshot store — the background save path must no-op without an NPE.
        SubagentExecutionResult result = manager.executeInBackground(env(dataRegistry), "task-1", "explore", "go", "")
                .join();

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a previousSnapshot on the env is forwarded to the executor request")
    void forwardsPreviousConversationToExecutorRequest() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("explore").systemPrompt("(data)").build());

        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("done"));

        SessionSnapshot previous = SessionSnapshot.of(SessionId.generate());
        DefaultSubagentExecutionManager manager = newManager(new InMemorySubagentBehaviorRegistry());

        manager.execute(envWithSnapshotStore(dataRegistry, null, previous), "task-1", "explore", "go", "");

        ArgumentCaptor<SubagentExecutionRequest> reqCaptor = ArgumentCaptor.forClass(SubagentExecutionRequest.class);
        verify(reactExecutor).execute(any(), reqCaptor.capture());
        assertThat(reqCaptor.getValue().getPreviousSnapshot()).contains(previous);
    }

    @Test
    @DisplayName("the @subagent overload binds the caller's conversation as the invoker")
    void agentRequestOverloadBindsInvokingConversation() {
        final InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        when(reactExecutor.execute(any(), any())).thenReturn(successResult("tick"));

        final SessionId caller = SessionId.generate();
        newManager(new InMemorySubagentBehaviorRegistry()).execute(env(dataRegistry),
                OrcaAgentExecutionRequest.builder().userInput("@clock what time?").sessionId(caller).build(),
                new TranscriptBuffer(caller));

        // This overload is the only entry point handed a TranscriptBuffer, so it is the only place that can name the
        // invoker when the environment did not already carry it.
        final ArgumentCaptor<SubagentExecutionRequest> captor = ArgumentCaptor.forClass(SubagentExecutionRequest.class);
        verify(reactExecutor).execute(any(), captor.capture());
        assertThat(captor.getValue().getInvokingSessionId()).contains(caller);
    }

    private static SubagentExecutionResult successResult(String answer) {
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate(), "sys", List.of()),
                ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.empty())
                        .timestamps(Instant.now(), Instant.now()).build());
    }

    @Test
    @DisplayName("SubagentStart/Stop hooks carry the spawning execution's environment (EE-9)")
    void subagentHooksCarryTheSpawnersExecutionEnvironment() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("clock", (ctx, req, support) -> support.success("tick"));
        HookExecutionManager hooks = mock(HookExecutionManager.class);
        DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(reactExecutor, bgPool, hooks,
                behaviorRegistry);
        // Both hooks fire in the spawner's registry, and at subagentStart the fork's own environment does not exist
        // yet — so what they carry is the spawner's.
        ExecutionEnvironment spawner = TestExecutionEnvironments.builder().workingDirectory("/spawner").build();
        SubagentLaunchContext env = SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(dataRegistry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).defaultModel(LlmModel.builder().name("gpt-4").build())
                .executionEnvironment(spawner).build();

        manager.execute(env, "task-1", "clock", "go", "");

        ArgumentCaptor<SubagentStartContext> start = ArgumentCaptor.forClass(SubagentStartContext.class);
        ArgumentCaptor<SubagentStopContext> stop = ArgumentCaptor.forClass(SubagentStopContext.class);
        verify(hooks).executeSubagentStart(start.capture());
        verify(hooks).executeSubagentStop(stop.capture());
        assertThat(start.getValue().getExecutionEnvironment().orElseThrow()).isSameAs(spawner);
        assertThat(stop.getValue().getExecutionEnvironment().orElseThrow()).isSameAs(spawner);
    }

    @Test
    @DisplayName("SubagentStart/Stop hooks carry no environment when a runtime-level runner spawned the fork")
    void subagentHooksAreEmptyWhenTheSpawnerHasNoEnvironment() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("clock", (ctx, req, support) -> support.success("tick"));
        HookExecutionManager hooks = mock(HookExecutionManager.class);
        DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(reactExecutor, bgPool, hooks,
                behaviorRegistry);

        manager.execute(env(dataRegistry), "task-1", "clock", "go", "");

        ArgumentCaptor<SubagentStartContext> start = ArgumentCaptor.forClass(SubagentStartContext.class);
        ArgumentCaptor<SubagentStopContext> stop = ArgumentCaptor.forClass(SubagentStopContext.class);
        verify(hooks).executeSubagentStart(start.capture());
        verify(hooks).executeSubagentStop(stop.capture());
        assertThat(start.getValue().getExecutionEnvironment()).isEmpty();
        assertThat(stop.getValue().getExecutionEnvironment()).isEmpty();
    }

    @Test
    @DisplayName("a foreground fork's SubagentStart/Stop carry the spawning execution's cancellation signal (EE-80)")
    void foregroundSubagentHooksCarryTheSpawnersSignal() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("clock", (ctx, req, support) -> support.success("tick"));
        SignalRecordingHooks hooks = new SignalRecordingHooks();
        DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(reactExecutor, bgPool,
                hooks.manager, behaviorRegistry);
        try (DefaultInterruptCoordinator spawner = new DefaultInterruptCoordinator()) {
            // A foreground fork is governed by the spawner's own signal: that is what cancels it.
            manager.execute(env(dataRegistry).toBuilder().cancellationSignal(spawner.getSignal()).build(), "task-1",
                    "clock", "go", "");

            assertThat(hooks.atStart.get().orElseThrow()).isSameAs(spawner.getSignal());
            assertThat(hooks.atStop.get().orElseThrow()).isSameAs(spawner.getSignal());
        }
    }

    @Test
    @DisplayName("a background fork's SubagentStart/Stop carry the task's signal; a stopped task's Stop carries none")
    void backgroundSubagentHooksCarryTheTasksSignal() throws Exception {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        CountDownLatch running = new CountDownLatch(1);
        AtomicReference<CancellationSignal> forkParentSignal = new AtomicReference<>();
        behaviorRegistry.register("clock", (ctx, req, support) -> {
            forkParentSignal.set(ctx.getParentCancellationSignal());
            CountDownLatch stopped = new CountDownLatch(1);
            ctx.getParentCancellationSignal().onCancel(stopped::countDown);
            running.countDown();
            try {
                stopped.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return support.failure("stopped");
        });
        SignalRecordingHooks hooks = new SignalRecordingHooks();
        DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(reactExecutor, bgPool,
                hooks.manager, behaviorRegistry);
        try (DefaultInterruptCoordinator spawner = new DefaultInterruptCoordinator()) {
            var future = manager.executeInBackground(
                    env(dataRegistry).toBuilder().cancellationSignal(spawner.getSignal()).build(), "task-bg", "clock",
                    "go", "");
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            // The task's own signal, not the spawner's: it is what Task.stop trips, and it cascades from the spawner's.
            assertThat(hooks.atStart.get().orElseThrow()).isNotSameAs(spawner.getSignal())
                    .isSameAs(forkParentSignal.get());

            assertThat(manager.stop("task-bg")).isTrue();
            future.get(5, TimeUnit.SECONDS);

            assertThat(spawner.getSignal().isCancelled()).isFalse();
            // The fork ended because that signal tripped; its stop is still reported, with nothing to keep the
            // hook's command from starting.
            assertThat(hooks.atStop.get()).as("SubagentStop fired").isNotNull();
            assertThat(hooks.atStop.get()).isEmpty();
        }
    }

    @Test
    @DisplayName("a fork spawned by an already-cancelled execution still reports its start, with no signal (EE-80)")
    void subagentStartOfAnAlreadyCancelledSpawnerCarriesNoSignal() throws Exception {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("clock", (ctx, req, support) -> support.failure("cancelled"));
        try (DefaultInterruptCoordinator spawner = new DefaultInterruptCoordinator()) {
            spawner.requestInterrupt(InterruptReason.USER_SIGINT);
            SubagentLaunchContext cancelled = env(dataRegistry).toBuilder().cancellationSignal(spawner.getSignal())
                    .build();

            // Foreground: the spawner's own signal, already tripped. A report is handed nothing that would keep its
            // command from starting.
            SignalRecordingHooks foreground = new SignalRecordingHooks();
            new DefaultSubagentExecutionManager(reactExecutor, bgPool, foreground.manager, behaviorRegistry)
                    .execute(cancelled, "task-fg", "clock", "go", "");
            assertThat(foreground.atStart.get()).as("SubagentStart fired").isNotNull();
            assertThat(foreground.atStart.get()).isEmpty();

            // Background: the task's signal, which the cascade trips as soon as it is registered on a tripped one.
            SignalRecordingHooks background = new SignalRecordingHooks();
            new DefaultSubagentExecutionManager(reactExecutor, bgPool, background.manager, behaviorRegistry)
                    .executeInBackground(cancelled, "task-bg", "clock", "go", "").get(5, TimeUnit.SECONDS);
            assertThat(background.atStart.get()).as("SubagentStart fired").isNotNull();
            assertThat(background.atStart.get()).isEmpty();
        }
    }

    @Test
    @DisplayName("EE-98: interrupting the spawner leaves a background fork's running SubagentStart command running")
    void spawnerInterruptLeavesARunningBackgroundSubagentStartCommandRunning() throws Exception {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("clock", (ctx, req, support) -> support.failure("cancelled"));
        CancellationOnlyShell shell = new CancellationOnlyShell();
        DefaultHookRegistry hookRegistry = new DefaultHookRegistry();
        hookRegistry.register(HookEventType.SUBAGENT_START,
                new DeclarativeSubagentStartHook("ops", AUDIT, new HostShellActionExecutor(shell.mock)));
        DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(reactExecutor, bgPool,
                new DefaultHookExecutionManager(), behaviorRegistry);
        ExecutorService launcher = Executors.newSingleThreadExecutor();
        try (DefaultInterruptCoordinator spawner = new DefaultInterruptCoordinator()) {
            SubagentLaunchContext launchContext = env(dataRegistry).toBuilder().hookRegistry(hookRegistry)
                    .cancellationSignal(spawner.getSignal()).build();

            // SubagentStart fires on the launching thread, so the launch itself waits on the command.
            Future<CompletableFuture<SubagentExecutionResult>> launch = launcher
                    .submit(() -> manager.executeInBackground(launchContext, "task-bg", "clock", "go", ""));
            assertThat(shell.started.await(5, TimeUnit.SECONDS)).isTrue();
            // The chain stops short of the command: spawner's signal -> the task's coordinator -> the hook context.
            // Listeners run inside requestInterrupt, so a command tied to the signal would have been stopped by now.
            spawner.requestInterrupt(InterruptReason.USER_SIGINT);

            assertThat(shell.stoppedByCancellation).isFalse();
            assertThat(launch).as("the launch is still waiting for the report command").isNotDone();
            shell.mayFinish.countDown();
            launch.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);

            assertThat(shell.ranToCompletion).as("the command finished on its own").isTrue();
            assertThat(shell.stoppedByCancellation).isFalse();
        } finally {
            launcher.shutdownNow();
        }
    }

    @Test
    @DisplayName("EE-98: interrupting the spawner leaves the SubagentStop command of a task the pool rejected running")
    void spawnerInterruptLeavesTheSubagentStopCommandOfARejectedTaskRunning() throws Exception {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        behaviorRegistry.register("clock", (ctx, req, support) -> {
            running.countDown();
            try {
                gate.await(HANG_GUARD.toMillis() * 4, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return support.success("tick");
        });
        CancellationOnlyShell shell = new CancellationOnlyShell();
        DefaultHookRegistry hookRegistry = new DefaultHookRegistry();
        hookRegistry.register(HookEventType.SUBAGENT_STOP,
                new DeclarativeSubagentStopHook("ops", AUDIT, new HostShellActionExecutor(shell.mock)));
        ExecutorService boundedPool = DefaultSubagentExecutionManager
                .newBackgroundExecutor(SubagentBackgroundConfig.of(1, 1));
        DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(reactExecutor, boundedPool,
                new DefaultHookExecutionManager(), behaviorRegistry);
        ExecutorService launcher = Executors.newSingleThreadExecutor();
        try (DefaultInterruptCoordinator spawner = new DefaultInterruptCoordinator()) {
            // One task on the single worker, one in the queue: the third has nowhere to go.
            manager.executeInBackground(env(dataRegistry), "t1", "clock", "go", "");
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            manager.executeInBackground(env(dataRegistry), "t2", "clock", "go", "");
            SubagentLaunchContext launchContext = env(dataRegistry).toBuilder().hookRegistry(hookRegistry)
                    .cancellationSignal(spawner.getSignal()).build();

            // The rejected task's Stop fires on the launching thread, inside the spawning execution.
            Future<CompletableFuture<SubagentExecutionResult>> launch = launcher
                    .submit(() -> manager.executeInBackground(launchContext, "t3", "clock", "go", ""));
            assertThat(shell.started.await(5, TimeUnit.SECONDS)).isTrue();
            spawner.requestInterrupt(InterruptReason.USER_SIGINT);

            assertThat(shell.stoppedByCancellation).isFalse();
            assertThat(launch).as("the launch is still waiting for the report command").isNotDone();
            shell.mayFinish.countDown();
            SubagentExecutionResult rejected = launch.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS).get(5,
                    TimeUnit.SECONDS);

            assertThat(rejected.getErrorMessage()).contains("saturated");
            assertThat(shell.ranToCompletion).as("the command finished on its own").isTrue();
            assertThat(shell.stoppedByCancellation).isFalse();
        } finally {
            gate.countDown();
            launcher.shutdownNow();
            boundedPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("EE-98: Task.stop leaves a background fork's running SubagentStop command running on either road")
    void taskStopLeavesARunningSubagentStopCommandRunning() throws Exception {
        InterruptAnsweringShell shell = new InterruptAnsweringShell();
        DefaultSubagentExecutionManager manager = managerWithInstantFork();
        CountDownLatch executorAsked = new CountDownLatch(1);
        DeclarativeSubagentStopHook declared = new DeclarativeSubagentStopHook("ops", AUDIT,
                new HostShellActionExecutor(shell.mock));
        DefaultHookRegistry hookRegistry = new DefaultHookRegistry();
        // The hook as declared, observed: the executor asks this question exactly when it is deciding what to do with
        // the interrupt of the worker thread, so the test knows the interrupt was answered before it lets go.
        hookRegistry.register(HookEventType.SUBAGENT_STOP, new SubagentStopHook() {
            @Override
            public HookResult execute(SubagentStopContext context) {
                return declared.execute(context);
            }

            @Override
            public Optional<Duration> getExecutionBudget() {
                return declared.getExecutionBudget();
            }

            @Override
            public boolean ignoresInterrupt() {
                executorAsked.countDown();
                return declared.ignoresInterrupt();
            }
        });

        var future = manager.executeInBackground(
                env(instantForkRegistry()).toBuilder().hookRegistry(hookRegistry).build(), "task-bg", "clock", "go",
                "");
        assertThat(shell.started.await(5, TimeUnit.SECONDS)).isTrue();
        // Both roads at once: the task's signal trips and the worker thread is interrupted.
        assertThat(manager.stop("task-bg")).isTrue();
        assertThat(executorAsked.await(5, TimeUnit.SECONDS)).as("the worker's interrupt reached the hook wait")
                .isTrue();

        // Neither road reached the command: its cancellation did not trip and its thread was not interrupted.
        assertThat(shell.stopped).isFalse();
        shell.mayFinish.countDown();
        future.get(HANG_GUARD.toMillis() * 2, TimeUnit.MILLISECONDS);

        assertThat(shell.ranToCompletion).as("the command finished on its own").isTrue();
        assertThat(shell.stopped).isFalse();
    }

    private static InMemorySubagentRegistry instantForkRegistry() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        dataRegistry.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        return dataRegistry;
    }

    /** A manager with real hooks whose "clock" fork ends at once, so the task goes straight to its SubagentStop. */
    private DefaultSubagentExecutionManager managerWithInstantFork() {
        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("clock", (ctx, req, support) -> support.success("tick"));
        return new DefaultSubagentExecutionManager(reactExecutor, bgPool, new DefaultHookExecutionManager(),
                behaviorRegistry);
    }

    /**
     * A shell that answers both stops, as {@code LocalShell} does: its command ends when its cancellation trips or
     * when its thread is interrupted, and otherwise runs until the test lets it finish.
     */
    private static final class InterruptAnsweringShell {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch mayFinish = new CountDownLatch(1);
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicBoolean ranToCompletion = new AtomicBoolean();
        final VirtualShell mock = mock(VirtualShell.class);

        InterruptAnsweringShell() throws Exception {
            when(mock.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
                final ExecutionOptions options = invocation.getArgument(1);
                final Thread commandThread = Thread.currentThread();
                options.getCancellation().onCancel(() -> {
                    stopped.set(true);
                    commandThread.interrupt();
                });
                started.countDown();
                try {
                    if (!mayFinish.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("the test never let the command finish");
                    }
                } catch (InterruptedException e) {
                    stopped.set(true);
                    Thread.currentThread().interrupt();
                    throw new ShellExecutionException("Interrupted: audit.sh", e);
                }
                ranToCompletion.set(true);
                return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
            });
        }
    }

    /**
     * A shell that ignores thread interrupts, as a shell whose blocking call is a remote request does. Its command
     * runs until its cancellation signal trips or the test lets it finish, and records which of the two ended it.
     */
    private static final class CancellationOnlyShell {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch mayFinish = new CountDownLatch(1);
        final AtomicBoolean stoppedByCancellation = new AtomicBoolean();
        final AtomicBoolean ranToCompletion = new AtomicBoolean();
        final VirtualShell mock = mock(VirtualShell.class);

        CancellationOnlyShell() throws Exception {
            when(mock.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
                final ExecutionOptions options = invocation.getArgument(1);
                final CountDownLatch ended = new CountDownLatch(1);
                options.getCancellation().onCancel(() -> {
                    stoppedByCancellation.set(true);
                    ended.countDown();
                });
                final Thread releaser = new Thread(() -> {
                    try {
                        mayFinish.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ended.countDown();
                }, "command-release");
                releaser.setDaemon(true);
                releaser.start();
                started.countDown();
                if (!awaitIgnoringInterrupts(ended)) {
                    throw new IllegalStateException("the test never let the command finish");
                }
                if (stoppedByCancellation.get()) {
                    throw new ShellCancelledException("Process cancelled: audit.sh");
                }
                ranToCompletion.set(true);
                return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
            });
        }

        private static boolean awaitIgnoringInterrupts(CountDownLatch latch) {
            final long deadline = System.nanoTime() + HANG_GUARD.toNanos();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        return latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** A hook manager that reads each subagent context's signal at the moment the event fires. */
    private static final class SignalRecordingHooks {
        final AtomicReference<Optional<CancellationSignal>> atStart = new AtomicReference<>();
        final AtomicReference<Optional<CancellationSignal>> atStop = new AtomicReference<>();
        final HookExecutionManager manager = mock(HookExecutionManager.class);

        SignalRecordingHooks() {
            when(manager.executeSubagentStart(any())).thenAnswer(invocation -> {
                atStart.set(invocation.<SubagentStartContext>getArgument(0).getExecutionCancellation());
                return List.of();
            });
            when(manager.executeSubagentStop(any())).thenAnswer(invocation -> {
                atStop.set(invocation.<SubagentStopContext>getArgument(0).getExecutionCancellation());
                return List.of();
            });
        }
    }

    private DefaultSubagentExecutionManager newManager(InMemorySubagentBehaviorRegistry behaviorRegistry) {
        return new DefaultSubagentExecutionManager(reactExecutor, bgPool, null, behaviorRegistry);
    }

    private static SubagentLaunchContext env(SubagentRegistry subagentRegistry) {
        return SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(subagentRegistry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }

    private static SubagentLaunchContext envWithSnapshotStore(SubagentRegistry subagentRegistry,
            SessionSnapshotStore snapshotStore, SessionSnapshot previousSnapshot) {
        return SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(subagentRegistry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).defaultModel(LlmModel.builder().name("gpt-4").build())
                .sessionSnapshotStore(snapshotStore).previousSnapshot(previousSnapshot).build();
    }

    private static SubagentExecutionResult reactResult(String answer) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }

    private static SubagentExecutionResult reactResultWithHistory(String answer) {
        final Instant now = Instant.now();
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.generate(), "(data)",
                List.of(Message.user("go"), Message.assistant(answer)));
        return SubagentExecutionResult.success(answer, snapshot, ExecutionMetadata.builder().iterationCount(1)
                .tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }
}
