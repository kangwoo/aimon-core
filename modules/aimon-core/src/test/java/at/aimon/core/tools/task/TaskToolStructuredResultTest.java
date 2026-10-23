package at.aimon.core.tools.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
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
import at.aimon.core.subagent.SubagentPolicyCapability;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.subagent.execution.StructuredResultReport;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentOutcomePayload;
import at.aimon.core.subagent.execution.SubagentResultFormatter;
import at.aimon.core.tools.ToolContextKeys;

/**
 * The parent side of a structured result: what the spawning model reads and what code reads beside it, for a report,
 * for a missing one, and for a launch whose policy asks for none.
 */
@DisplayName("TaskTool structured result")
class TaskToolStructuredResultTest {

    private static final Map<String, Object> SCHEMA = Map
            .of("type", "object", "properties",
                    Map.of("findings",
                            Map.of("type", "array", "items", Map.of("type", "object", "properties",
                                    Map.of("severity", Map.of("type", "string", "enum", List.of("HIGH", "LOW")), "body",
                                            Map.of("type", "string")),
                                    "required", List.of("severity", "body")))),
                    "required", List.of("findings"));

    private static final SubagentExecutionPolicy WORKER = SubagentExecutionPolicy.builder("review-worker")
            .resultSchema(SCHEMA).build();

    private SubagentExecutionManager manager;
    private TaskTool tool;

    @BeforeEach
    void setUp() {
        final SubagentRegistry registry = mock(SubagentRegistry.class);
        lenient().when(registry.getAllSubagents()).thenReturn(List.of());
        manager = mock(SubagentExecutionManager.class);
        lenient().when(manager.supportedPolicyCapabilities()).thenReturn(EnumSet.allOf(SubagentPolicyCapability.class));
        tool = new TaskTool(mock(LlmModel.class), registry, mock(ToolRegistry.class), mock(HookRegistry.class), manager,
                List.of(), null, null, null, SubagentExecutionPolicyResolver.of(Map.of("worker", WORKER)));
    }

    @Test
    @DisplayName("an answer over 32,000 characters is truncated; the structured result beside it is not")
    void payloadIsNeverTruncated() {
        final Map<String, Object> payload = payload("x".repeat(40_000));
        final SubagentExecutionResult result = success("prose ".repeat(10_000), CompletionReason.COMPLETED)
                .withStructuredResultRequired(true).withStructuredResult(payload);
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(result);

        final ToolResult toolResult = tool.execute(input("worker"), context());

        final String text = toolResult.getContent();
        assertThat(text).contains("chars omitted]…").contains(SubagentResultFormatter.STRUCTURED_RESULT_HEADER)
                .contains("```json\n" + StructuredResultReport.toJson(payload) + "\n```\n");
        assertThat(text.length()).isGreaterThan(SubagentResultFormatter.DEFAULT_MAX_CHARS + 40_000);
        assertThat(toolResult.getRenderPayload()).containsEntry(SubagentOutcomePayload.STRUCTURED_RESULT, payload)
                .containsEntry(SubagentOutcomePayload.STRUCTURED_RESULT_MISSING, false);
    }

    @Test
    @DisplayName("a required result that was not reported is called out, before the reason line the CLI reads last")
    void missingResultIsVisible() {
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(success("partial findings", CompletionReason.MAX_ITERATIONS_GRACE));

        final ToolResult toolResult = tool.execute(input("worker"), context());

        final String text = toolResult.getContent();
        assertThat(text).contains(SubagentResultFormatter.STRUCTURED_RESULT_MISSING_LINE);
        assertThat(text.strip().substring(text.strip().lastIndexOf('\n') + 1))
                .startsWith("Completion reason: MAX_ITERATIONS_GRACE");
        assertThat(text.indexOf(SubagentResultFormatter.STRUCTURED_RESULT_MISSING_LINE))
                .isGreaterThan(text.indexOf("partial findings"));
        // The policy alone says it was required — the result need not (a result the manager made without the fork).
        assertThat(toolResult.getRenderPayload()).containsEntry(SubagentOutcomePayload.STRUCTURED_RESULT_MISSING, true)
                .doesNotContainKey(SubagentOutcomePayload.STRUCTURED_RESULT);
    }

    @Test
    @DisplayName("without a policy the text is byte for byte what it always was, and the payload has no new keys")
    void noPolicyIsByteIdentical() {
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(success("done", CompletionReason.COMPLETED));

        final ToolResult toolResult = tool.execute(input("Explore"), context());

        assertThat(toolResult.getContent()).isEqualTo("=== Subagent Task Result ===\nSubagent: Explore\n"
                + "Task: review\nStatus: SUCCESS\nIterations: 1\nTokens: 2\n\nResult:\ndone\n");
        assertThat(toolResult.getRenderPayload()).doesNotContainKeys(SubagentOutcomePayload.STRUCTURED_RESULT,
                SubagentOutcomePayload.STRUCTURED_RESULT_MISSING);
    }

    @Test
    @DisplayName("a policy without a result schema adds nothing either")
    void policyWithoutSchemaAddsNothing() {
        final TaskTool bounded = new TaskTool(mock(LlmModel.class), mock(SubagentRegistry.class),
                mock(ToolRegistry.class), mock(HookRegistry.class), manager, List.of(), null, null, null,
                SubagentExecutionPolicyResolver
                        .of(Map.of("worker", SubagentExecutionPolicy.builder("w").maxToolCalls(3).build())));
        when(manager.execute(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(success("done", CompletionReason.COMPLETED));

        final ToolResult toolResult = bounded.execute(input("worker"), context());

        assertThat(toolResult.getContent()).doesNotContain("Structured result");
        assertThat(toolResult.getRenderPayload()).doesNotContainKeys(SubagentOutcomePayload.STRUCTURED_RESULT,
                SubagentOutcomePayload.STRUCTURED_RESULT_MISSING);
    }

    @Test
    @DisplayName("a manager that cannot offer the report tool refuses the policy before anything is spawned")
    void unsupportedCapabilityIsRefused() {
        final EnumSet<SubagentPolicyCapability> withoutIt = EnumSet.allOf(SubagentPolicyCapability.class);
        withoutIt.remove(SubagentPolicyCapability.STRUCTURED_RESULT);
        when(manager.supportedPolicyCapabilities()).thenReturn(withoutIt);

        final ToolResult toolResult = tool.execute(input("worker"), context());

        assertThat(toolResult.isError()).isTrue();
        assertThat(toolResult.getContent()).contains("cannot be launched");
        verify(manager, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("end to end: the child fixes a rejected report and the parent receives only the valid one, whole")
    void endToEnd() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name("worker").systemPrompt("you review").build());
        final String longBody = "y".repeat(35_000);
        final ArrayDeque<LlmResponse> script = new ArrayDeque<>(List.of(
                LlmResponse.of("reporting",
                        List.of(ToolUse.of("r1", StructuredResultReport.TOOL_NAME,
                                Map.of("findings", List.of(Map.of("severity", "CRITICAL", "body", "bad"))))),
                        TokenUsage.of(5, 5, 10)),
                LlmResponse.of("fixed", List.of(ToolUse.of("r2", StructuredResultReport.TOOL_NAME, payload(longBody))),
                        TokenUsage.of(5, 5, 10)),
                LlmResponse.text("summary ".repeat(5_000))));
        final List<List<Message>> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        final LlmClient llm = new LlmClient() {
            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> defs,
                    LlmModel modelConfig) {
                sent.add(List.copyOf(messages));
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
            final TaskTool realTool = new TaskTool(LlmModel.builder().name("gpt-4").build(), registry,
                    new DefaultToolRegistry(), new DefaultHookRegistry(), realManager, List.of(), null, null, null,
                    SubagentExecutionPolicyResolver.of(Map.of("worker", WORKER)));

            final ToolResult toolResult = realTool.execute(input("worker"), context());

            assertThat(toolResult.getRenderPayload())
                    .containsEntry(SubagentOutcomePayload.COMPLETION_REASON, "COMPLETED")
                    .containsEntry(SubagentOutcomePayload.STRUCTURED_RESULT, payload(longBody))
                    .containsEntry(SubagentOutcomePayload.STRUCTURED_RESULT_MISSING, false)
                    .containsEntry(SubagentOutcomePayload.TOOL_CALLS, 0);
            assertThat(toolResult.getContent()).contains(StructuredResultReport.toJson(payload(longBody)))
                    .doesNotContain("CRITICAL");
            // The child was told what to fix.
            final List<Message> second = sent.get(1);
            assertThat(second.get(second.size() - 1).getToolUseResults().get(0).getContent())
                    .contains("must be one of [HIGH, LOW], but was 'CRITICAL'");
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Object> payload(String body) {
        final Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", "HIGH");
        finding.put("body", body);
        return Map.of("findings", List.of(finding));
    }

    private static SubagentExecutionResult success(String answer, CompletionReason reason) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.of(1, 1, 2)).timestamps(now, now).build(), reason);
    }

    private static ToolInput input(String subagent) {
        return ToolInput.of(Map.of("subagent_name", subagent, "prompt", "review the diff", "description", "review"));
    }

    private static ToolContext context() {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test")).build();
    }
}
