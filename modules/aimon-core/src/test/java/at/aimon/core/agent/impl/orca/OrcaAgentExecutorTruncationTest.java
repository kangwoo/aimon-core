package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.TruncatedResponses;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantMessageReceived;
import at.aimon.core.agent.stream.IterationCompleted;
import at.aimon.core.agent.stream.ToolResultReady;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ParallelToolDispatcher;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A response the provider cut off at its max-output-token limit, in both of its shapes.
 *
 * <p>
 * <strong>A final answer</strong> with no tool calls is surfaced as a flagged partial answer, marked with
 * {@link OrcaAgentExecutor#TRUNCATION_MARKER}, and terminates with {@link CompletionReason#TRUNCATED} instead of being
 * silently treated as a normal {@link CompletionReason#COMPLETED} finish.
 *
 * <p>
 * <strong>A response with tool calls</strong> runs none of them (#108). Each is answered with
 * {@link TruncatedResponses#REFUSED_TOOL_CALL_MESSAGE}, a WARN names {@code max_tokens}, and the loop continues — so a
 * call whose arguments never arrived is not run with an empty map, and the model is told why. The assertions read the
 * shared constants, as {@code DefaultSubagentExecutorTruncationTest} does for a fork; that is what keeps the two
 * executors giving one answer.
 */
@DisplayName("OrcaAgentExecutor max_tokens truncation handling")
class OrcaAgentExecutorTruncationTest {

    private static final TokenUsage USAGE = TokenUsage.of(10, 10, 20);

    @TempDir
    Path tempDir;

    private Logger executorLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        executorLogger = (Logger) LoggerFactory.getLogger(OrcaAgentExecutor.class);
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
    @DisplayName("MAX_TOKENS stop with no tool uses surfaces flagged partial answer as TRUNCATED")
    void maxTokensWithoutToolUsesIsFlaggedAsTruncated() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("partial text", List.of(), TokenUsage.of(10, 5, 15), StopReason.MAX_TOKENS));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(), request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TRUNCATED);
        assertThat(result.getCompletionReason().isSuccessful()).isFalse();
        // The flagged partial text is still surfaced to the caller - the underlying result stays "successful" even
        // though the completion reason signals an incomplete answer.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).startsWith("partial text");
        assertThat(result.getFinalAnswer()).endsWith(OrcaAgentExecutor.TRUNCATION_MARKER);
        // The shared definition a fork appends too, and the literal, which pins that moving it changed no text.
        assertThat(result.getFinalAnswer()).endsWith(TruncatedResponses.TRUNCATION_MARKER);
        assertThat(result.getFinalAnswer()).contains("[System: response truncated at max_tokens]");
        assertThat(warnings()).anySatisfy(warning -> assertThat(warning).contains("max_tokens"));
    }

    @Test
    @DisplayName("normal END_TURN final response completes cleanly without the truncation marker")
    void normalFinalResponseCompletesCleanly() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(10, 5, 15), StopReason.END_TURN));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(), request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(result.getFinalAnswer()).doesNotContain(OrcaAgentExecutor.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("legacy 3-arg factory (no stop reason captured) also completes cleanly")
    void legacyResponseWithoutStopReasonCompletesCleanly() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(10, 5, 15)));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(), request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(result.getFinalAnswer()).doesNotContain(OrcaAgentExecutor.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("a tool call in a response cut at max_tokens is not run; it is answered with an error naming max_tokens")
    void aToolCallInAResponseCutAtMaxTokensIsNotRun() {
        // Replaces maxTokensWithToolUsesIsNotTreatedAsTruncated. Its comment said such a response "goes through the
        // normal tool-execution path", and its assertions (COMPLETED, "done") stayed green whether or not the tool ran.
        // The invocation count is the assertion that tells the two apart.
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("acting", List.of(ToolUse.of("tu-1", CountingTool.TOOL_NAME, Map.of())), USAGE,
                StopReason.MAX_TOKENS));
        llmClient.enqueue(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));
        final CountingTool tool = new CountingTool();

        final OrcaAgentExecutionResult result = createExecutor(llmClient).execute(createContext(registryWith(tool)),
                request());

        assertThat(tool.invocations).hasValue(0);
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.getFinalAnswer()).isEqualTo("done").doesNotContain(OrcaAgentExecutor.TRUNCATION_MARKER);
        // What the model is handed on the next call: exactly one result for the one call, and it is the refusal.
        assertThat(lastToolResults(llmClient.seen.get(1))).singleElement().satisfies(answer -> {
            assertThat(answer.getToolUseId()).isEqualTo("tu-1");
            assertThat(answer.isError()).isTrue();
            assertThat(answer.getContent()).isEqualTo(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE)
                    .contains("max_tokens");
        });
        assertThat(warnings()).anySatisfy(warning -> assertThat(warning).contains("max_tokens").contains("iteration 1")
                .contains(CountingTool.TOOL_NAME));
    }

    @Test
    @DisplayName("every tool call of a cut response is refused, in order, including one whose arguments arrived")
    void everyToolCallOfACutResponseIsRefusedInOrder() {
        // The neutral response does not say which call was cut, so a call that looks complete is refused as well.
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient
                .enqueue(
                        LlmResponse.of("",
                                List.of(ToolUse.of("tu-1", CountingTool.TOOL_NAME, Map.of("note", "complete")),
                                        ToolUse.of("tu-2", CountingTool.TOOL_NAME, Map.of())),
                                USAGE, StopReason.MAX_TOKENS));
        llmClient.enqueue(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));
        final CountingTool tool = new CountingTool();

        final OrcaAgentExecutionResult result = createExecutor(llmClient).execute(createContext(registryWith(tool)),
                request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(tool.invocations).hasValue(0);
        final List<ToolUseResult> answers = lastToolResults(llmClient.seen.get(1));
        assertThat(answers).extracting(ToolUseResult::getToolUseId).containsExactly("tu-1", "tu-2");
        assertThat(answers).allSatisfy(answer -> {
            assertThat(answer.isError()).isTrue();
            assertThat(answer.getContent()).isEqualTo(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE);
        });
    }

    @Test
    @DisplayName("three cut tool responses in a row trip the stalled-iteration guard and end the turn as ERROR")
    void threeCutToolResponsesInARowTripTheStalledIterationGuard() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        for (int i = 1; i <= OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS; i++) {
            llmClient.enqueue(LlmResponse.of("", List.of(ToolUse.of("tu-" + i, CountingTool.TOOL_NAME, Map.of())),
                    USAGE, StopReason.MAX_TOKENS));
        }
        // Must never be consumed: the guard ends the turn before a fourth LLM call.
        llmClient.enqueue(LlmResponse.of("never reached", List.of(), USAGE, StopReason.END_TURN));
        final CountingTool tool = new CountingTool();

        final OrcaAgentExecutionResult result = createExecutor(llmClient).execute(createContext(registryWith(tool)),
                request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(result.getIterationCount()).isEqualTo(OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        assertThat(llmClient.seen).hasSize(OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        assertThat(tool.invocations).hasValue(0);
        // One WARN per cut response, each naming max_tokens; the guard's own WARN does not.
        assertThat(warnings()).filteredOn(warning -> warning.contains("max_tokens"))
                .hasSize(OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS);
        // The stop message is what a reader of the ERROR sees, and a streak made only of refusals says why (#115).
        assertThat(result.getErrorMessage()).endsWith(
                "(all tool calls failed) — each of those responses was cut off at max_tokens, and its tool calls were "
                        + "refused");
    }

    @Test
    @DisplayName("no PermissionRequest, PreTool or PostTool hook runs for a refused call; the same call uncut reaches all three")
    void noHookRunsForARefusedCall() {
        // Pins what #113's CHANGELOG entry says and aRefusedCallStillEmitsItsLifecycleEvents only stated in a comment
        // (#117). The uncut iteration is the positive control: without it, 0 could mean "not wired" as easily as
        // "not reached".
        final HookCounters hooks = new HookCounters();
        final CountingTool tool = new CountingTool();
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("", List.of(ToolUse.of("tu-1", CountingTool.TOOL_NAME, Map.of())), USAGE,
                StopReason.MAX_TOKENS));
        llmClient.enqueue(LlmResponse.of("", List.of(ToolUse.of("tu-2", CountingTool.TOOL_NAME, Map.of())), USAGE,
                StopReason.TOOL_USE));
        llmClient.enqueue(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));
        final List<List<Integer>> reachedBeforeEachCall = new ArrayList<>();
        llmClient.beforeEachCall = () -> reachedBeforeEachCall.add(hooks.andTool(tool.invocations));

        final OrcaAgentExecutionResult result = createExecutor(llmClient)
                .execute(createContext(registryWith(tool), hooks.registry), request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        // [PermissionRequest, PreTool, PostTool, tool] before each LLM call: after the cut iteration, then the uncut
        // one.
        assertThat(reachedBeforeEachCall).containsExactly(List.of(0, 0, 0, 0), List.of(0, 0, 0, 0),
                List.of(1, 1, 1, 1));
        assertThat(lastToolResults(llmClient.seen.get(1))).singleElement().satisfies(
                answer -> assertThat(answer.getContent()).isEqualTo(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE));
    }

    @Test
    @DisplayName("both truncation WARNs carry the cut response's own reasoning count, not the turn's running total")
    void bothWarningsCarryTheCutResponsesReasoningCount() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("", List.of(ToolUse.of("tu-1", CountingTool.TOOL_NAME, Map.of())),
                TokenUsage.of(10, 4000, 4010, 3990), StopReason.MAX_TOKENS));
        llmClient.enqueue(
                LlmResponse.of("partial", List.of(), TokenUsage.of(10, 3100, 3110, 3000), StopReason.MAX_TOKENS));

        final OrcaAgentExecutionResult result = createExecutor(llmClient)
                .execute(createContext(registryWith(new CountingTool())), request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TRUNCATED);
        // Accumulated, the final answer's usage would read 7100 output and 6990 reasoning tokens.
        assertThat(warnings()).filteredOn(warning -> warning.contains("max_tokens")).satisfiesExactly(
                toolCalls -> assertThat(toolCalls).contains(CountingTool.TOOL_NAME)
                        .endsWith("; the response's usage reports 4000 output tokens and 3990 reasoning tokens"),
                finalAnswer -> assertThat(finalAnswer).contains("surfacing flagged partial answer")
                        .endsWith("; the response's usage reports 3100 output tokens and 3000 reasoning tokens"));
    }

    @Test
    @DisplayName("with no reasoning tokens reported, neither WARN gains a clause and the final-answer WARN reads as before")
    void withoutReasoningTokensTheWarningsReadAsBefore() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("", List.of(ToolUse.of("tu-1", CountingTool.TOOL_NAME, Map.of())),
                TokenUsage.of(10, 4000, 4010), StopReason.MAX_TOKENS));
        llmClient.enqueue(LlmResponse.of("partial", List.of(), TokenUsage.of(10, 4000, 4010), StopReason.MAX_TOKENS));

        createExecutor(llmClient).execute(createContext(registryWith(new CountingTool())), request());

        assertThat(warnings()).filteredOn(warning -> warning.contains("max_tokens")).hasSize(2)
                .noneMatch(warning -> warning.contains("reasoning")).contains(
                        "Agent execution truncated at max_tokens after 2 iterations; surfacing flagged partial answer");
    }

    @Test
    @DisplayName("a refused call still emits ToolUseStarted and a failed ToolResultReady carrying the refusal")
    void aRefusedCallStillEmitsItsLifecycleEvents() {
        // The REPL renders a refused call from ToolResultReady alone ("Tool '<name>' failed: <errorMessage>"):
        // its usual tool-call line comes from a PreTool hook, and no hook runs for a refused call. The error
        // message is where a person watching the REPL reads max_tokens.
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("acting", List.of(ToolUse.of("tu-1", CountingTool.TOOL_NAME, Map.of())), USAGE,
                StopReason.MAX_TOKENS));
        llmClient.enqueue(LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN));
        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final List<AgentExecutionEvent> seen = new ArrayList<>();
        executor.addEventListener(seen::add);

        executor.execute(createContext(registryWith(new CountingTool())), request());

        final List<AgentExecutionEvent> firstIteration = seen.stream()
                .filter(event -> event instanceof AssistantMessageReceived || event instanceof ToolUseStarted
                        || event instanceof ToolResultReady || event instanceof IterationCompleted)
                .limit(4).toList();
        assertThat(firstIteration).satisfiesExactly(
                received -> assertThat(received).isInstanceOf(AssistantMessageReceived.class),
                started -> assertThat(started).isInstanceOfSatisfying(ToolUseStarted.class,
                        event -> assertThat(event.getToolUseId()).isEqualTo("tu-1")),
                ready -> assertThat(ready).isInstanceOfSatisfying(ToolResultReady.class, event -> {
                    assertThat(event.getToolUseId()).isEqualTo("tu-1");
                    assertThat(event.isSuccess()).isFalse();
                    assertThat(event.getErrorMessage()).contains(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE);
                }), completed -> assertThat(completed).isInstanceOfSatisfying(IterationCompleted.class,
                        event -> assertThat(event.isWillContinue()).isTrue()));
    }

    @Test
    @DisplayName("streamed: a tool_use that reached TOOL_USE_READY before the MAX_TOKENS stop is refused as well")
    void aStreamedCutToolCallIsRefused() {
        final StreamingLlmClient llmClient = new StreamingLlmClient(
                ToolUse.of("tu-1", CountingTool.TOOL_NAME, Map.of()));
        final CountingTool tool = new CountingTool();

        final OrcaAgentExecutionResult result = createStreamingExecutor(llmClient, null)
                .execute(createContext(registryWith(tool)), request());

        assertThat(tool.invocations).hasValue(0);
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(lastToolResults(llmClient.seen.get(1))).singleElement().satisfies(answer -> {
            assertThat(answer.isError()).isTrue();
            assertThat(answer.getContent()).isEqualTo(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE);
        });
        assertThat(warnings())
                .anySatisfy(warning -> assertThat(warning).contains("max_tokens").contains("none of them"));
    }

    @Test
    @DisplayName("streamed with overlap: a CONCURRENT_SAFE call started early from the cut response does not supply its result")
    void anEagerlyStartedCallFromACutResponseDoesNotSupplyItsResult() {
        // Overlap starts a CONCURRENT_SAFE call as its block closes, before the stop reason arrives, so the call
        // may have run by the time MAX_TOKENS is read. What must hold: its result is discarded, the model gets the
        // refusal, and the WARN says a started call was discarded instead of claiming none ran.
        final StreamingLlmClient llmClient = new StreamingLlmClient(
                ToolUse.of("tu-1", SafeCountingTool.SAFE_TOOL_NAME, Map.of()));
        try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(2).streamingOverlap(true).build())) {

            final OrcaAgentExecutionResult result = createStreamingExecutor(llmClient, dispatcher)
                    .execute(createContext(registryWith(new SafeCountingTool())), request());

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(lastToolResults(llmClient.seen.get(1))).singleElement().satisfies(answer -> {
                assertThat(answer.isError()).isTrue();
                assertThat(answer.getContent()).isEqualTo(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE);
            });
            assertThat(warnings()).anySatisfy(warning -> assertThat(warning).contains("max_tokens")
                    .contains("streaming overlap had already started"));
        }
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<ToolUseResult> lastToolResults(List<Message> messagesSentOnACall) {
        return messagesSentOnACall.get(messagesSentOnACall.size() - 1).getToolUseResults();
    }

    private static OrcaAgentExecutionRequest request() {
        return OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build();
    }

    private static DefaultToolRegistry registryWith(AbstractTool tool) {
        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(tool);
        return toolRegistry;
    }

    private OrcaAgentRuntime createContext() {
        return createContext(new DefaultToolRegistry());
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        return createContext(toolRegistry, new DefaultHookRegistry());
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry, DefaultHookRegistry hookRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry)
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    private OrcaAgentExecutor createStreamingExecutor(LlmClient client, ParallelToolDispatcher dispatcher) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        final OrcaAgentExecutor.Builder builder = OrcaAgentExecutor.builder().llmClient(client)
                .transcriptManager(new DefaultTranscriptManager(new InMemorySessionRecordStore()))
                .toolExecutionManager(toolManager).hookExecutionManager(hookManager)
                .commandExecutionManager(commandManager).subagentExecutionManager(subagentManager).useStreaming(true);
        if (dispatcher != null) {
            builder.parallelToolDispatcher(dispatcher);
        }
        return builder.build();
    }

    /**
     * Minimal LLM client that returns pre-queued responses in sequence, and records the messages each call was handed.
     * Supports an optional delay applied once per response, allowing wall-clock budget scenarios to exercise the
     * elapsed-time check without relying on real LLM latency.
     */
    private static final class SequencedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private final List<List<Message>> seen = new ArrayList<>();
        private Duration delayBeforeResponse = Duration.ZERO;
        /** Runs at the start of every call, so a test can read what the previous iteration reached. */
        private Runnable beforeEachCall = () -> {
        };

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        void setDelayBeforeResponse(Duration delay) {
            this.delayBeforeResponse = delay;
        }

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
            if (!delayBeforeResponse.isZero()) {
                try {
                    Thread.sleep(delayBeforeResponse.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (responses.isEmpty()) {
                return LlmResponse.text("unexpected-extra-call");
            }
            return responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Sequenced";
        }

    }

    /**
     * Streams a response whose one tool_use block closes (so it reaches {@code TOOL_USE_READY}) before the stream stops
     * at {@code MAX_TOKENS}, then a normal final answer. Only the order of the chunks matters to the executor, not any
     * provider's vocabulary.
     */
    private static final class StreamingLlmClient implements LlmClient {
        private final ToolUse toolUse;
        private final List<List<Message>> seen = new ArrayList<>();

        StreamingLlmClient(ToolUse toolUse) {
            this.toolUse = toolUse;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("streaming-only fake");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            seen.add(List.copyOf(messages));
            if (seen.size() == 1) {
                sink.accept(LlmStreamChunk.textDelta(0, "acting"));
                sink.accept(LlmStreamChunk.toolUseReady(1, toolUse));
                sink.accept(LlmStreamChunk.streamEnd(2, USAGE, Optional.of("max_tokens"), StopReason.MAX_TOKENS));
                return LlmResponse.of("acting", List.of(toolUse), USAGE, StopReason.MAX_TOKENS);
            }
            sink.accept(LlmStreamChunk.textDelta(0, "done"));
            sink.accept(LlmStreamChunk.streamEnd(1, USAGE, Optional.of("end_turn"), StopReason.END_TURN));
            return LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN);
        }

        @Override
        public String getProviderName() {
            return "Streaming";
        }
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

    /** Counts its invocations, which is what the refusal tests assert on. */
    private static class CountingTool extends AbstractTool {
        static final String TOOL_NAME = "Counting";

        final AtomicInteger invocations = new AtomicInteger();

        CountingTool() {
            this(TOOL_NAME);
        }

        CountingTool(String name) {
            super(name, "counts its invocations for truncation tests", Map.of("type", "object", "properties",
                    Map.of("note", Map.of("type", "string")), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            invocations.incrementAndGet();
            return ToolResult.success("ran");
        }
    }

    /** A counting tool that streaming overlap may start early, before the response's stop reason is read. */
    private static final class SafeCountingTool extends CountingTool {
        static final String SAFE_TOOL_NAME = "SafeCounting";

        SafeCountingTool() {
            super(SAFE_TOOL_NAME);
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.NON_INTERRUPTIBLE;
        }
    }
}
