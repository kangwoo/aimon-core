package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionDeadline;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.context.ContextDecision;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.ContextView;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentExecutionPolicy;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentPolicyCapability;
import at.aimon.core.tools.ToolContextKeys;

/**
 * The fork side of an execution policy: every limit {@link DefaultSubagentExecutor} claims to enforce, enforced, and
 * every context setting reaching the context engine.
 */
@DisplayName("DefaultSubagentExecutor execution policy")
class DefaultSubagentExecutorPolicyTest {

    private static final TokenUsage USAGE = TokenUsage.of(5, 5, 10);

    @Test
    @DisplayName("claims every capability with a compacting engine, and not the context axes with passthrough")
    void enforcesEveryCapability() {
        assertThat(new DefaultSubagentExecutor(LlmCallGateway.<TranscriptBuffer>withDefaultRetry(new ScriptedClient()),
                new DefaultToolExecutionManager(), new DefaultHookExecutionManager(), new RecordingEngine())
                .supportedPolicyCapabilities()).containsExactlyInAnyOrder(SubagentPolicyCapability.values());
        assertThat(executor(new ScriptedClient()).supportedPolicyCapabilities())
                .doesNotContainAnyElementsOf(SubagentPolicyCapability.contextAxes())
                .contains(SubagentPolicyCapability.MAX_TOOL_CALLS, SubagentPolicyCapability.MAX_WALL_CLOCK);
    }

    @Test
    @DisplayName("a batch over the tool-call cap runs only what is left, refuses the rest, and stops before the next call")
    void toolCallCapRunsOnlyWhatIsLeft() {
        final CountingTool tool = new CountingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(calls("a1", "a2"));
        llm.responses.add(calls("b1", "b2", "b3"));
        llm.responses.add(LlmResponse.of("never reached", List.of(), USAGE));
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("verifier").maxToolCalls(3).build();

        final SubagentExecutionResult result = executor(llm).execute(context(registryWith(tool)),
                request(policy, null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TOOL_CALL_BUDGET_EXCEEDED);
        assertThat(tool.runs).hasValue(3);
        assertThat(result.getToolCallCount()).isEqualTo(3);
        assertThat(llm.calls).hasValue(2);
        // The refused calls are answered, so the transcript stays well formed, and they say they were not run.
        final List<ToolUseResult> lastBatch = result.getConversationHistory().stream()
                .filter(m -> !m.getToolUseResults().isEmpty()).reduce((a, b) -> b).orElseThrow().getToolUseResults();
        assertThat(lastBatch).hasSize(3);
        assertThat(lastBatch.get(0).isError()).isFalse();
        assertThat(lastBatch.subList(1, 3)).allSatisfy(r -> {
            assertThat(r.isError()).isTrue();
            assertThat(r.getContent()).startsWith("Not run");
        });
    }

    @Test
    @DisplayName("a fork that uses exactly its tool-call cap may still answer")
    void exactlyTheCapStillCompletes() {
        final CountingTool tool = new CountingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(calls("a1", "a2"));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(context(registryWith(tool)),
                request(SubagentExecutionPolicy.builder("verifier").maxToolCalls(2).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.getToolCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the token cap stops the fork before its next LLM call, after the call that overshot it")
    void tokenCapStopsBeforeTheNextCall() {
        final CountingTool tool = new CountingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())),
                TokenUsage.of(900, 200, 1100)));
        llm.responses.add(LlmResponse.of("never reached", List.of(), USAGE));
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("verifier").maxTokens(1000).build();

        final SubagentExecutionResult result = executor(llm).execute(context(registryWith(tool)),
                request(policy, null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        assertThat(llm.calls).hasValue(1);
        // The response that overshot carried a tool call; it was answered, not run.
        assertThat(tool.runs).hasValue(0);
        assertThat(result.getToolCallCount()).isZero();
        assertThat(result.getMetadata().getTokenUsage().getTotalTokens()).isEqualTo(1100);
    }

    @Test
    @DisplayName("the iteration cap below the subagent's own stops the fork as MAX_ITERATIONS")
    void iterationCapBelowTheSubagents() {
        final ScriptedClient llm = new ScriptedClient();
        for (int i = 0; i < 5; i++) {
            llm.responses.add(calls("t" + i));
        }
        final CountingTool tool = new CountingTool();
        final SubagentExecutionResult result = executor(llm).execute(context(registryWith(tool)),
                request(SubagentExecutionPolicy.builder("verifier").maxIterations(2).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(llm.calls).hasValue(2);
        // The last allowed iteration's tool runs: the iteration cap stops the next iteration, not this one's tools.
        assertThat(tool.runs).hasValue(2);
    }

    @Test
    @DisplayName("grace round: at the policy's iteration cap one more call, with no tool run, writes up the result")
    void graceRoundAtThePolicysIterationCap() {
        final CountingTool tool = new CountingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(calls("t1"));
        llm.responses.add(calls("t2"));
        // The model may still ask for a tool in its final round; the call is dropped and only the text is kept.
        llm.responses.add(LlmResponse.of("FINDING: off-by-one in parse()",
                List.of(ToolUse.of("t3", CountingTool.NAME, Map.of())), USAGE));
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("worker").maxIterations(2)
                .graceRound(true).build();

        final SubagentExecutionResult result = executor(llm).execute(context(registryWith(tool)),
                request(policy, null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS_GRACE);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).isEqualTo("FINDING: off-by-one in parse()");
        assertThat(llm.calls).hasValue(3);
        assertThat(tool.runs).as("the grace round runs no tool").hasValue(2);
        assertThat(result.getToolCallCount()).isEqualTo(2);
        assertThat(result.getIterationCount()).isEqualTo(3);
        assertThat(result.getMetadata().getTokenUsage().getTotalTokens()).isEqualTo(30);
        // Same tool list as before — the prompt-cache prefix holds — and the call opens with the final-round reminder.
        assertThat(llm.offeredTools.get(2)).isEqualTo(llm.offeredTools.get(1));
        final List<Message> graceRequest = llm.sentMessages.get(2);
        assertThat(graceRequest.get(graceRequest.size() - 1).getContent())
                .contains(DefaultSubagentExecutor.GRACE_ROUND_REMINDER_KEY)
                .contains(DefaultSubagentExecutor.GRACE_ROUND_INSTRUCTION);
    }

    @Test
    @DisplayName("grace round: the subagent's own max-iterations gets one too")
    void graceRoundAtTheSubagentsOwnLimit() {
        final CountingTool tool = new CountingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(calls("t1"));
        llm.responses.add(calls("t2"));
        llm.responses.add(LlmResponse.of("partial report", List.of(), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(
                context(registryWith(tool), NoopCancellationSignal.INSTANCE, 2),
                request(SubagentExecutionPolicy.builder("worker").graceRound(true).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS_GRACE);
        assertThat(result.getSummary()).isEqualTo("partial report");
        assertThat(llm.calls).hasValue(3);
    }

    @Test
    @DisplayName("grace round: skipped when the budget would stop the call anyway")
    void graceRoundSkippedWhenTheBudgetIsSpent() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(calls("t1"));
        llm.responses.add(calls("t2"));
        llm.responses.add(LlmResponse.of("never reached", List.of(), USAGE));
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("worker").graceRound(true).build();
        // A request budget the two calls spend exactly: the limit is reached with nothing left for a third.
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("review")
                .executionPolicy(policy)
                .budget(at.aimon.core.agent.budget.ExecutionBudget.builder().maxTokens(20).build()).build();

        final SubagentExecutionResult result = executor(llm)
                .execute(context(registryWith(new CountingTool()), NoopCancellationSignal.INSTANCE, 2), request);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(llm.calls).hasValue(2);
    }

    @Test
    @DisplayName("grace round: no text in it ends the fork MAX_ITERATIONS, without the reminder in its transcript")
    void graceRoundWithoutAnAnswer() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(calls("t1"));
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t2", CountingTool.NAME, Map.of())), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(
                context(registryWith(new CountingTool()), NoopCancellationSignal.INSTANCE, 1),
                request(SubagentExecutionPolicy.builder("worker").graceRound(true).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getIterationCount()).isEqualTo(2);
        assertThat(result.getConversationHistory()).noneSatisfy(
                m -> assertThat(m.getContent()).contains(DefaultSubagentExecutor.GRACE_ROUND_REMINDER_KEY));
    }

    @Test
    @DisplayName("grace round: a call that throws hands back the transcript from before the reminder")
    void graceRoundFailureLeavesNoReminder() {
        final ScriptedClient llm = new ScriptedClient() {
            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig, LlmCallMetadata metadata) {
                if (calls.get() == 1) {
                    calls.incrementAndGet();
                    throw new IllegalStateException("provider blew up");
                }
                return super.sendMessage(systemPrompt, messages, tools, modelConfig, metadata);
            }
        };
        llm.responses.add(calls("t1"));

        final SubagentExecutionResult result = executor(llm).execute(
                context(registryWith(new CountingTool()), NoopCancellationSignal.INSTANCE, 1),
                request(SubagentExecutionPolicy.builder("worker").graceRound(true).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(llm.calls).hasValue(2);
        assertThat(result.getConversationHistory()).isNotEmpty().noneSatisfy(
                m -> assertThat(m.getContent()).contains(DefaultSubagentExecutor.GRACE_ROUND_REMINDER_KEY));
    }

    @Test
    @DisplayName("without a grace round the iteration limit ends the fork as before")
    void noGraceRoundWithoutThePolicy() {
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(calls("t1"));
        llm.responses.add(LlmResponse.of("never reached", List.of(), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(
                context(registryWith(new CountingTool()), NoopCancellationSignal.INSTANCE, 1),
                request(SubagentExecutionPolicy.builder("worker").maxToolCalls(5).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(llm.calls).hasValue(1);
    }

    @Test
    @DisplayName("a tool that failed the policy's limit of times in a row is not run again; others still run")
    void failureStreakStopsRunningTheTool() {
        final ScriptedOutcomeTool flaky = new ScriptedOutcomeTool(false, false);
        final CountingTool other = new CountingTool();
        final DefaultToolRegistry registry = registryWith(flaky);
        registry.register(other);
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(flakyCall("f1"));
        llm.responses.add(flakyCall("f2"));
        llm.responses.add(LlmResponse.of("again", List.of(ToolUse.of("f3", ScriptedOutcomeTool.NAME, Map.of()),
                ToolUse.of("p1", CountingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("done without it", List.of(), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(context(registry),
                request(SubagentExecutionPolicy.builder("worker").maxConsecutiveToolFailures(2).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(flaky.runs).hasValue(2);
        assertThat(other.runs).hasValue(1);
        // A refused call is not run, so it takes none of the tool-call count.
        assertThat(result.getToolCallCount()).isEqualTo(3);
        final List<ToolUseResult> third = result.getConversationHistory().stream()
                .filter(m -> !m.getToolUseResults().isEmpty()).toList().get(2).getToolUseResults();
        assertThat(third.get(0).isError()).isTrue();
        assertThat(third.get(0).getContent()).startsWith("Not run: " + ScriptedOutcomeTool.NAME + " failed 2");
        assertThat(third.get(1).isError()).isFalse();
    }

    @Test
    @DisplayName("a blocked call and a run call sharing a tool-use id each get their own answer, by position")
    void blockedCallsAreMatchedByPosition() {
        final ScriptedOutcomeTool flaky = new ScriptedOutcomeTool(false);
        final CountingTool other = new CountingTool();
        final DefaultToolRegistry registry = registryWith(flaky);
        registry.register(other);
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(flakyCall("f1"));
        llm.responses.add(LlmResponse.of("again", List.of(ToolUse.of("dup", ScriptedOutcomeTool.NAME, Map.of()),
                ToolUse.of("dup", CountingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(context(registry),
                request(SubagentExecutionPolicy.builder("worker").maxConsecutiveToolFailures(1).build(), null));

        final List<ToolUseResult> second = result.getConversationHistory().stream()
                .filter(m -> !m.getToolUseResults().isEmpty()).toList().get(1).getToolUseResults();
        assertThat(second).hasSize(2);
        assertThat(second.get(0).getContent()).startsWith("Not run");
        assertThat(second.get(1).isError()).isFalse();
        assertThat(other.runs).hasValue(1);
    }

    @Test
    @DisplayName("a success resets the tool's failure streak")
    void successResetsTheStreak() {
        final ScriptedOutcomeTool flaky = new ScriptedOutcomeTool(false, true, false, true);
        final ScriptedClient llm = new ScriptedClient();
        for (int i = 0; i < 4; i++) {
            llm.responses.add(flakyCall("f" + i));
        }
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(context(registryWith(flaky)),
                request(SubagentExecutionPolicy.builder("worker").maxConsecutiveToolFailures(2).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(flaky.runs).hasValue(4);
    }

    private static LlmResponse flakyCall(String id) {
        return LlmResponse.of("try", List.of(ToolUse.of(id, ScriptedOutcomeTool.NAME, Map.of())), USAGE);
    }

    @Test
    @DisplayName("a failure the tool reports as not retryable blocks the tool at once, below the policy's limit")
    void notRetryableFailureBlocksAtOnce() {
        final UnchangingFailureTool sized = new UnchangingFailureTool();
        final CountingTool other = new CountingTool();
        final DefaultToolRegistry registry = registryWith(sized);
        registry.register(other);
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(unchangingCall("u1"));
        llm.responses.add(LlmResponse.of("again", List.of(ToolUse.of("u2", UnchangingFailureTool.NAME, Map.of()),
                ToolUse.of("p1", CountingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("done without it", List.of(), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(context(registry),
                request(SubagentExecutionPolicy.builder("worker").maxConsecutiveToolFailures(3).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(sized.runs).hasValue(1);
        assertThat(other.runs).hasValue(1);
        assertThat(result.getToolCallCount()).isEqualTo(2);
        final List<List<ToolUseResult>> batches = toolResultBatches(result);
        // The model read the tool's own words the first time; the mark reached the executor through the real pipeline.
        assertThat(batches.get(0).get(0).getContent()).isEqualTo(UnchangingFailureTool.MESSAGE);
        assertThat(batches.get(0).get(0).isRetryable()).isFalse();
        assertThat(batches.get(1).get(0).isError()).isTrue();
        assertThat(batches.get(1).get(0).getContent()).startsWith(
                "Not run: " + UnchangingFailureTool.NAME + " reported its failure as not retryable in this task");
        assertThat(batches.get(1).get(1).isError()).isFalse();
    }

    @Test
    @DisplayName("without the failure-streak axis a not-retryable failure changes nothing: the tool keeps running")
    void notRetryableIsInertWithoutTheAxis() {
        for (SubagentExecutionPolicy policy : new SubagentExecutionPolicy[]{null,
                SubagentExecutionPolicy.builder("worker").maxToolCalls(10).build()}) {
            final UnchangingFailureTool sized = new UnchangingFailureTool();
            final ScriptedClient llm = new ScriptedClient();
            // Two, not three: three all-error iterations in a row would trip the StalledIterationGuard.
            llm.responses.add(unchangingCall("u1"));
            llm.responses.add(unchangingCall("u2"));
            llm.responses.add(LlmResponse.of("done", List.of(), USAGE));

            final SubagentExecutionResult result = executor(llm).execute(context(registryWith(sized)),
                    request(policy, null));

            assertThat(result.getCompletionReason()).as("policy %s", policy).isEqualTo(CompletionReason.COMPLETED);
            assertThat(sized.runs).as("policy %s", policy).hasValue(2);
            assertThat(toolResultBatches(result)).allSatisfy(
                    batch -> assertThat(batch.get(0).getContent()).isEqualTo(UnchangingFailureTool.MESSAGE));
        }
    }

    @Test
    @DisplayName("the mark survives the pipeline's rewrites: a PostTool output rewrite and appended hook feedback")
    void notRetryableSurvivesHookRewrites() {
        final UnchangingFailureTool sized = new UnchangingFailureTool();
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        // A masking hook that rebuilds the error from scratch, without the mark, and one that only adds a note.
        hooks.register(HookEventType.POST_TOOL,
                ctx -> HookResult.withUpdatedOutput(ToolResult.error("[masked] " + ctx.currentOutput().getContent())));
        hooks.register(HookEventType.POST_TOOL, ctx -> HookResult.withFeedback("audited"));
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(unchangingCall("u1"));
        llm.responses.add(unchangingCall("u2"));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));

        final SubagentExecutionResult result = executor(llm).execute(context(registryWith(sized), hooks),
                request(SubagentExecutionPolicy.builder("worker").maxConsecutiveToolFailures(5).build(), null));

        assertThat(sized.runs).hasValue(1);
        final List<List<ToolUseResult>> batches = toolResultBatches(result);
        assertThat(batches.get(0).get(0).getContent()).startsWith("[masked] " + UnchangingFailureTool.MESSAGE)
                .contains("audited");
        assertThat(batches.get(1).get(0).getContent()).contains("reported its failure as not retryable");
    }

    @Test
    @DisplayName("a PostTool hook may mark an error not retryable itself")
    void aHookMayMarkAFailureNotRetryable() {
        final ScriptedOutcomeTool flaky = new ScriptedOutcomeTool(false, false);
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.POST_TOOL,
                ctx -> ctx.currentOutput().isError()
                        ? HookResult.withUpdatedOutput(ctx.currentOutput().notRetryable())
                        : HookResult.success());
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(flakyCall("f1"));
        llm.responses.add(flakyCall("f2"));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));

        executor(llm).execute(context(registryWith(flaky), hooks),
                request(SubagentExecutionPolicy.builder("worker").maxConsecutiveToolFailures(5).build(), null));

        assertThat(flaky.runs).hasValue(1);
    }

    @Test
    @DisplayName("a not-retryable block is sticky: a success of the same tool in the same batch, before or after, lifts nothing")
    void notRetryableBlockIsStickyWhateverTheBatchOrder() {
        for (Boolean[] batchOutcomes : List.of(new Boolean[]{false, true}, new Boolean[]{true, false})) {
            // Every error it returns is marked not retryable (the mark is a no-op on its successes).
            final ScriptedOutcomeTool mixed = new ScriptedOutcomeTool(batchOutcomes) {
                @Override
                public ToolResult execute(ToolInput input, ToolContext context) {
                    return super.execute(input, context).notRetryable();
                }
            };
            final ScriptedClient llm = new ScriptedClient();
            llm.responses.add(LlmResponse.of("try", List.of(ToolUse.of("a", ScriptedOutcomeTool.NAME, Map.of()),
                    ToolUse.of("b", ScriptedOutcomeTool.NAME, Map.of())), USAGE));
            llm.responses.add(flakyCall("c"));
            llm.responses.add(LlmResponse.of("done", List.of(), USAGE));

            // A limit the count alone would not reach: only the mark can block.
            final SubagentExecutionResult result = executor(llm).execute(context(registryWith(mixed)),
                    request(SubagentExecutionPolicy.builder("worker").maxConsecutiveToolFailures(5).build(), null));

            final String order = java.util.Arrays.toString(batchOutcomes);
            assertThat(mixed.runs).as("batch %s", order).hasValue(2);
            assertThat(toolResultBatches(result).get(1).get(0).getContent()).as("batch %s", order)
                    .contains("reported its failure as not retryable");
        }
    }

    private static LlmResponse unchangingCall(String id) {
        return LlmResponse.of("try", List.of(ToolUse.of(id, UnchangingFailureTool.NAME, Map.of())), USAGE);
    }

    private static List<List<ToolUseResult>> toolResultBatches(SubagentExecutionResult result) {
        return result.getConversationHistory().stream().filter(m -> !m.getToolUseResults().isEmpty())
                .map(Message::getToolUseResults).toList();
    }

    @Test
    @DisplayName("a deadline that passed before the fork started runs nothing")
    void deadlinePassedBeforeStart() {
        final AtomicLong now = new AtomicLong();
        final ExecutionDeadline deadline = ExecutionDeadline.after(Duration.ofSeconds(1), now::get);
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.text("never reached"));

        final SubagentExecutionResult result = executor(llm).execute(context(new DefaultToolRegistry()), request(
                SubagentExecutionPolicy.builder("verifier").maxWallClock(Duration.ofSeconds(1)).build(), deadline));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(llm.calls).hasValue(0);
    }

    @Test
    @DisplayName("a deadline passing during a blocking LLM call aborts it and reports WALL_CLOCK_EXCEEDED, not INTERRUPTED")
    void deadlineAbortsABlockingCall() {
        final BlockingClient llm = new BlockingClient();

        final SubagentExecutionResult result = executor(llm).execute(context(new DefaultToolRegistry()),
                request(policyWithWallClock(), ExecutionDeadline.after(Duration.ofSeconds(1))));

        assertThat(llm.aborted.getCount()).isZero();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
        assertThat(result.getErrorMessage()).contains("deadline");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("a parent cancellation that lands first keeps its reason when the deadline passes after it")
    void parentCancellationFirstStaysInterrupted() {
        try (InterruptCoordinator parent = new DefaultInterruptCoordinator()) {
            final AtomicLong now = new AtomicLong();
            final ExecutionDeadline deadline = ExecutionDeadline.after(Duration.ofHours(1), now::get);
            final ScriptedClient llm = new ScriptedClient() {
                @Override
                public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                        LlmModel modelConfig, LlmCallMetadata metadata) {
                    parent.requestInterrupt(InterruptReason.USER_SIGINT);
                    now.addAndGet(Duration.ofHours(2).toNanos());
                    return super.sendMessage(systemPrompt, messages, tools, modelConfig, metadata);
                }
            };
            llm.responses.add(calls("t1"));
            llm.responses.add(LlmResponse.text("never reached"));

            final SubagentExecutionResult result = executor(llm).execute(
                    context(registryWith(new CountingTool()), parent.getSignal()),
                    request(policyWithWallClock(), deadline));

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        }
    }

    @Test
    @DisplayName("a deadline that lands first keeps its reason when the parent cancels after it")
    void deadlineFirstStaysWallClock() {
        try (InterruptCoordinator parent = new DefaultInterruptCoordinator()) {
            final BlockingClient llm = new BlockingClient(() -> parent.requestInterrupt(InterruptReason.USER_SIGINT));

            final SubagentExecutionResult result = executor(llm).execute(
                    context(new DefaultToolRegistry(), parent.getSignal()),
                    request(policyWithWallClock(), ExecutionDeadline.after(Duration.ofSeconds(1))));

            assertThat(parent.getSignal().isCancelled()).isTrue();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
        }
    }

    @Test
    @DisplayName("a child cancelled by its parent's deadline, which it shares, reports WALL_CLOCK_EXCEEDED")
    void sharedParentDeadlineIsTheChildsDeadline() {
        try (InterruptCoordinator parent = new DefaultInterruptCoordinator()) {
            final AtomicLong now = new AtomicLong();
            final ExecutionDeadline deadline = ExecutionDeadline.after(Duration.ofHours(1), now::get);
            final ScriptedClient llm = new ScriptedClient() {
                @Override
                public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                        LlmModel modelConfig, LlmCallMetadata metadata) {
                    // The parent's timer fires first at the shared instant and cascades into the child.
                    now.addAndGet(Duration.ofHours(2).toNanos());
                    parent.requestInterrupt(InterruptReason.BUDGET_EXCEEDED);
                    return super.sendMessage(systemPrompt, messages, tools, modelConfig, metadata);
                }
            };
            llm.responses.add(calls("t1"));

            final SubagentExecutionResult result = executor(llm).execute(
                    context(registryWith(new CountingTool()), parent.getSignal()),
                    request(policyWithWallClock(), deadline));

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
        }
    }

    @Test
    @DisplayName("a deadline passing during a blocking tool interrupts it and leaves no interrupt behind")
    void deadlineInterruptsABlockingTool() {
        final BlockingTool tool = new BlockingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", BlockingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.text("never reached"));

        final SubagentExecutionResult result = executor(llm).execute(context(registryWith(tool)),
                request(policyWithWallClock(), ExecutionDeadline.after(Duration.ofSeconds(1))));

        assertThat(tool.interrupted.getCount()).isZero();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
        assertThat(llm.calls).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("the fork publishes its deadline and context engine to its tools")
    void publishesDeadlineAndEngine() {
        final CountingTool tool = new CountingTool();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(calls("t1"));
        llm.responses.add(LlmResponse.text("done"));
        final ExecutionDeadline deadline = ExecutionDeadline.after(Duration.ofHours(1));
        final RecordingEngine engine = new RecordingEngine();

        executor(llm).execute(SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1"))
                .subagent(subagent(10)).defaultModel(LlmModel.builder().name("gpt-4").build())
                .toolRegistry(registryWith(tool)).hookRegistry(new DefaultHookRegistry()).contextEngine(engine).build(),
                request(policyWithWallClock(), deadline));

        assertThat(tool.lastContext.get(ToolContextKeys.EXECUTION_DEADLINE)).containsSame(deadline);
        assertThat(tool.lastContext.get(ToolContextKeys.CONTEXT_ENGINE)).containsSame(engine);
        assertThat(engine.requests).isNotEmpty();
    }

    @Test
    @DisplayName("the compaction threshold and the context target each mark the next context request budgetForced")
    void softHintsReachTheEngine() {
        final RecordingEngine engine = new RecordingEngine();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())),
                TokenUsage.of(50, 10, 60)));
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t2", CountingTool.NAME, Map.of())),
                TokenUsage.of(50, 10, 60)));
        llm.responses.add(LlmResponse.text("done"));
        // The cumulative threshold (100) is crossed after the second call, not the first.
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("verifier").compactionTokenThreshold(100)
                .compactionInstructions("keep finding ids and pinned SHAs").build();

        run(engine, llm, policy);

        assertThat(engine.requests).extracting(ContextRequest::isBudgetForced).containsExactly(false, false, true);
        assertThat(engine.requests).allSatisfy(
                r -> assertThat(r.getCompactionInstructions()).contains("keep finding ids and pinned SHAs"));

        final RecordingEngine targetEngine = new RecordingEngine();
        final ScriptedClient targetLlm = new ScriptedClient();
        targetLlm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())),
                TokenUsage.of(4_000, 10, 4_010)));
        targetLlm.responses.add(LlmResponse.text("done"));

        run(targetEngine, targetLlm, SubagentExecutionPolicy.builder("verifier").contextTargetTokens(4_000).build());

        assertThat(targetEngine.requests).extracting(ContextRequest::isBudgetForced).containsExactly(false, true);
    }

    @Test
    @DisplayName("without a policy no request is budgetForced and none carries instructions")
    void noPolicyNoHints() {
        final RecordingEngine engine = new RecordingEngine();
        final ScriptedClient llm = new ScriptedClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())),
                TokenUsage.of(50_000, 10, 50_010)));
        llm.responses.add(LlmResponse.text("done"));

        run(engine, llm, null);

        assertThat(engine.requests).extracting(ContextRequest::isBudgetForced).containsOnly(false);
        assertThat(engine.requests).allSatisfy(r -> assertThat(r.getCompactionInstructions()).isEmpty());
    }

    private static void run(RecordingEngine engine, ScriptedClient llm, SubagentExecutionPolicy policy) {
        new DefaultSubagentExecutor(LlmCallGateway.<TranscriptBuffer>withDefaultRetry(llm),
                new DefaultToolExecutionManager(), new DefaultHookExecutionManager(), engine)
                .execute(context(registryWith(new CountingTool())), request(policy, null));
    }

    private static SubagentExecutionPolicy policyWithWallClock() {
        return SubagentExecutionPolicy.builder("verifier").maxWallClock(Duration.ofHours(1)).build();
    }

    private static LlmResponse calls(String... ids) {
        return LlmResponse.of("act",
                java.util.Arrays.stream(ids).map(id -> ToolUse.of(id, CountingTool.NAME, Map.of())).toList(), USAGE);
    }

    private static DefaultSubagentExecutor executor(LlmClient llm) {
        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager());
    }

    private static Subagent subagent(int maxIterations) {
        return Subagent.of("verifier", SubagentMetadata.builder().description("d").maxIterations(maxIterations).build(),
                SubagentContent.of("you verify"));
    }

    private static SubagentExecutionContext context(DefaultToolRegistry registry) {
        return context(registry, NoopCancellationSignal.INSTANCE);
    }

    private static SubagentExecutionContext context(DefaultToolRegistry registry, CancellationSignal parent) {
        return context(registry, parent, 10);
    }

    private static SubagentExecutionContext context(DefaultToolRegistry registry, CancellationSignal parent,
            int maxIterations) {
        return context(registry, new DefaultHookRegistry(), parent, maxIterations);
    }

    private static SubagentExecutionContext context(DefaultToolRegistry registry, DefaultHookRegistry hooks) {
        return context(registry, hooks, NoopCancellationSignal.INSTANCE, 10);
    }

    private static SubagentExecutionContext context(DefaultToolRegistry registry, DefaultHookRegistry hooks,
            CancellationSignal parent, int maxIterations) {
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1"))
                .subagent(subagent(maxIterations)).defaultModel(LlmModel.builder().name("gpt-4").build())
                .toolRegistry(registry).hookRegistry(hooks).parentCancellationSignal(parent).build();
    }

    private static SubagentExecutionRequest request(SubagentExecutionPolicy policy, ExecutionDeadline deadline) {
        return SubagentExecutionRequest.builder().taskId("task-1").goal("verify the finding").executionPolicy(policy)
                .budget(policy == null ? null : policy.toBudget()).deadline(deadline).build();
    }

    private static DefaultToolRegistry registryWith(AbstractTool tool) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        return registry;
    }

    /** Fails every call with an error it marks not retryable, counting the calls. */
    private static final class UnchangingFailureTool extends AbstractTool {
        static final String NAME = "Fetch";
        static final String MESSAGE = "SIZE_LIMIT: the source is larger than 10 MB";
        final AtomicInteger runs = new AtomicInteger();

        UnchangingFailureTool() {
            super(NAME, "always too large", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            runs.incrementAndGet();
            return ToolResult.error(MESSAGE).notRetryable();
        }
    }

    /** Answers each call it runs with the next scripted outcome (true = success), counting the calls. */
    private static class ScriptedOutcomeTool extends AbstractTool {
        static final String NAME = "Flaky";
        final Deque<Boolean> outcomes = new ArrayDeque<>();
        final AtomicInteger runs = new AtomicInteger();

        ScriptedOutcomeTool(Boolean... outcomes) {
            super(NAME, "fails on cue", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            runs.incrementAndGet();
            return outcomes.removeFirst() ? ToolResult.success("ok") : ToolResult.error("SIZE_LIMIT: too large");
        }
    }

    /** Counts the calls it actually runs and keeps the last context it saw. */
    private static final class CountingTool extends AbstractTool {
        static final String NAME = "Probe";
        final AtomicInteger runs = new AtomicInteger();
        volatile ToolContext lastContext;

        CountingTool() {
            super(NAME, "counts runs", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            runs.incrementAndGet();
            lastContext = context;
            return ToolResult.success("ok");
        }
    }

    /** Answers from a queue, counts its calls and records what each was sent. */
    private static class ScriptedClient implements LlmClient {
        final Deque<LlmResponse> responses = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<List<String>> offeredTools = new CopyOnWriteArrayList<>();
        final List<List<Message>> sentMessages = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            calls.incrementAndGet();
            offeredTools.add(tools.stream().map(ToolDefinition::getName).toList());
            sentMessages.add(List.copyOf(messages));
            return responses.removeFirst();
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }

    /** Blocks on a latch until it is interrupted; declares THREAD_INTERRUPT so the deadline's terminators reach it. */
    private static final class BlockingTool extends AbstractTool {
        static final String NAME = "Blocker";
        final CountDownLatch interrupted = new CountDownLatch(1);

        BlockingTool() {
            super(NAME, "blocks", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public at.aimon.core.agent.interrupt.InterruptBehavior getInterruptBehavior() {
            return at.aimon.core.agent.interrupt.InterruptBehavior.THREAD_INTERRUPT;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            try {
                new CountDownLatch(1).await(30, TimeUnit.SECONDS);
                return ToolResult.error("never interrupted");
            } catch (InterruptedException e) {
                interrupted.countDown();
                return ToolResult.error("interrupted");
            }
        }
    }

    /** Blocks until its call is aborted through the cancellation token, then reports the abort. */
    private static final class BlockingClient implements LlmClient {
        final CountDownLatch aborted = new CountDownLatch(1);
        private final Runnable afterAbort;

        BlockingClient() {
            this(() -> {
            });
        }

        BlockingClient(Runnable afterAbort) {
            this.afterAbort = afterAbort;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new AssertionError("the cancellable overload is expected");
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            cancellation.onCancel(aborted::countDown);
            try {
                if (!aborted.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("never aborted");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            afterAbort.run();
            throw new LlmCallCancelledException("aborted");
        }

        @Override
        public String getProviderName() {
            return "Blocking";
        }
    }

    /** Hands back the transcript as it is and records every request. */
    private static final class RecordingEngine implements ContextEngine {
        final List<ContextRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public ContextDecision prepare(ContextRequest request) {
            requests.add(request);
            return ContextDecision.builder()
                    .view(ContextView.of(List.copyOf(request.getTranscriptBuffer().getMessages())))
                    .action(CompactionDecision.Action.NONE).reason("recorded").build();
        }

        @Override
        public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
            return Optional.empty();
        }

        @Override
        public CompactionResult compactNow(ContextRequest request, String instructions) {
            throw new AssertionError("not called");
        }
    }
}
