package at.aimon.core.skill.execution.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.budget.StalledIterationGuard;
import at.aimon.core.agent.budget.TruncatedResponses;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.execution.SkillToolDispatcher;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.tools.ToolContextKeys;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A skill's own tool loop answers a response cut off at {@code max_tokens} the way both agent executors do (#115), and
 * shares their stalled-iteration guard.
 *
 * <p>
 * Before #115 this loop never read the stop reason: a cut tool call ran with whatever arguments had arrived, and a cut
 * final answer came back as a plain success. Now a cut response's calls are refused with
 * {@link TruncatedResponses#REFUSED_TOOL_CALL_MESSAGE} on both dispatch paths, a cut final answer ends with
 * {@link TruncatedResponses#TRUNCATION_MARKER}, and {@link StalledIterationGuard#MAX_CONSECUTIVE_STALLED_ITERATIONS}
 * iterations whose calls all failed end the skill. A skill result has no completion reason, so the marker in
 * {@link SkillExecutionResult#getResponse()} is how a cut answer shows — the shape a fork-mode skill already had.
 */
@DisplayName("LlmSkillExecutor max_tokens truncation and stalled-iteration handling")
class LlmSkillExecutorTruncationTest {

    private static final String SKILL = "audit";
    private static final TokenUsage USAGE = TokenUsage.of(10, 10, 20);
    private static final String PLAIN_STOP_MESSAGE = "Execution aborted: 3 consecutive tool-only iterations made no "
            + "progress (all tool calls failed)";
    private static final String MAX_TOKENS_CLAUSE = " — each of those responses was cut off at max_tokens, and its "
            + "tool calls were refused";

    private final QueueLlmClient llm = new QueueLlmClient();
    private final LlmSkillExecutor executor = new LlmSkillExecutor(llm, new DefaultSkillContentRenderer(),
            new DefaultToolExecutionManager());

    private Logger executorLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        executorLogger = (Logger) LoggerFactory.getLogger(LlmSkillExecutor.class);
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
    @DisplayName("fallback path: a tool call in a response cut at max_tokens is not run; the refusal answers it and the skill continues")
    void aCutToolCallIsRefusedOnTheFallbackPath() {
        final CountingTool tool = new CountingTool();
        llm.responses.add(LlmResponse.of("acting", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())), USAGE,
                StopReason.MAX_TOKENS));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));

        final SkillExecutionResult result = execute(ToolContext.empty(), tool);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("done");
        assertThat(tool.executions).isZero();
        assertThat(lastToolResults(llm.seen.get(1))).singleElement().satisfies(answer -> {
            assertThat(answer.getToolUseId()).isEqualTo("t1");
            assertThat(answer.isError()).isTrue();
            assertThat(answer.getContent()).isEqualTo(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE);
        });
        assertThat(warnings()).anySatisfy(warning -> assertThat(warning).contains("Skill '" + SKILL + "'")
                .contains("max_tokens").contains("iteration 1").contains(CountingTool.NAME));
    }

    @Test
    @DisplayName("dispatcher path: the bound dispatcher is never handed a cut response's calls, and is handed an uncut one's")
    void aCutToolCallNeverReachesTheBoundDispatcher() {
        // The dispatcher is where the hooks and the approval gate stand, so a refusal must be answered before it.
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())), USAGE,
                StopReason.MAX_TOKENS));
        llm.responses.add(
                LlmResponse.of("", List.of(ToolUse.of("t2", CountingTool.NAME, Map.of())), USAGE, StopReason.TOOL_USE));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));

        final SkillExecutionResult result = execute(
                ToolContext.builder().put(ToolContextKeys.SKILL_TOOL_DISPATCHER_KEY, dispatcher).build(),
                new CountingTool());

        assertThat(result.isSuccess()).isTrue();
        assertThat(dispatcher.dispatched).extracting(ToolUse::getId).containsExactly("t2");
        assertThat(lastToolResults(llm.seen.get(1))).singleElement().satisfies(
                answer -> assertThat(answer.getContent()).isEqualTo(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE));
    }

    @Test
    @DisplayName("a final answer cut at max_tokens is a success whose response ends with the marker, and is logged")
    void aCutFinalAnswerEndsWithTheMarker() {
        // Success, not failure: a failure's response is an error message, so the partial text a turn and a fork both
        // keep would be lost on this path alone.
        llm.responses.add(LlmResponse.of("partial", List.of(), USAGE, StopReason.MAX_TOKENS));

        final SkillExecutionResult result = execute(ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("partial" + TruncatedResponses.TRUNCATION_MARKER);
        assertThat(warnings()).anySatisfy(warning -> assertThat(warning).contains("Skill '" + SKILL + "'")
                .contains("final answer truncated at max_tokens"));
    }

    @Test
    @DisplayName("a final answer that stopped normally, or reported no stop reason, carries no marker and no WARN")
    void anUncutFinalAnswerIsUnchanged() {
        for (LlmResponse response : List.of(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN),
                LlmResponse.of("done", List.of(), USAGE))) {
            llm.responses.add(response);

            final SkillExecutionResult result = execute(ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResponse()).isEqualTo("done");
        }
        assertThat(warnings()).noneMatch(warning -> warning.contains("max_tokens"));
    }

    @Test
    @DisplayName("three cut tool responses in a row trip the stalled-iteration guard and fail the skill, naming max_tokens")
    void threeCutToolResponsesInARowFailTheSkill() {
        // A slash command cannot be interrupted, so without the guard a person would wait out the skill's
        // max-iterations, 100 by default.
        final CountingTool tool = new CountingTool();
        for (int i = 1; i <= StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS; i++) {
            llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t" + i, CountingTool.NAME, Map.of())), USAGE,
                    StopReason.MAX_TOKENS));
        }
        // Must never be consumed: the guard ends the skill before a fourth LLM call.
        llm.responses.add(LlmResponse.of("never reached", List.of(), USAGE, StopReason.END_TURN));

        final SkillExecutionResult result = execute(ToolContext.empty(), tool);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).isEqualTo(PLAIN_STOP_MESSAGE + MAX_TOKENS_CLAUSE);
        assertThat(result.getError()).get().isInstanceOf(IllegalStateException.class);
        assertThat(result.getMetadata()).get().satisfies(metadata -> assertThat(metadata.getIterationCount())
                .isEqualTo(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS));
        assertThat(llm.seen).hasSize(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        assertThat(tool.executions).isZero();
        // One WARN per cut response names max_tokens; the guard's own WARN does not.
        assertThat(warnings()).filteredOn(warning -> warning.contains("max_tokens"))
                .hasSize(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        assertThat(warnings()).anySatisfy(warning -> assertThat(warning).contains("Skill '" + SKILL + "'")
                .contains("stalled-iteration guard tripped"));
    }

    @Test
    @DisplayName("three iterations whose tool calls all fail end the skill with the plain stop message")
    void threeFailingToolIterationsFailTheSkill() {
        // The guard is not about max_tokens: this is the observable change beyond truncation that #115 names.
        for (int i = 1; i <= StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS; i++) {
            llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t" + i, FailingTool.NAME, Map.of())), USAGE));
        }
        llm.responses.add(LlmResponse.of("never reached", List.of(), USAGE));

        final SkillExecutionResult result = execute(ToolContext.empty(), new FailingTool());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).isEqualTo(PLAIN_STOP_MESSAGE);
        assertThat(llm.seen).hasSize(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS);
    }

    @Test
    @DisplayName("a skill whose tool succeeds before the threshold resets the streak and finishes")
    void aSkillThatRecoversBeforeTheThresholdFinishes() {
        final CountingTool counting = new CountingTool();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", FailingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t2", FailingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t3", CountingTool.NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));

        final SkillExecutionResult result = execute(ToolContext.empty(), new FailingTool(), counting);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("done");
        assertThat(counting.executions).isOne();
        assertThat(llm.seen).hasSize(4);
    }

    @Test
    @DisplayName("both skill WARNs carry the cut response's reasoning count when it is reported, and no clause when not")
    void bothWarningsCarryTheReasoningCountOnlyWhenReported() {
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())),
                TokenUsage.of(10, 4000, 4010, 3990), StopReason.MAX_TOKENS));
        llm.responses
                .add(LlmResponse.of("partial", List.of(), TokenUsage.of(10, 3100, 3110, 3000), StopReason.MAX_TOKENS));

        execute(ToolContext.empty(), new CountingTool());

        // Accumulated, the final answer's usage would read 7100 output and 6990 reasoning tokens.
        assertThat(warnings()).filteredOn(warning -> warning.contains("max_tokens")).satisfiesExactly(
                toolCalls -> assertThat(toolCalls).contains(CountingTool.NAME)
                        .endsWith("; the response's usage reports 4000 output tokens and 3990 reasoning tokens"),
                finalAnswer -> assertThat(finalAnswer).contains("final answer")
                        .endsWith("; the response's usage reports 3100 output tokens and 3000 reasoning tokens"));

        logAppender.list.clear();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", CountingTool.NAME, Map.of())),
                TokenUsage.of(10, 4000, 4010), StopReason.MAX_TOKENS));
        llm.responses.add(LlmResponse.of("partial", List.of(), TokenUsage.of(10, 4000, 4010), StopReason.MAX_TOKENS));

        execute(ToolContext.empty(), new CountingTool());

        assertThat(warnings()).filteredOn(warning -> warning.contains("max_tokens")).hasSize(2)
                .noneMatch(warning -> warning.contains("reasoning"));
    }

    private SkillExecutionResult execute(ToolContext toolContext, Tool... tools) {
        final ToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        final Skill skill = Skill.builder().name(SKILL)
                .metadata(
                        SkillMetadata.builder().name(SKILL).description("truncation fixture").maxIterations(10).build())
                .content(SkillContent.of("Audit the module")).build();
        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(registry)
                .executionId(ExecutionId.generate("skill:test")).toolContext(toolContext).build();
        return executor.execute(context, SkillExecutionRequest.builder().build());
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<ToolUseResult> lastToolResults(List<Message> messagesSentOnACall) {
        return messagesSentOnACall.get(messagesSentOnACall.size() - 1).getToolUseResults();
    }

    /** Counts its own executions, so a test can tell a refused call from one that ran. */
    private static final class CountingTool implements Tool {
        static final String NAME = "Counter";

        private int executions;

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of(NAME, "Counts executions", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            executions++;
            return ToolResult.success("counted");
        }
    }

    /** Always fails, which is what drives a streak of stalled iterations. */
    private static final class FailingTool implements Tool {
        static final String NAME = "Failing";

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of(NAME, "Always fails", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.error("boom");
        }
    }

    /** Records the calls it is handed and answers each with success, touching no tool. */
    private static final class RecordingDispatcher implements SkillToolDispatcher {
        private final List<ToolUse> dispatched = new ArrayList<>();

        @Override
        public List<ToolUseResult> dispatch(ToolRegistry toolRegistry, ToolContext toolContext, List<ToolUse> toolUses,
                List<AllowedTool> allowedTools, int iterationCount) {
            dispatched.addAll(toolUses);
            return toolUses.stream().map(use -> ToolUseResult.success(use.getId(), "dispatched")).toList();
        }
    }

    /** Returns queued responses in order and records the messages each call was handed. */
    private static final class QueueLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> seen = new ArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            seen.add(List.copyOf(messages));
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Queue";
        }
    }
}
