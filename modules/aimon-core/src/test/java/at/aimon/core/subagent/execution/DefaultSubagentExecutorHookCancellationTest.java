package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.context.ContextDecision;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.ContextView;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;

/**
 * EE-80 on the fork path: a fork's {@code onStop} is handed the fork's own cancellation signal at each of its three
 * sites, a cancelled fork's {@code onStop} is handed none (so its command still starts), and the compaction gate
 * carries the same signal — a BLOCK that follows a cancellation ends the fork as interrupted.
 */
@DisplayName("DefaultSubagentExecutor: onStop and the compaction gate carry the fork's cancellation signal (EE-80)")
class DefaultSubagentExecutorHookCancellationTest {

    private static final TokenUsage USAGE = TokenUsage.of(5, 5, 10);

    private final StubLlmClient llm = new StubLlmClient();
    private final DefaultHookRegistry hooks = new DefaultHookRegistry();
    private final List<Optional<CancellationSignal>> onStartSignals = new ArrayList<>();
    private final List<Optional<CancellationSignal>> onStopSignals = new ArrayList<>();
    private final DefaultInterruptCoordinator spawner = new DefaultInterruptCoordinator();

    DefaultSubagentExecutorHookCancellationTest() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> {
            onStartSignals.add(context.getExecutionCancellation());
            return HookResult.success();
        });
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            // Read while the hook runs: a report context answers for the moment the work starts.
            onStopSignals.add(context.getExecutionCancellation());
            return HookResult.success();
        });
    }

    @Test
    @DisplayName("the success onStop carries the fork's signal")
    void successOnStop() {
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));

        final SubagentExecutionResult result = execute(null, 10);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertOnStopCarriesTheForksLiveSignal();
    }

    @Test
    @DisplayName("the onStop of a fork whose final answer was cut at max_tokens carries the fork's signal")
    void truncatedOnStop() {
        llm.responses.add(LlmResponse.of("partial", List.of(), USAGE, StopReason.MAX_TOKENS));

        final SubagentExecutionResult result = execute(null, 10);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TRUNCATED);
        assertOnStopCarriesTheForksLiveSignal();
    }

    @Test
    @DisplayName("the failure onStop of a fork that was not cancelled carries the fork's signal")
    void failureOnStop() {
        final ContextEngine blocking = gate(
                request -> ContextDecision.from(CompactionDecision.block("over the blocking limit", 9_900, 9_500),
                        ContextView.of(request.getTranscriptBuffer().getMessages())));

        final SubagentExecutionResult result = execute(blocking, 10);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(result.getErrorMessage()).contains("Context window exceeded");
        assertOnStopCarriesTheForksLiveSignal();
    }

    @Test
    @DisplayName("a cancelled fork's onStop is handed no signal, so its command still starts")
    void cancelledForkOnStop() {
        llm.beforeAnswering = () -> spawner.requestInterrupt(InterruptReason.USER_SIGINT);
        // A tool call, so the loop reaches its tail checkpoint: a final answer would end the fork as completed.
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", "NoSuchTool", Map.of())), USAGE));

        final SubagentExecutionResult result = execute(null, 10);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(onStopSignals).singleElement().satisfies(signal -> assertThat(signal).isEmpty());
    }

    @Test
    @DisplayName("the compaction gate carries the fork's signal, and a BLOCK after a cancellation is the cancellation")
    void compactionBlockedAfterACancellation() {
        final List<Optional<CancellationSignal>> gateSignals = new ArrayList<>();
        // Stands in for an engine whose preCompact guard was cancelled: the compaction is skipped and the view is
        // over the blocking limit.
        final ContextEngine blockingAfterCancel = gate(request -> {
            gateSignals.add(request.getExecutionCancellation());
            spawner.requestInterrupt(InterruptReason.USER_SIGINT);
            return ContextDecision.from(CompactionDecision.block("over the blocking limit", 9_900, 9_500),
                    ContextView.of(request.getTranscriptBuffer().getMessages()));
        });

        final SubagentExecutionResult result = execute(blockingAfterCancel, 10);

        assertThat(gateSignals).singleElement().isEqualTo(onStartSignals.get(0));
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(result.getErrorMessage()).doesNotContain("Context window exceeded");
        assertThat(llm.calls).isZero();
    }

    private void assertOnStopCarriesTheForksLiveSignal() {
        final CancellationSignal forkSignal = onStartSignals.get(0).orElseThrow();
        assertThat(onStopSignals).singleElement()
                .satisfies(signal -> assertThat(signal.orElseThrow()).isSameAs(forkSignal));
    }

    private SubagentExecutionResult execute(ContextEngine contextEngine, int maxIterations) {
        final Subagent subagent = Subagent.of("explorer",
                SubagentMetadata.builder().description("d").maxIterations(maxIterations).build(),
                SubagentContent.of("you are explorer"));
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent)
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(hooks).parentCancellationSignal(spawner.getSignal()).build();
        final DefaultSubagentExecutor executor = contextEngine == null
                ? new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                : new DefaultSubagentExecutor(LlmCallGateway.<TranscriptBuffer>withDefaultRetry(llm),
                        new DefaultToolExecutionManager(), new DefaultHookExecutionManager(), contextEngine);
        return executor.execute(context, SubagentExecutionRequest.builder().taskId("task-1").goal("go").build());
    }

    private static ContextEngine gate(Function<ContextRequest, ContextDecision> gate) {
        return new ContextEngine() {
            @Override
            public ContextDecision prepare(ContextRequest request) {
                return gate.apply(request);
            }

            @Override
            public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
                return Optional.empty();
            }

            @Override
            public CompactionResult compactNow(ContextRequest request, String instructions) {
                throw new UnsupportedOperationException("not under test");
            }
        };
    }

    /** Returns queued responses in order, counting calls. */
    private static final class StubLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private Runnable beforeAnswering = () -> {
        };
        private int calls;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            calls++;
            beforeAnswering.run();
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }
    }
}
