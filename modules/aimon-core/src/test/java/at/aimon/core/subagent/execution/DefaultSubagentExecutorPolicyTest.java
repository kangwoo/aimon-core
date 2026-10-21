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
        final SubagentExecutionResult result = executor(llm).execute(context(registryWith(new CountingTool())),
                request(SubagentExecutionPolicy.builder("verifier").maxIterations(2).build(), null));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(llm.calls).hasValue(2);
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
                request(policyWithWallClock(), ExecutionDeadline.after(Duration.ofMillis(100))));

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
                    request(policyWithWallClock(), ExecutionDeadline.after(Duration.ofMillis(100))));

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
                request(policyWithWallClock(), ExecutionDeadline.after(Duration.ofMillis(100))));

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
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1"))
                .subagent(subagent(10)).defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry()).parentCancellationSignal(parent).build();
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

    /** Answers from a queue and counts its calls. */
    private static class ScriptedClient implements LlmClient {
        final Deque<LlmResponse> responses = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            calls.incrementAndGet();
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
