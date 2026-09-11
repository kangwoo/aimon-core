package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.StalledIterationGuard;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;

/**
 * A fork stops a streak of iterations that make no progress with the turn's guard, {@link StalledIterationGuard}
 * (#115), and ends as {@link CompletionReason#ERROR}.
 *
 * <p>
 * Mirrors {@code OrcaAgentExecutorStalledIterationTest}: it trips at the threshold, a fork that recovers before the
 * threshold does not trip, a cancellation landing on the would-be third stalled iteration stays a cancellation, and a
 * {@code maxIterations} below the threshold still stops the fork first. Before #115 only the last three held — a fork
 * had no guard, so its failing tools carried on until {@code maxIterations}, 1000 unless the subagent set one.
 */
@DisplayName("DefaultSubagentExecutor stalled-iteration guard")
class DefaultSubagentExecutorStalledIterationTest {

    private static final String SUBAGENT = "explorer";
    private static final TokenUsage USAGE = TokenUsage.of(5, 5, 10);

    /** The turn's text: a streak of failing tools gains no max_tokens clause on a fork either. */
    private static final String PLAIN_STOP_MESSAGE = "Execution aborted: 3 consecutive tool-only iterations made no "
            + "progress (all tool calls failed)";

    @Test
    @DisplayName("three consecutive all-error tool iterations end the fork as ERROR with the turn's stop message")
    void threeConsecutiveFailingIterationsEndTheForkAsError() {
        final StubLlmClient llm = new StubLlmClient();
        for (int i = 1; i <= StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS; i++) {
            llm.responses.add(callTo(FailingTool.TOOL_NAME, "t" + i));
        }
        // Must never be consumed: the guard ends the fork before a fourth LLM call.
        llm.responses.add(LlmResponse.of("never reached", List.of(), USAGE));
        final List<OnStopContext> onStops = new ArrayList<>();
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            onStops.add(context);
            return HookResult.success();
        });

        final SubagentExecutionResult result = execute(llm, registryWith(new FailingTool()), hooks, 10,
                NoopCancellationSignal.INSTANCE);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo("FAILURE");
        assertThat(result.getErrorMessage()).isEqualTo(PLAIN_STOP_MESSAGE);
        assertThat(result.getIterationCount()).isEqualTo(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        assertThat(llm.calls).isEqualTo(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        assertThat(onStops).singleElement().satisfies(context -> {
            assertThat(context.isSuccess()).isFalse();
            assertThat(context.getFinalAnswer()).isEqualTo(PLAIN_STOP_MESSAGE);
        });
    }

    @Test
    @DisplayName("a fork whose tool succeeds before the threshold resets the streak and completes")
    void aForkThatRecoversBeforeTheThresholdCompletes() {
        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(callTo(FailingTool.TOOL_NAME, "t1"));
        llm.responses.add(callTo(FailingTool.TOOL_NAME, "t2"));
        llm.responses.add(callTo(NoopTool.TOOL_NAME, "t3"));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));
        final DefaultToolRegistry registry = registryWith(new FailingTool());
        registry.register(new NoopTool());

        final SubagentExecutionResult result = execute(llm, registry, new DefaultHookRegistry(), 10,
                NoopCancellationSignal.INSTANCE);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(llm.calls).isEqualTo(4);
    }

    @Test
    @DisplayName("a parent cancellation landing on the would-be third stalled iteration ends the fork INTERRUPTED, not ERROR")
    void aCancellationOnTheWouldBeThirdStalledIterationIsInterrupted() {
        // The guard records an iteration only after the fork's iteration-tail cancellation check, which consumes the
        // cancellation and returns INTERRUPTED. The iteration's result is all-error either way, so a guard placed
        // before that check would report a cancellation somebody asked for as a death spiral.
        try (InterruptCoordinator parent = new DefaultInterruptCoordinator()) {
            final StubLlmClient llm = new StubLlmClient();
            llm.responses.add(callTo(FailingTool.TOOL_NAME, "t1"));
            llm.responses.add(callTo(FailingTool.TOOL_NAME, "t2"));
            llm.responses.add(callTo(TrippingFailingTool.TOOL_NAME, "t3"));
            llm.responses.add(LlmResponse.of("never reached", List.of(), USAGE));
            final DefaultToolRegistry registry = registryWith(new FailingTool());
            registry.register(new TrippingFailingTool(parent));

            final SubagentExecutionResult result = execute(llm, registry, new DefaultHookRegistry(), 10,
                    parent.getSignal());

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
            assertThat(result.getErrorMessage()).isEqualTo("Execution interrupted").doesNotContain("no progress");
            assertThat(llm.calls).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("a maxIterations below the threshold still stops the fork first, as MAX_ITERATIONS")
    void aMaxIterationsBelowTheThresholdStopsTheForkFirst() {
        final StubLlmClient llm = new StubLlmClient();
        for (int i = 1; i <= 5; i++) {
            llm.responses.add(callTo(FailingTool.TOOL_NAME, "t" + i));
        }

        final SubagentExecutionResult result = execute(llm, registryWith(new FailingTool()), new DefaultHookRegistry(),
                2, NoopCancellationSignal.INSTANCE);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(result.getErrorMessage()).doesNotContain("no progress");
        assertThat(llm.calls).isEqualTo(2);
    }

    private static LlmResponse callTo(String toolName, String toolUseId) {
        return LlmResponse.of("act", List.of(ToolUse.of(toolUseId, toolName, Map.of())), USAGE);
    }

    private static SubagentExecutionResult execute(LlmClient llm, DefaultToolRegistry registry,
            DefaultHookRegistry hookRegistry, int maxIterations, CancellationSignal parentSignal) {
        final Subagent subagent = Subagent.of(SUBAGENT,
                SubagentMetadata.builder().description("d").maxIterations(maxIterations).build(),
                SubagentContent.of("you are " + SUBAGENT));
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent)
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(hookRegistry).environment(Environment.createDefault())
                .parentCancellationSignal(parentSignal).build();
        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .execute(context, SubagentExecutionRequest.builder().taskId("task-1").goal("go").build());
    }

    private static DefaultToolRegistry registryWith(AbstractTool tool) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        return registry;
    }

    /** Always fails, which is what drives a streak of stalled iterations. */
    private static class FailingTool extends AbstractTool {
        static final String TOOL_NAME = "FailingTool";

        FailingTool() {
            this(TOOL_NAME);
        }

        FailingTool(String name) {
            super(name, "always-failing tool for stalled-iteration tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.error("boom");
        }
    }

    /**
     * Fails like {@link FailingTool}, and trips the parent's signal on the way out — the shape of a tool whose failure
     * was itself caused by a cancellation. Its iteration is all-error and cancelled at the same moment.
     */
    private static final class TrippingFailingTool extends FailingTool {
        static final String TOOL_NAME = "TrippingFailingTool";

        private final InterruptCoordinator parent;

        TrippingFailingTool(InterruptCoordinator parent) {
            super(TOOL_NAME);
            this.parent = parent;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.COOPERATIVE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            parent.requestInterrupt(InterruptReason.USER_SIGINT);
            return ToolResult.error("boom");
        }
    }

    /** Succeeds, which is what resets the streak. */
    private static final class NoopTool extends AbstractTool {
        static final String TOOL_NAME = "NoopTool";

        NoopTool() {
            super(TOOL_NAME, "no-op tool for stalled-iteration tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("noop");
        }
    }

    /** Returns queued responses in order and counts its calls. */
    private static final class StubLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
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
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }
    }
}
