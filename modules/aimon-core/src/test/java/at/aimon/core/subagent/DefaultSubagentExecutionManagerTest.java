package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.subagent.task.InMemorySessionSnapshotStore;
import at.aimon.core.subagent.task.SessionSnapshotStore;

@DisplayName("DefaultSubagentExecutionManager — code-behavior dispatch fork")
class DefaultSubagentExecutionManagerTest {

    private final SubagentExecutor reactExecutor = mock(SubagentExecutor.class);
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

    private DefaultSubagentExecutionManager newManager(InMemorySubagentBehaviorRegistry behaviorRegistry) {
        return new DefaultSubagentExecutionManager(reactExecutor, bgPool, null, behaviorRegistry);
    }

    private static SubagentExecutionEnvironment env(SubagentRegistry subagentRegistry) {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(subagentRegistry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }

    private static SubagentExecutionEnvironment envWithSnapshotStore(SubagentRegistry subagentRegistry,
            SessionSnapshotStore snapshotStore, SessionSnapshot previousSnapshot) {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(subagentRegistry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).sessionSnapshotStore(snapshotStore)
                .previousSnapshot(previousSnapshot).build();
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
