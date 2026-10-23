package at.aimon.core.tools.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionDeadline;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentExecutionPolicy;
import at.aimon.core.subagent.SubagentExecutionPolicyResolver;
import at.aimon.core.subagent.SubagentLaunchContext;
import at.aimon.core.subagent.SubagentPolicyCapability;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.SubagentResultTruncation;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentOutcomePayload;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("TaskTool trusted execution policy")
class TaskToolExecutionPolicyTest {

    private static final SubagentExecutionPolicy VERIFIER = SubagentExecutionPolicy.builder("review-verifier")
            .maxIterations(8).maxToolCalls(20).maxWallClock(Duration.ofSeconds(120)).maxTokens(300_000)
            .contextTargetTokens(48_000).build();

    private SubagentExecutionManager manager;
    private TaskTool tool;

    @BeforeEach
    void setUp() {
        final SubagentRegistry registry = mock(SubagentRegistry.class);
        lenient().when(registry.getAllSubagents()).thenReturn(List.of());
        manager = mock(SubagentExecutionManager.class);
        lenient().when(manager.supportedPolicyCapabilities()).thenReturn(EnumSet.allOf(SubagentPolicyCapability.class));
        tool = new TaskTool(mock(LlmModel.class), registry, mock(ToolRegistry.class), mock(HookRegistry.class), manager,
                List.of(), null, null, null, SubagentExecutionPolicyResolver.of(Map.of("verifier", VERIFIER)));
    }

    @Test
    @DisplayName("the resolved policy, the spawner's deadline and its engine reach the launch context")
    void policyReachesTheLaunch() {
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(result());
        final ExecutionDeadline parentDeadline = ExecutionDeadline.after(Duration.ofMinutes(5));
        final ContextEngine engine = ContextEngine.passthrough();
        final ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test"))
                .put(ToolContextKeys.EXECUTION_DEADLINE, parentDeadline).put(ToolContextKeys.CONTEXT_ENGINE, engine)
                .build();

        final ToolResult toolResult = tool.execute(input("verifier"), context);

        assertThat(toolResult.isSuccess()).isTrue();
        final SubagentLaunchContext launch = capturedLaunch();
        assertThat(launch.getExecutionPolicy()).containsSame(VERIFIER);
        assertThat(launch.getParentDeadline()).containsSame(parentDeadline);
        assertThat(launch.getParentContextEngine()).containsSame(engine);
    }

    @Test
    @DisplayName("budget-like fields the model writes into the Task call do not reach the policy")
    void modelInputCannotRaiseALimit() {
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(result());

        tool.execute(ToolInput.of(Map.of("subagent_name", "verifier", "prompt", "check", "description", "verify",
                "maxIterations", 1000, "max_tool_calls", 1000, "role", "general-purpose", "policy",
                Map.of("maxWallClockSeconds", 99_999))), contextWithId());

        assertThat(capturedLaunch().getExecutionPolicy()).containsSame(VERIFIER);
    }

    @Test
    @DisplayName("a subagent the resolver has no policy for launches without one")
    void unknownNameHasNoPolicy() {
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(result());

        tool.execute(input("Explore"), contextWithId());

        assertThat(capturedLaunch().getExecutionPolicy()).isEmpty();
    }

    @Test
    @DisplayName("a policy the manager cannot enforce is refused before anything is spawned")
    void unsupportedPolicyIsRefused() {
        when(manager.supportedPolicyCapabilities()).thenReturn(Set.of(SubagentPolicyCapability.MAX_ITERATIONS));

        final ToolResult toolResult = tool.execute(input("verifier"), contextWithId());

        assertThat(toolResult.isError()).isTrue();
        assertThat(toolResult.getContent()).contains("cannot be launched").doesNotContain("MAX_TOOL_CALLS");
        verify(manager, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
        verify(manager, never()).executeInBackground(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the structured outcome rides beside the text")
    void structuredOutcome() {
        final Instant now = Instant.now();
        final SubagentExecutionResult stopped = SubagentExecutionResult
                .failure("Execution stopped", SessionSnapshot.of(SessionId.generate()),
                        ExecutionMetadata.builder().iterationCount(4).tokenUsage(TokenUsage.of(10, 5, 15))
                                .timestamps(now, now).build(),
                        CompletionReason.TOOL_CALL_BUDGET_EXCEEDED, null)
                .withToolCallCount(20).withExitUnconfirmed();
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(stopped);

        final ToolResult toolResult = tool.execute(input("verifier"), contextWithId());

        assertThat(toolResult.getRenderPayload())
                .containsEntry(SubagentOutcomePayload.KIND, SubagentOutcomePayload.KIND_VALUE)
                .containsEntry(SubagentOutcomePayload.COMPLETION_REASON, "TOOL_CALL_BUDGET_EXCEEDED")
                .containsEntry(SubagentOutcomePayload.SUCCESS, false)
                .containsEntry(SubagentOutcomePayload.EXIT_CONFIRMED, false)
                .containsEntry(SubagentOutcomePayload.TOOL_CALLS, 20)
                .containsEntry(SubagentOutcomePayload.ITERATIONS, 4)
                .containsEntry(SubagentOutcomePayload.TOTAL_TOKENS, 15)
                .containsEntry(SubagentOutcomePayload.POLICY_ROLE, "review-verifier")
                .containsEntry(SubagentOutcomePayload.BACKGROUND, false).containsKey(SubagentOutcomePayload.TASK_ID);
        assertThat(toolResult.getContent()).contains("Completion reason: TOOL_CALL_BUDGET_EXCEEDED")
                .contains("Exit: UNCONFIRMED");
    }

    @Test
    @DisplayName("a background launch's payload names the task and nothing about its end")
    void backgroundPayload() {
        when(manager.executeInBackground(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());

        final ToolResult toolResult = tool.execute(ToolInput.of(Map.of("subagent_name", "verifier", "prompt", "check",
                "description", "verify", "run_in_background", true)), contextWithId());

        assertThat(toolResult.getRenderPayload()).containsEntry(SubagentOutcomePayload.BACKGROUND, true)
                .doesNotContainKey(SubagentOutcomePayload.COMPLETION_REASON);
    }

    @Test
    @DisplayName("a policy's result budget and truncation shape the answer the parent reads")
    void policyShapesTheInlinedAnswer() {
        final SubagentExecutionPolicy worker = SubagentExecutionPolicy.builder("review-worker").resultMaxChars(20)
                .resultTruncation(SubagentResultTruncation.HEAD).build();
        final TaskTool shaped = new TaskTool(mock(LlmModel.class), mock(SubagentRegistry.class),
                mock(ToolRegistry.class), mock(HookRegistry.class), manager, List.of(), null, null, null,
                SubagentExecutionPolicyResolver.of(Map.of("worker", worker)));
        final Instant now = Instant.now();
        final String answer = "FINDING-1 FINDING-2 " + "reviewed path\n".repeat(50);
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(
                SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                        .builder().iterationCount(1).tokenUsage(TokenUsage.of(1, 1, 2)).timestamps(now, now).build()));

        final ToolResult toolResult = shaped.execute(input("worker"), contextWithId());

        assertThat(toolResult.getContent())
                .contains("FINDING-1 FINDING-2 \n…[" + (answer.length() - 20) + " chars omitted]…")
                .doesNotContain("reviewed path");
    }

    @Test
    @DisplayName("a completed run without a policy reads as it always has")
    void completedRunTextIsUnchanged() {
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(result());

        final ToolResult toolResult = tool.execute(input("Explore"), contextWithId());

        assertThat(toolResult.getContent()).doesNotContain("Completion reason").doesNotContain("Exit:");
        assertThat(toolResult.getRenderPayload()).doesNotContainKey(SubagentOutcomePayload.POLICY_ROLE)
                .containsEntry(SubagentOutcomePayload.EXIT_CONFIRMED, true);
    }

    @Test
    @DisplayName("end to end: a real Task launch enforces the policy and reports the stop in the structured outcome")
    void endToEndThroughTheRealManagerAndExecutor() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name("verifier").systemPrompt("you verify").build());
        final DefaultToolRegistry tools = new DefaultToolRegistry();
        final AtomicInteger runs = new AtomicInteger();
        tools.register(new AbstractTool("Probe", "probe",
                Map.of("type", "object", "properties", Map.of(), "required", List.of())) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                runs.incrementAndGet();
                return ToolResult.success("ok");
            }
        });
        final ArrayDeque<LlmResponse> script = new ArrayDeque<>(List.of(
                LlmResponse.of("act", List.of(ToolUse.of("a", "Probe", Map.of()), ToolUse.of("b", "Probe", Map.of())),
                        TokenUsage.of(5, 5, 10)),
                LlmResponse.text("never reached")));
        final LlmClient llm = new LlmClient() {
            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> defs,
                    LlmModel modelConfig) {
                return script.removeFirst();
            }

            @Override
            public String getProviderName() {
                return "Scripted";
            }
        };
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try (DefaultSubagentExecutionManager realManager = new DefaultSubagentExecutionManager(
                new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager()),
                pool, null)) {
            final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("review-verifier").maxToolCalls(1)
                    .maxWallClock(Duration.ofMinutes(1)).build();
            final TaskTool realTool = new TaskTool(LlmModel.builder().name("gpt-4").build(), registry, tools,
                    new DefaultHookRegistry(), realManager, List.of(), null, null, null,
                    SubagentExecutionPolicyResolver.of(Map.of("verifier", policy)));

            final ToolResult toolResult = realTool.execute(input("verifier"), contextWithId());

            // Delivering the result is not the subagent completing: the payload says which it was.
            assertThat(toolResult.isSuccess()).isTrue();
            assertThat(toolResult.getRenderPayload())
                    .containsEntry(SubagentOutcomePayload.COMPLETION_REASON, "TOOL_CALL_BUDGET_EXCEEDED")
                    .containsEntry(SubagentOutcomePayload.TOOL_CALLS, 1)
                    .containsEntry(SubagentOutcomePayload.EXIT_CONFIRMED, true);
            assertThat(runs).hasValue(1);
            assertThat(script).hasSize(1);
        }
    }

    private SubagentLaunchContext capturedLaunch() {
        final ArgumentCaptor<SubagentLaunchContext> captor = ArgumentCaptor.forClass(SubagentLaunchContext.class);
        verify(manager).execute(captor.capture(), anyString(), anyString(), anyString(), anyString());
        return captor.getValue();
    }

    private static ToolInput input(String subagent) {
        return ToolInput.of(Map.of("subagent_name", subagent, "prompt", "check the finding", "description", "verify"));
    }

    private static ToolContext contextWithId() {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test")).build();
    }

    private static SubagentExecutionResult result() {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success("done", SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.of(1, 1, 2)).timestamps(now, now).build());
    }
}
