package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionDeadline;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentExecutionPolicy;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentPolicyCapability;

/**
 * The fork side of a structured result: the report tool a policy with a result schema offers, how a call to it is
 * judged, where its answer goes in a batch, how it relates to the policy's other axes, and what reaches the result.
 */
@DisplayName("DefaultSubagentExecutor structured result")
class DefaultSubagentExecutorStructuredResultTest {

    private static final TokenUsage USAGE = TokenUsage.of(5, 5, 10);

    /** A code-review worker's findings: a list of objects, each with an enum and required fields. */
    static final Map<String, Object> FINDINGS_SCHEMA = findingsSchema();

    @Test
    @DisplayName("the executor claims STRUCTURED_RESULT, with or without a compacting engine")
    void claimsTheCapability() {
        assertThat(executor(new ScriptedClient()).supportedPolicyCapabilities())
                .contains(SubagentPolicyCapability.STRUCTURED_RESULT);
    }

    @Test
    @DisplayName("the report tool is offered last, with the policy's schema, whatever the allow-list says")
    void reportToolIsOffered() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(report("r1", findings(finding("HIGH"))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(context(List.of("Probe")),
                request(policy().build()));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(llm.offeredTools.get(0)).extracting(ToolDefinition::getName).containsExactly(CountingTool.NAME,
                StructuredResultReport.TOOL_NAME);
        assertThat(llm.offeredTools.get(0).get(1).getInputSchema()).isEqualTo(FINDINGS_SCHEMA);
        assertThat(result.getStructuredResult()).contains(findings(finding("HIGH")));
        assertThat(result.isStructuredResultRequired()).isTrue();
    }

    @Test
    @DisplayName("without a result schema nothing changes: no report tool, nothing required, a call is just unknown")
    void noSchemaNoChange() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(report("r1", findings(finding("HIGH"))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(context(null),
                request(SubagentExecutionPolicy.builder("worker").maxToolCalls(5).build()));

        assertThat(llm.offeredTools.get(0)).extracting(ToolDefinition::getName).containsExactly(CountingTool.NAME);
        assertThat(result.getStructuredResult()).isEmpty();
        assertThat(result.isStructuredResultRequired()).isFalse();
        // The call went to the tool pipeline, which knows no such tool.
        assertThat(toolResults(result).get(0).get(0).isError()).isTrue();
        assertThat(result.getToolCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a payload that violates the schema is answered with what to fix; the corrected one is recorded")
    void violationIsReturnedAndCorrected() {
        final ScriptedClient llm = new ScriptedClient();
        final Map<String, Object> bad = new LinkedHashMap<>(finding("URGENT"));
        bad.remove("path");
        llm.responses.add(report("r1", findings(bad)));
        llm.responses.add(report("r2", findings(finding("HIGH"))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(context(null), request(policy().build()));

        final ToolUseResult rejected = toolResults(result).get(0).get(0);
        assertThat(rejected.isError()).isTrue();
        assertThat(rejected.getContent()).startsWith("Not recorded")
                .contains("In 'findings[0]': Parameter 'path' is required")
                .contains("In 'findings[0]': Parameter 'severity' must be one of [HIGH, MEDIUM, LOW], but was "
                        + "'URGENT'");
        assertThat(toolResults(result).get(1).get(0).isError()).isFalse();
        assertThat(result.getStructuredResult()).contains(findings(finding("HIGH")));
        // A report is not a tool call the pipeline ran.
        assertThat(result.getToolCallCount()).isZero();
    }

    @Test
    @DisplayName("a payload that never fits is never delivered")
    void invalidPayloadNeverReachesTheResult() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(report("r1", Map.of("findings", "not a list")));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(context(null), request(policy().build()));

        assertThat(result.getStructuredResult()).isEmpty();
        assertThat(result.isStructuredResultRequired()).isTrue();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    @DisplayName("a payload over the size cap is answered with an error and not recorded")
    void overTheCapIsRejected() {
        final ScriptedClient llm = new ScriptedClient();
        final Map<String, Object> large = new LinkedHashMap<>(finding("LOW"));
        large.put("body", "x".repeat(500));
        llm.responses.add(report("r1", findings(large)));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(context(null),
                request(policy().resultPayloadMaxChars(300).build()));

        assertThat(toolResults(result).get(0).get(0).getContent()).startsWith("Not recorded")
                .contains("accepts at most 300");
        assertThat(result.getStructuredResult()).isEmpty();
    }

    @Test
    @DisplayName("a payload far over the inline answer budget reaches the result whole")
    void largePayloadIsLossless() {
        final ScriptedClient llm = new ScriptedClient();
        final Map<String, Object> big = new LinkedHashMap<>(finding("MEDIUM"));
        big.put("existingCode", "line of code;\n".repeat(4_000));
        llm.responses.add(report("r1", findings(big, finding("LOW"))));
        llm.responses.add(LlmResponse.text("answer ".repeat(10_000)));

        final SubagentExecutionResult result = executor(llm).execute(context(null), request(policy().build()));

        assertThat(StructuredResultReport.toJson(result.getStructuredResult().orElseThrow()).length())
                .isGreaterThan(SubagentResultFormatter.DEFAULT_MAX_CHARS);
        assertThat(result.getStructuredResult()).contains(findings(big, finding("LOW")));
    }

    @Test
    @DisplayName("the last accepted report wins")
    void lastReportWins() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(report("r1", findings(finding("HIGH"))));
        llm.responses.add(report("r2", findings(finding("LOW"))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(context(null), request(policy().build()));

        assertThat(result.getStructuredResult()).contains(findings(finding("LOW")));
    }

    @Test
    @DisplayName("a mixed batch keeps the response's order, matched by position even when ids repeat")
    void mixedBatchKeepsOrder() {
        final CountingTool tool = new CountingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act",
                List.of(ToolUse.of("same", CountingTool.NAME, Map.of()),
                        ToolUse.of("same", StructuredResultReport.TOOL_NAME, findings(finding("HIGH"))),
                        ToolUse.of("same", CountingTool.NAME, Map.of()),
                        ToolUse.of("same", StructuredResultReport.TOOL_NAME, Map.of("findings", 3))),
                USAGE));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(context(tool, null), request(policy().build()));

        final List<ToolUseResult> batch = toolResults(result).get(0);
        assertThat(batch).hasSize(4);
        assertThat(batch.get(0).getContent()).isEqualTo("ok");
        assertThat(batch.get(1).getContent()).startsWith("Recorded");
        assertThat(batch.get(2).getContent()).isEqualTo("ok");
        assertThat(batch.get(3).getContent()).startsWith("Not recorded");
        assertThat(tool.runs).hasValue(2);
        assertThat(result.getToolCallCount()).isEqualTo(2);
        // The rejected report after the accepted one does not erase it.
        assertThat(result.getStructuredResult()).contains(findings(finding("HIGH")));
    }

    @Test
    @DisplayName("a report takes none of the tool-call budget and is judged in the batch that exhausts it")
    void reportIsNotAToolCall() {
        final CountingTool tool = new CountingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act",
                List.of(ToolUse.of("a", CountingTool.NAME, Map.of()),
                        ToolUse.of("r", StructuredResultReport.TOOL_NAME, findings(finding("HIGH"))),
                        ToolUse.of("b", CountingTool.NAME, Map.of())),
                USAGE));
        llm.responses.add(LlmResponse.text("never reached"));

        final SubagentExecutionResult result = executor(llm).execute(context(tool, null),
                request(policy().maxToolCalls(1).build()));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TOOL_CALL_BUDGET_EXCEEDED);
        assertThat(result.getToolCallCount()).isEqualTo(1);
        final List<ToolUseResult> batch = toolResults(result).get(0);
        assertThat(batch.get(1).getContent()).startsWith("Recorded");
        assertThat(batch.get(2).getContent()).startsWith("Not run");
        assertThat(result.getStructuredResult()).contains(findings(finding("HIGH")));
    }

    @Test
    @DisplayName("a report is judged even in the batch of a response that spent the token budget")
    void reportInATokenExhaustedBatch() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act",
                List.of(ToolUse.of("r", StructuredResultReport.TOOL_NAME, findings(finding("HIGH"))),
                        ToolUse.of("a", CountingTool.NAME, Map.of())),
                TokenUsage.of(900, 200, 1100)));

        final SubagentExecutionResult result = executor(llm).execute(context(null),
                request(policy().maxTokens(1000).build()));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        assertThat(result.getStructuredResult()).contains(findings(finding("HIGH")));
        assertThat(result.getToolCallCount()).isZero();
    }

    @Test
    @DisplayName("rejected reports never block the report tool, whatever the failure-streak limit")
    void failureStreakDoesNotBlockReports() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(report("r1", Map.of()));
        llm.responses.add(report("r2", Map.of()));
        llm.responses.add(report("r3", findings(finding("LOW"))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(context(null),
                request(policy().maxConsecutiveToolFailures(1).build()));

        assertThat(toolResults(result).get(2).get(0).getContent()).startsWith("Recorded");
        assertThat(result.getStructuredResult()).contains(findings(finding("LOW")));
    }

    @Test
    @DisplayName("an accepted report is progress for the stalled-iteration guard; rejected ones are not")
    void stallGuardCountsRejectionsOnly() {
        final ScriptedClient llm = new ScriptedClient();
        for (int i = 0; i < 6; i++) {
            llm.responses.add(report("r" + i, Map.of()));
        }
        final SubagentExecutionResult stalled = executor(llm).execute(context(null), request(policy().build()));
        assertThat(stalled.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(stalled.getStructuredResult()).isEmpty();

        final ScriptedClient progressing = new ScriptedClient();
        for (int i = 0; i < 6; i++) {
            progressing.responses.add(report("r" + i, findings(finding("LOW"))));
        }
        progressing.responses.add(LlmResponse.text("done"));
        final SubagentExecutionResult completed = executor(progressing).execute(context(null),
                request(policy().build()));
        assertThat(completed.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    @DisplayName("grace round: a report in the final answer is recorded, every other call is still dropped")
    void graceRoundHonoursTheReport() {
        final CountingTool tool = new CountingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("Two findings; see the report.",
                List.of(ToolUse.of("g1", CountingTool.NAME, Map.of()),
                        ToolUse.of("g2", StructuredResultReport.TOOL_NAME, findings(finding("HIGH"), finding("LOW")))),
                USAGE));

        final SubagentExecutionResult result = executor(llm).execute(context(tool, null),
                request(policy().maxIterations(1).graceRound(true).build()));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS_GRACE);
        assertThat(result.getSummary()).isEqualTo("Two findings; see the report.");
        assertThat(tool.runs).as("the grace round runs no tool").hasValue(1);
        assertThat(result.getStructuredResult()).contains(findings(finding("HIGH"), finding("LOW")));
        // The reminder names the exception; the transcript keeps the grace answer's text only.
        final List<Message> graceRequest = llm.sentMessages.get(1);
        assertThat(graceRequest.get(graceRequest.size() - 1).getContent())
                .contains(DefaultSubagentExecutor.GRACE_ROUND_INSTRUCTION)
                .contains(StructuredResultReport.GRACE_ROUND_NOTE.strip());
        final List<Message> history = result.getConversationHistory();
        assertThat(history.get(history.size() - 1).getToolUses()).isEmpty();
        assertThat(history.get(history.size() - 1).getContent()).isEqualTo("Two findings; see the report.");
    }

    @Test
    @DisplayName("grace round without a schema: the reminder is exactly what it was")
    void graceReminderUnchangedWithoutSchema() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.text("written up"));

        executor(llm).execute(context(null),
                request(SubagentExecutionPolicy.builder("worker").maxIterations(1).graceRound(true).build()));

        final List<Message> graceRequest = llm.sentMessages.get(1);
        assertThat(graceRequest.get(graceRequest.size() - 1).getContent())
                .contains(DefaultSubagentExecutor.GRACE_ROUND_INSTRUCTION)
                .doesNotContain(StructuredResultReport.TOOL_NAME);
    }

    @Test
    @DisplayName("grace round with a report and no text ends MAX_ITERATIONS, still carrying the report")
    void graceRoundReportWithoutText() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("",
                List.of(ToolUse.of("g1", StructuredResultReport.TOOL_NAME, findings(finding("MEDIUM")))), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(context(null),
                request(policy().maxIterations(1).graceRound(true).build()));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(result.getStructuredResult()).contains(findings(finding("MEDIUM")));
    }

    @Test
    @DisplayName("grace round: a rejected report keeps the one accepted before it")
    void graceRoundRejectionKeepsEarlierReport() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(report("r1", findings(finding("LOW"))));
        llm.responses.add(LlmResponse.of("final",
                List.of(ToolUse.of("g1", StructuredResultReport.TOOL_NAME, Map.of("findings", "broken"))), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(context(null),
                request(policy().maxIterations(1).graceRound(true).build()));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS_GRACE);
        assertThat(result.getStructuredResult()).contains(findings(finding("LOW")));
    }

    @Test
    @DisplayName("a fork that never reports is marked as required, with no payload")
    void missingReport() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.text("prose only"));

        final SubagentExecutionResult result = executor(llm).execute(context(null), request(policy().build()));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.isStructuredResultRequired()).isTrue();
        assertThat(result.getStructuredResult()).isEmpty();
    }

    @Test
    @DisplayName("a report in a response cut at max_tokens is refused like any call in it")
    void truncatedResponseRefusesTheReport() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act",
                List.of(ToolUse.of("r", StructuredResultReport.TOOL_NAME, findings(finding("HIGH")))), USAGE,
                StopReason.MAX_TOKENS));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(context(null), request(policy().build()));

        assertThat(toolResults(result).get(0).get(0).isError()).isTrue();
        assertThat(result.getStructuredResult()).isEmpty();
    }

    @Test
    @DisplayName("grace round cut at max_tokens: its report is not taken, the earlier one stays")
    void truncatedGraceRoundReportIsNotTaken() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(report("r1", findings(finding("LOW"))));
        llm.responses.add(LlmResponse.of("final, cut",
                List.of(ToolUse.of("g1", StructuredResultReport.TOOL_NAME, findings(finding("HIGH")))), USAGE,
                StopReason.MAX_TOKENS));

        final SubagentExecutionResult result = executor(llm).execute(context(null),
                request(policy().maxIterations(1).graceRound(true).build()));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS_GRACE);
        assertThat(result.getStructuredResult()).contains(findings(finding("LOW")));
    }

    @Test
    @DisplayName("a fork whose deadline passed before it started is still marked as owing a structured result")
    void deadlineBeforeStartIsMarkedRequired() {
        final SubagentExecutionPolicy policy = policy().build();
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("review")
                .executionPolicy(policy).deadline(ExecutionDeadline.after(Duration.ofNanos(1))).build();

        final SubagentExecutionResult result = executor(new ScriptedClient()).execute(context(null), request);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
        assertThat(result.isStructuredResultRequired()).isTrue();
    }

    @Test
    @DisplayName("a fork whose goal could not be rendered is still marked as owing a structured result")
    void goalRenderFailureIsMarkedRequired() {
        final SubagentExecutionPolicy policy = policy().build();
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("review")
                .executionPolicy(policy).forkGoalRenderer(environment -> {
                    throw new IllegalStateException("staging failed");
                }).build();

        final SubagentExecutionResult result = executor(new ScriptedClient()).execute(context(null), request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isStructuredResultRequired()).isTrue();
    }

    @Test
    @DisplayName("a registered tool named ReportResult is hidden on every offer, and its name goes to the report tool")
    void registeredToolOfTheSameNameIsHidden() {
        final AtomicInteger shadowRuns = new AtomicInteger();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new CountingTool());
        registry.register(new AbstractTool(StructuredResultReport.TOOL_NAME, "someone else's",
                Map.of("type", "object", "properties", Map.of(), "required", List.of())) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                shadowRuns.incrementAndGet();
                return ToolResult.success("shadow");
            }
        });
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t", CountingTool.NAME, Map.of())), USAGE));
        llm.responses.add(report("r1", findings(finding("HIGH"))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = executor(llm).execute(contextWith(registry), request(policy().build()));

        assertThat(llm.offeredTools).allSatisfy(offered -> assertThat(offered).extracting(ToolDefinition::getName)
                .containsOnlyOnce(StructuredResultReport.TOOL_NAME).last().isEqualTo(StructuredResultReport.TOOL_NAME));
        assertThat(llm.offeredTools).allSatisfy(offered -> assertThat(offered.get(offered.size() - 1).getDescription())
                .isEqualTo(StructuredResultReport.DESCRIPTION));
        assertThat(shadowRuns).hasValue(0);
        assertThat(result.getStructuredResult()).contains(findings(finding("HIGH")));
    }

    // ---- helpers ----

    static SubagentExecutionPolicy.Builder policy() {
        return SubagentExecutionPolicy.builder("review-worker").resultSchema(FINDINGS_SCHEMA);
    }

    static Map<String, Object> finding(String severity) {
        final Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", severity);
        finding.put("category", "correctness");
        finding.put("path", "src/Main.java");
        finding.put("existingCode", "int i = 0;");
        finding.put("body", "off by one");
        return finding;
    }

    @SafeVarargs
    static Map<String, Object> findings(Map<String, Object>... findings) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("findings", List.of(findings));
        return payload;
    }

    private static Map<String, Object> findingsSchema() {
        final Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties",
                Map.of("severity", Map.of("type", "string", "enum", List.of("HIGH", "MEDIUM", "LOW")), "category",
                        Map.of("type", "string"), "path", Map.of("type", "string"), "existingCode",
                        Map.of("type", "string"), "body", Map.of("type", "string")));
        item.put("required", List.of("severity", "category", "path", "body"));
        item.put("additionalProperties", false);
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("findings", Map.of("type", "array", "items", item)));
        schema.put("required", List.of("findings"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static LlmResponse report(String id, Map<String, Object> payload) {
        return LlmResponse.of("reporting", List.of(ToolUse.of(id, StructuredResultReport.TOOL_NAME, payload)), USAGE);
    }

    /** The tool-result batches of the transcript, in order. */
    private static List<List<ToolUseResult>> toolResults(SubagentExecutionResult result) {
        return result.getConversationHistory().stream().filter(m -> !m.getToolUseResults().isEmpty())
                .map(Message::getToolUseResults).toList();
    }

    private static DefaultSubagentExecutor executor(LlmClient llm) {
        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager());
    }

    private static SubagentExecutionContext context(List<String> allowedTools) {
        return context(new CountingTool(), allowedTools);
    }

    private static SubagentExecutionContext contextWith(DefaultToolRegistry registry) {
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1"))
                .subagent(Subagent.of("review-worker",
                        SubagentMetadata.builder().description("d").maxIterations(10).build(),
                        SubagentContent.of("you review")))
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry()).parentCancellationSignal(NoopCancellationSignal.INSTANCE)
                .build();
    }

    private static SubagentExecutionContext context(CountingTool tool, List<String> allowedTools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        final SubagentMetadata.Builder metadata = SubagentMetadata.builder().description("d").maxIterations(10);
        if (allowedTools != null) {
            metadata.allowedTools(allowedTools.stream().map(AllowedTool::parse).toList());
        }
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1"))
                .subagent(Subagent.of("review-worker", metadata.build(), SubagentContent.of("you review")))
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry()).parentCancellationSignal(NoopCancellationSignal.INSTANCE)
                .build();
    }

    private static SubagentExecutionRequest request(SubagentExecutionPolicy policy) {
        return SubagentExecutionRequest.builder().taskId("task-1").goal("review the diff").executionPolicy(policy)
                .budget(policy.toBudget()).build();
    }

    /** Counts the calls it actually runs. */
    private static final class CountingTool extends AbstractTool {
        static final String NAME = "Probe";
        final AtomicInteger runs = new AtomicInteger();

        CountingTool() {
            super(NAME, "counts runs", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            runs.incrementAndGet();
            return ToolResult.success("ok");
        }
    }

    /** Answers from a queue and records what each call was offered and sent. */
    private static final class ScriptedClient implements LlmClient {
        final Deque<LlmResponse> responses = new ArrayDeque<>();
        final List<List<ToolDefinition>> offeredTools = new CopyOnWriteArrayList<>();
        final List<List<Message>> sentMessages = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            offeredTools.add(List.copyOf(tools));
            sentMessages.add(List.copyOf(messages));
            return responses.removeFirst();
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
