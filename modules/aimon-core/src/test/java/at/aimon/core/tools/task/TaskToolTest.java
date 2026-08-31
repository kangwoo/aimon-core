package at.aimon.core.tools.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.exception.SubagentNotFoundException;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentResultFormatter;
import at.aimon.core.subagent.task.InMemorySessionSnapshotStore;
import at.aimon.core.subagent.task.InMemoryTaskOutputStore;
import at.aimon.core.subagent.task.SessionSnapshotStore;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.tools.ToolContextKeys;

class TaskToolTest {

    private LlmModel defaultModel;
    private SubagentRegistry subagentRegistry;
    private ToolRegistry toolRegistry;
    private HookRegistry hookRegistry;
    private Environment environment;
    private SubagentExecutionManager executionManager;
    private TaskTool tool;

    @BeforeEach
    void setUp() {
        defaultModel = mock(LlmModel.class);
        subagentRegistry = mock(SubagentRegistry.class);
        toolRegistry = mock(ToolRegistry.class);
        hookRegistry = mock(HookRegistry.class);
        environment = mock(Environment.class);
        executionManager = mock(SubagentExecutionManager.class);
        lenient().when(subagentRegistry.getAllSubagents()).thenReturn(List.of());
        tool = new TaskTool(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment, executionManager);
    }

    private SubagentExecutionResult successResult() {
        Instant now = Instant.now();
        ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(3)
                .tokenUsage(TokenUsage.of(100, 200, 300)).timestamps(now, now).build();
        SessionSnapshot snapshot = SessionSnapshot.of(SessionId.generate(), "sys", List.of());
        return SubagentExecutionResult.success("done", snapshot, metadata);
    }

    private ToolContext contextWithId() {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test")).build();
    }

    private ToolInput validInput() {
        return ToolInput.of(Map.of("subagent_name", "Explore", "prompt", "Find auth files", "description", "auth"));
    }

    @Test
    void constructorRejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(
                () -> new TaskTool(null, subagentRegistry, toolRegistry, hookRegistry, environment, executionManager));
        assertThatNullPointerException().isThrownBy(
                () -> new TaskTool(defaultModel, null, toolRegistry, hookRegistry, environment, executionManager));
        assertThatNullPointerException().isThrownBy(
                () -> new TaskTool(defaultModel, subagentRegistry, null, hookRegistry, environment, executionManager));
        assertThatNullPointerException().isThrownBy(
                () -> new TaskTool(defaultModel, subagentRegistry, toolRegistry, null, environment, executionManager));
        assertThatNullPointerException().isThrownBy(
                () -> new TaskTool(defaultModel, subagentRegistry, toolRegistry, hookRegistry, null, executionManager));
        assertThatNullPointerException().isThrownBy(
                () -> new TaskTool(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment, null));
    }

    @Test
    void executeRejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> tool.execute(null, ToolContext.empty()));
        assertThatNullPointerException().isThrownBy(() -> tool.execute(validInput(), null));
    }

    @Test
    void definitionExposesToolNameAndDescription() {
        Subagent explore = stubSubagent("Explore", "Find files");
        when(subagentRegistry.getAllSubagents()).thenReturn(List.of(explore));

        assertThat(tool.getDefinition().getName()).isEqualTo(TaskTool.TOOL_NAME);
        assertThat(tool.getDefinition().getDescription()).contains("Explore").contains("Find files");
    }

    @Test
    void definitionDescriptionIncludesPlaceholderWhenNoSubagents() {
        when(subagentRegistry.getAllSubagents()).thenReturn(List.of());

        assertThat(tool.getDefinition().getDescription()).contains("No subagents currently available");
    }

    @Test
    void definitionSurfacesWhenToUseWhenPresent() {
        Subagent reviewer = Subagent.builder().name("code-reviewer").description("Reviews code")
                .whenToUse("When you need code review").systemPrompt("You are a reviewer.").build();
        when(subagentRegistry.getAllSubagents()).thenReturn(List.of(reviewer));

        assertThat(tool.getDefinition().getDescription()).contains("code-reviewer: Reviews code")
                .contains("(Use when: When you need code review)");
    }

    @Test
    void executeRejectsBlankResumeParameter() {
        ToolInput input = ToolInput
                .of(Map.of("subagent_name", "Explore", "prompt", "p", "description", "d", "resume", "   "));

        ToolResult result = tool.execute(input, contextWithId());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("resume must not be blank");
    }

    @Test
    void executeRejectsResumeWhenNoSnapshotStoreConfigured() {
        // `tool` is built with the store-less constructor → resume must be rejected, not silently ignored.
        ToolInput input = ToolInput
                .of(Map.of("subagent_name", "Explore", "prompt", "p", "description", "d", "resume", "task-123"));

        ToolResult result = tool.execute(input, contextWithId());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("no session snapshot store is configured");
    }

    @Test
    void executeRejectsResumeWhenSnapshotNotFound() {
        SessionSnapshotStore store = new InMemorySessionSnapshotStore();
        TaskTool withStore = new TaskTool(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment,
                executionManager, List.of(), null, store);
        ToolInput input = ToolInput
                .of(Map.of("subagent_name", "Explore", "prompt", "p", "description", "d", "resume", "missing"));

        ToolResult result = withStore.execute(input, contextWithId());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("No resumable task found for id: missing");
    }

    @Test
    void executeForwardsTheCallersConversationAsTheInvoker() {
        final SessionId caller = SessionId.generate();

        final SubagentExecutionEnvironment env = captureEnvFor(
                ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test"))
                        .put(ToolContextKeys.SESSION_ID, caller).build());

        assertThat(env.getInvokingSessionId()).contains(caller);
    }

    @Test
    void executeFromWithinASubagentHandsDownTheInheritedConversation() {
        // Depth 2: a subagent spawning another. Handing down the intermediate fork's OWN id would end the reach here,
        // because nothing is ever granted under a fork's own id.
        final SessionId user = SessionId.generate();
        final SessionId intermediateFork = SessionId.generate();

        final SubagentExecutionEnvironment env = captureEnvFor(
                ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test"))
                        .put(ToolContextKeys.SESSION_ID, intermediateFork)
                        .put(ToolContextKeys.INVOKING_SESSION_ID, user).build());

        assertThat(env.getInvokingSessionId()).contains(user);
        assertThat(env.getInvokingSessionId()).isNotEqualTo(Optional.of(intermediateFork));
    }

    @Test
    void executeWithoutAnyConversationLeavesTheInvokerEmpty() {
        assertThat(captureEnvFor(contextWithId()).getInvokingSessionId()).isEmpty();
    }

    /** Runs a successful foreground Task against the given context and returns the environment it built. */
    private SubagentExecutionEnvironment captureEnvFor(ToolContext context) {
        when(executionManager.execute(any(SubagentExecutionEnvironment.class), anyString(), eq("Explore"), anyString(),
                anyString())).thenReturn(successResult());

        tool.execute(ToolInput.of(Map.of("subagent_name", "Explore", "prompt", "p", "description", "d")), context);

        final ArgumentCaptor<SubagentExecutionEnvironment> captor = ArgumentCaptor
                .forClass(SubagentExecutionEnvironment.class);
        verify(executionManager).execute(captor.capture(), anyString(), eq("Explore"), anyString(), anyString());
        return captor.getValue();
    }

    @Test
    void executeResumesFromStoredSnapshotAndForwardsItToEnvironment() {
        SessionSnapshotStore store = new InMemorySessionSnapshotStore();
        SessionSnapshot saved = SessionSnapshot.of(SessionId.generate(), "sys", List.of());
        // The transcript is tagged with the caller's own context, so the scoped resume load surfaces it.
        store.save("task-123", "Explore", AgentRuntimeId.of("agent:test"), saved);
        TaskTool withStore = new TaskTool(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment,
                executionManager, List.of(), null, store);
        when(executionManager.execute(any(SubagentExecutionEnvironment.class), anyString(), eq("Explore"), anyString(),
                anyString())).thenReturn(successResult());
        ToolInput input = ToolInput
                .of(Map.of("subagent_name", "Explore", "prompt", "p", "description", "d", "resume", "task-123"));

        ToolResult result = withStore.execute(input, contextWithId());

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<SubagentExecutionEnvironment> envCaptor = ArgumentCaptor
                .forClass(SubagentExecutionEnvironment.class);
        verify(executionManager).execute(envCaptor.capture(), anyString(), eq("Explore"), anyString(), anyString());
        assertThat(envCaptor.getValue().getPreviousSnapshot()).contains(saved);
        assertThat(envCaptor.getValue().getSessionSnapshotStore()).contains(store);
    }

    @Test
    void executeRejectsResumeWhenSnapshotBelongsToDifferentSubagent() {
        SessionSnapshotStore store = new InMemorySessionSnapshotStore();
        SessionSnapshot saved = SessionSnapshot.of(SessionId.generate(), "sys", List.of());
        // The snapshot was recorded by "Explore" in the caller's own context; resuming it as "Plan" must be rejected on
        // the subagent-name check (the context check passes, so we reach the mismatch branch), not silently grafted.
        store.save("task-123", "Explore", AgentRuntimeId.of("agent:test"), saved);
        TaskTool withStore = new TaskTool(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment,
                executionManager, List.of(), null, store);
        ToolInput input = ToolInput
                .of(Map.of("subagent_name", "Plan", "prompt", "p", "description", "d", "resume", "task-123"));

        ToolResult result = withStore.execute(input, contextWithId());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("belongs to subagent 'Explore'").contains("not 'Plan'");
        verify(executionManager, never()).execute(any(SubagentExecutionEnvironment.class), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void executeDeniesResumeOfSnapshotOwnedByAnotherContext() {
        SessionSnapshotStore store = new InMemorySessionSnapshotStore();
        SessionSnapshot saved = SessionSnapshot.of(SessionId.generate(), "sys", List.of());
        // The transcript belongs to a different agent's runtime. Even though the id is known, the caller
        // (agent:test) must not be able to resume — and thereby read — another agent's transcript.
        store.save("task-123", "Explore", AgentRuntimeId.of("agent:other"), saved);
        TaskTool withStore = new TaskTool(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment,
                executionManager, List.of(), null, store);
        ToolInput input = ToolInput
                .of(Map.of("subagent_name", "Explore", "prompt", "p", "description", "d", "resume", "task-123"));

        ToolResult result = withStore.execute(input, contextWithId());

        assertThat(result.isError()).isTrue();
        // Reported as unknown id — the foreign transcript's existence is not leaked.
        assertThat(result.getContent()).contains("No resumable task found for id: task-123");
        verify(executionManager, never()).execute(any(SubagentExecutionEnvironment.class), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void nineArgConstructorAcceptsConversationSnapshotStore() {
        SessionSnapshotStore store = new InMemorySessionSnapshotStore();

        TaskTool withStore = new TaskTool(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment,
                executionManager, List.of(), null, store);

        assertThat(withStore.getDefinition().getName()).isEqualTo(TaskTool.TOOL_NAME);
    }

    @Test
    void executeReportsErrorWhenAgentRuntimeIdMissing() {
        ToolResult result = tool.execute(validInput(), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Task execution failed");
    }

    @Test
    void executeWrapsSubagentNotFoundExceptionWithAvailableNames() {
        Subagent gp = stubSubagent("general-purpose", "general");
        Subagent explore = stubSubagent("Explore", "explore files");
        when(subagentRegistry.getAllSubagents()).thenReturn(List.of(gp, explore));
        when(executionManager.execute(any(SubagentExecutionEnvironment.class), anyString(), eq("Explore"), anyString(),
                anyString())).thenThrow(new SubagentNotFoundException("Explore"));

        ToolResult result = tool.execute(validInput(), contextWithId());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Subagent not found").contains("Explore").contains("general-purpose");
    }

    @Test
    void executeReturnsFormattedResultOnSuccess() {
        when(executionManager.execute(any(SubagentExecutionEnvironment.class), anyString(), eq("Explore"),
                eq("Find auth files"), eq("auth"))).thenReturn(successResult());

        ToolResult result = tool.execute(validInput(), contextWithId());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Subagent Task Result").contains("Subagent: Explore")
                .contains("Status: SUCCESS").contains("done");
        verify(executionManager, times(1)).execute(any(), anyString(), eq("Explore"), eq("Find auth files"), eq("auth"));
    }

    @Test
    void executeReturnsBackgroundLaunchMessageWhenRunInBackground() {
        when(executionManager.executeInBackground(any(SubagentExecutionEnvironment.class), anyString(), eq("Explore"),
                anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(successResult()));

        ToolInput input = ToolInput.of(Map.of("subagent_name", "Explore", "prompt", "Find auth files", "description",
                "auth", "run_in_background", true));

        ToolResult result = tool.execute(input, contextWithId());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Background task launched").contains("Subagent: Explore")
                .contains("AgentOutput");
        verify(executionManager).executeInBackground(any(), anyString(), eq("Explore"), eq("Find auth files"),
                eq("auth"));
    }

    @Test
    void executeReportsInvalidParameterWhenRequiredFieldMissing() {
        ToolInput input = ToolInput.of(Map.of("subagent_name", "Explore"));

        ToolResult result = tool.execute(input, contextWithId());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    // --- Foreground result truncation ------------------------------------------------------------

    @Test
    void executeTruncatesLargeForegroundResultWithoutRetrievalPointer() {
        String hugeSummary = "Z".repeat(SubagentResultFormatter.DEFAULT_MAX_CHARS + 500);
        Instant now = Instant.now();
        ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.of(1, 1, 2))
                .timestamps(now, now).build();
        SessionSnapshot snapshot = SessionSnapshot.of(SessionId.generate(), "sys", List.of());
        SubagentExecutionResult huge = SubagentExecutionResult.success(hugeSummary, snapshot, metadata);
        when(executionManager.execute(any(SubagentExecutionEnvironment.class), anyString(), eq("Explore"),
                eq("Find auth files"), eq("auth"))).thenReturn(huge);

        ToolResult result = tool.execute(validInput(), contextWithId());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("chars omitted");
        // Foreground results are inlined, not stored — the marker must NOT carry a retrieval pointer.
        assertThat(result.getContent()).doesNotContain(" — ").doesNotContain("AgentOutput");
        assertThat(result.getContent().length()).isLessThan(hugeSummary.length());
    }

    @Test
    void eightArgConstructorAcceptsTaskOutputStore() {
        TaskOutputStore store = new InMemoryTaskOutputStore();

        TaskTool withStore = new TaskTool(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment,
                executionManager, List.of(), store);

        assertThat(withStore.getDefinition().getName()).isEqualTo(TaskTool.TOOL_NAME);
    }

    private Subagent stubSubagent(String name, String description) {
        Subagent subagent = mock(Subagent.class);
        SubagentMetadata metadata = mock(SubagentMetadata.class);
        lenient().when(subagent.getName()).thenReturn(name);
        lenient().when(subagent.getMetadata()).thenReturn(metadata);
        lenient().when(metadata.getDescription()).thenReturn(description);
        return subagent;
    }
}
