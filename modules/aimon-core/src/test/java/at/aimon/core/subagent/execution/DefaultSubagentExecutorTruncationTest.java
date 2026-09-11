package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.StalledIterationGuard;
import at.aimon.core.agent.budget.TruncatedResponses;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A fork answers a response the provider cut off at {@code max_tokens} the way a turn does (#100).
 *
 * <p>
 * Before #100 the fork never read the stop reason: a final answer cut mid-sentence reached its parent as
 * {@link CompletionReason#COMPLETED}, and a cut tool call ran with whatever arguments had arrived. A cut final answer
 * now ends as {@link CompletionReason#TRUNCATED} with {@link TruncatedResponses#TRUNCATION_MARKER}, and a cut tool
 * response has its calls refused with {@link TruncatedResponses#REFUSED_TOOL_CALL_MESSAGE}. These tests read the same
 * constants {@code OrcaAgentExecutorTruncationTest} reads for a turn.
 */
@DisplayName("DefaultSubagentExecutor max_tokens truncation handling")
class DefaultSubagentExecutorTruncationTest {

    private static final String SUBAGENT = "explorer";
    private static final TokenUsage USAGE = TokenUsage.of(10, 10, 20);

    private final StringBuilder streamed = new StringBuilder();
    private Logger executorLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        executorLogger = (Logger) LoggerFactory.getLogger(DefaultSubagentExecutor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        executorLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        executorLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    @DisplayName("a final answer cut at max_tokens is TRUNCATED, marked, logged and streamed as such — as on a turn")
    void aCutFinalAnswerIsTruncated() {
        // The test #100 sketched: a stubbed client returns text with a truncated stop reason and no tool use.
        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("partial", List.of(), USAGE, StopReason.MAX_TOKENS));

        final SubagentExecutionResult result = execute(llm, new DefaultToolRegistry());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TRUNCATED);
        assertThat(result.getCompletionReason().isSuccessful()).isFalse();
        // success(...), not failure(...): the partial text stays in the summary a parent reads, as on a turn.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("partial" + TruncatedResponses.TRUNCATION_MARKER);
        assertThat(result.getSummary()).isEqualTo(result.getFinalAnswer());
        assertThat(result.getConversationHistory()).filteredOn(message -> message.getRole() == Role.ASSISTANT).last()
                .satisfies(message -> assertThat(message.getContent()).endsWith(TruncatedResponses.TRUNCATION_MARKER));
        assertThat(warnings()).anySatisfy(warning -> assertThat(warning).contains(SUBAGENT).contains("max_tokens"));
        assertThat(streamed()).contains("[completed: TRUNCATED at max_tokens after 1 iterations]")
                .doesNotContain("[completed: SUCCESS");
    }

    @Test
    @DisplayName("a final answer that stopped normally, or reported no stop reason, still completes with no marker")
    void anUncutFinalAnswerStillCompletes() {
        for (LlmResponse response : List.of(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN),
                LlmResponse.of("done", List.of(), USAGE))) {
            final StubLlmClient llm = new StubLlmClient();
            llm.responses.add(response);

            final SubagentExecutionResult result = execute(llm, new DefaultToolRegistry());

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(result.getFinalAnswer()).isEqualTo("done");
        }
        assertThat(warnings()).noneMatch(warning -> warning.contains("max_tokens"));
        assertThat(streamed()).contains("[completed: SUCCESS").doesNotContain("TRUNCATED");
    }

    @Test
    @DisplayName("a tool call in a response cut at max_tokens is not run; the refusal answers it and the fork continues")
    void aCutToolCallIsRefusedAndTheForkContinues() {
        final CountingTool tool = new CountingTool();
        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("acting", List.of(ToolUse.of("t1", CountingTool.TOOL_NAME, Map.of())), USAGE,
                StopReason.MAX_TOKENS));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));

        final SubagentExecutionResult result = execute(llm, registryWith(tool));

        assertThat(tool.invocations).hasValue(0);
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        final List<Message> secondCall = llm.seen.get(1);
        assertThat(secondCall.get(secondCall.size() - 1).getToolUseResults()).singleElement().satisfies(answer -> {
            assertThat(answer.getToolUseId()).isEqualTo("t1");
            assertThat(answer.isError()).isTrue();
            assertThat(answer.getContent()).isEqualTo(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE);
        });
        assertThat(warnings()).anySatisfy(warning -> assertThat(warning).contains(SUBAGENT).contains("max_tokens")
                .contains("iteration 1").contains(CountingTool.TOOL_NAME));
        assertThat(streamed()).contains("→ " + CountingTool.TOOL_NAME)
                .contains("← " + CountingTool.TOOL_NAME + " [error]")
                .contains(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE);
    }

    @Test
    @DisplayName("no PermissionRequest, PreTool or PostTool hook runs for a refused call; the same call uncut reaches all three")
    void noHookRunsForARefusedCall() {
        // The fork's half of what #113's CHANGELOG entry says (#117). The uncut iteration is the positive control.
        final HookCounters hooks = new HookCounters();
        final CountingTool tool = new CountingTool();
        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", CountingTool.TOOL_NAME, Map.of())), USAGE,
                StopReason.MAX_TOKENS));
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t2", CountingTool.TOOL_NAME, Map.of())), USAGE,
                StopReason.TOOL_USE));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));
        final List<List<Integer>> reachedBeforeEachCall = new ArrayList<>();
        llm.beforeEachCall = () -> reachedBeforeEachCall.add(hooks.andTool(tool.invocations));

        final SubagentExecutionResult result = execute(llm, registryWith(tool), hooks.registry);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        // [PermissionRequest, PreTool, PostTool, tool] before each LLM call: after the cut iteration, then the uncut
        // one.
        assertThat(reachedBeforeEachCall).containsExactly(List.of(0, 0, 0, 0), List.of(0, 0, 0, 0),
                List.of(1, 1, 1, 1));
    }

    @Test
    @DisplayName("three cut tool responses in a row trip the stalled-iteration guard and end the fork as ERROR, naming max_tokens")
    void threeCutToolResponsesInARowEndTheForkAsError() {
        // Before #115 a fork had no guard: this one would have carried on to its maxIterations, refusing every time.
        final CountingTool tool = new CountingTool();
        final StubLlmClient llm = new StubLlmClient();
        for (int i = 1; i <= StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS; i++) {
            llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t" + i, CountingTool.TOOL_NAME, Map.of())), USAGE,
                    StopReason.MAX_TOKENS));
        }
        // Must never be consumed: the guard ends the fork before a fourth LLM call.
        llm.responses.add(LlmResponse.of("never reached", List.of(), USAGE, StopReason.END_TURN));

        final SubagentExecutionResult result = execute(llm, registryWith(tool));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getIterationCount()).isEqualTo(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        assertThat(llm.seen).hasSize(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        assertThat(tool.invocations).hasValue(0);
        assertThat(result.getErrorMessage()).endsWith(
                "(all tool calls failed) — each of those responses was cut off at max_tokens, and its tool calls were "
                        + "refused");
        // One WARN per cut response names max_tokens; the guard's own WARN does not.
        assertThat(warnings()).filteredOn(warning -> warning.contains("max_tokens"))
                .hasSize(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        assertThat(warnings()).anySatisfy(
                warning -> assertThat(warning).contains(SUBAGENT).contains("stalled-iteration guard tripped"));
        assertThat(streamed()).contains("[ended: " + result.getErrorMessage() + "]");
    }

    @Test
    @DisplayName("both fork WARNs carry the cut response's reasoning count when it is reported, and no clause when not")
    void theForkWarningsCarryTheReasoningCountOnlyWhenReported() {
        final StubLlmClient reported = new StubLlmClient();
        reported.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", CountingTool.TOOL_NAME, Map.of())),
                TokenUsage.of(10, 4000, 4010, 3990), StopReason.MAX_TOKENS));
        reported.responses
                .add(LlmResponse.of("partial", List.of(), TokenUsage.of(10, 3100, 3110, 3000), StopReason.MAX_TOKENS));

        execute(reported, registryWith(new CountingTool()));

        assertThat(warnings()).filteredOn(warning -> warning.contains("max_tokens")).satisfiesExactly(
                toolCalls -> assertThat(toolCalls)
                        .endsWith("; the response's usage reports 4000 output tokens and 3990 reasoning tokens"),
                finalAnswer -> assertThat(finalAnswer).contains("final answer")
                        .endsWith("; the response's usage reports 3100 output tokens and 3000 reasoning tokens"));

        logAppender.list.clear();
        final StubLlmClient unreported = new StubLlmClient();
        unreported.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", CountingTool.TOOL_NAME, Map.of())),
                TokenUsage.of(10, 4000, 4010), StopReason.MAX_TOKENS));
        unreported.responses
                .add(LlmResponse.of("partial", List.of(), TokenUsage.of(10, 4000, 4010), StopReason.MAX_TOKENS));

        execute(unreported, registryWith(new CountingTool()));

        assertThat(warnings()).filteredOn(warning -> warning.contains("max_tokens")).hasSize(2)
                .noneMatch(warning -> warning.contains("reasoning"));
    }

    private SubagentExecutionResult execute(LlmClient llm, DefaultToolRegistry registry) {
        return execute(llm, registry, new DefaultHookRegistry());
    }

    private SubagentExecutionResult execute(LlmClient llm, DefaultToolRegistry registry,
            DefaultHookRegistry hookRegistry) {
        final SubagentOutputSink sink = text -> {
            synchronized (streamed) {
                streamed.append(text);
            }
        };
        final Subagent subagent = Subagent.of(SUBAGENT,
                SubagentMetadata.builder().description("d").maxIterations(5).build(),
                SubagentContent.of("you are " + SUBAGENT));
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent)
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(hookRegistry).environment(Environment.createDefault()).outputSink(sink).build();
        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .execute(context, SubagentExecutionRequest.builder().taskId("task-1").goal("go").build());
    }

    private String streamed() {
        synchronized (streamed) {
            return streamed.toString();
        }
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static DefaultToolRegistry registryWith(AbstractTool tool) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        return registry;
    }

    /** Counts PermissionRequest, PreTool and PostTool invocations on a hook registry of its own. */
    private static final class HookCounters {
        final AtomicInteger permissionRequest = new AtomicInteger();
        final AtomicInteger preTool = new AtomicInteger();
        final AtomicInteger postTool = new AtomicInteger();
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        HookCounters() {
            registry.register(HookEventType.PERMISSION_REQUEST, (PermissionRequestHook) context -> {
                permissionRequest.incrementAndGet();
                return HookResult.success();
            });
            registry.register(HookEventType.PRE_TOOL, (PreToolHook) context -> {
                preTool.incrementAndGet();
                return HookResult.success();
            });
            registry.register(HookEventType.POST_TOOL, (PostToolHook) context -> {
                postTool.incrementAndGet();
                return HookResult.success();
            });
        }

        /** {@code [PermissionRequest, PreTool, PostTool, tool]} as they read now. */
        List<Integer> andTool(AtomicInteger toolInvocations) {
            return List.of(permissionRequest.get(), preTool.get(), postTool.get(), toolInvocations.get());
        }
    }

    /** Counts its invocations, which is what the refusal test asserts on. */
    private static final class CountingTool extends AbstractTool {
        static final String TOOL_NAME = "Counting";

        final AtomicInteger invocations = new AtomicInteger();

        CountingTool() {
            super(TOOL_NAME, "counts its invocations for truncation tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            invocations.incrementAndGet();
            return ToolResult.success("ran");
        }
    }

    /** Returns queued responses in order and records the messages each call was handed. */
    private static final class StubLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> seen = new ArrayList<>();
        /** Runs at the start of every call, so a test can read what the previous iteration reached. */
        private Runnable beforeEachCall = () -> {
        };

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            beforeEachCall.run();
            seen.add(List.copyOf(messages));
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }
    }
}
