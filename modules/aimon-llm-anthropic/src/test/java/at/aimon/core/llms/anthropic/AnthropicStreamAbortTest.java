package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.compact.CompactionRequest;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.compact.InMemoryCompactionFailureStore;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.SignalBackedLlmCancellation;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * What an interrupt does to a streaming call that is in flight, over the real transport.
 *
 * <p>
 * The SDK does not throw out of a stream that is closed under it — the stream ends. Before EE-95's follow-up the
 * client took that for the end of the answer and returned what had arrived. These tests pin the three places that
 * mattered: the client reports a cancellation, an AUTO-compaction summary is not installed and does not move the
 * circuit breaker, and a turn ends {@code INTERRUPTED} rather than completing with the part of the answer that got
 * through.
 *
 * <p>
 * Every stop is read from an outcome — the exception, the transcript, the breaker's count — and the timeouts are hang
 * guards only.
 */
@DisplayName("Anthropic - an interrupt during a streaming call, over the real transport")
class AnthropicStreamAbortTest {

    private static final long HANG_GUARD_SECONDS = 20;
    private static final String PARTIAL = "The user first asked to";
    private static final SessionId SESSION = SessionId.of("s-abort");

    /** Auto-compact at 1200 estimated tokens, warning at 1000, blocking at 1400. */
    private static final ModelContextLimits TINY = ModelContextLimits.builder().contextWindow(2000)
            .reservedOutputTokens(500).autoCompactBuffer(300).warningBuffer(200).blockingBuffer(100).build();

    private static final String OPENING = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{"
            + "\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-x\",\"content\":[],"
            + "\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n"
            + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n";
    private static final String TEXT_DELTA = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\","
            + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + PARTIAL + "\"}}\n\n";
    private static final String BEFORE_ANY_TEXT = OPENING;
    private static final String AFTER_SOME_TEXT = OPENING + TEXT_DELTA;

    @TempDir
    Path tempDir;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    // --- the client ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the client: aborted after some text, the call is a cancellation and the sink is given no end")
    void client_abortedAfterSomeText_isACancellation() throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(AFTER_SOME_TEXT);
                DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final RecordingSink sink = new RecordingSink();

            final Future<LlmResponse> running = stream(client(server), sink, coordinator);
            assertThat(sink.textArrived.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            assertThatThrownBy(() -> running.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(LlmCallCancelledException.class);
            // A terminal chunk would tell the caller the answer is whole; its "interrupted" completion is its own.
            assertThat(sink.kinds()).containsExactly(LlmStreamChunk.Kind.TEXT_DELTA);
        }
    }

    @Test
    @DisplayName("the client: aborted before any text, the call is a cancellation and not an empty answer")
    void client_abortedBeforeAnyText_isACancellation() throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(BEFORE_ANY_TEXT);
                DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final RecordingSink sink = new RecordingSink();

            final Future<LlmResponse> running = stream(client(server), sink, coordinator);
            assertThat(server.awaitAnswered(HANG_GUARD_SECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            assertThatThrownBy(() -> running.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(LlmCallCancelledException.class);
            assertThat(sink.kinds()).isEmpty();
        }
    }

    // --- the AUTO-compaction summary call (EE-95) ---------------------------------------------------------------

    @Test
    @DisplayName("AUTO compaction: aborted after some text, no summary is installed and the breaker does not move")
    void summary_abortedAfterSomeText_installsNothing() throws Exception {
        assertAnAbortedSummaryInstallsNothing(AFTER_SOME_TEXT);
    }

    @Test
    @DisplayName("AUTO compaction: aborted before any text, it is not an empty-summary failure the breaker counts")
    void summary_abortedBeforeAnyText_installsNothing() throws Exception {
        assertAnAbortedSummaryInstallsNothing(BEFORE_ANY_TEXT);
    }

    @SuppressWarnings("deprecation")
    private void assertAnAbortedSummaryInstallsNothing(String sse) throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(sse);
                DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final DefaultCompactionEngine engine = DefaultCompactionEngine.withDefaults(client(server),
                    new HeuristicTokenEstimator(), new DefaultHookExecutionManager());
            final InMemoryCompactionFailureStore failures = new InMemoryCompactionFailureStore();
            final DefaultCompactionGuard guard = new DefaultCompactionGuard(engine,
                    InMemoryModelContextWindowRegistry.builder().defaultLimits(TINY).build(),
                    new HeuristicTokenEstimator(), DefaultCompactionGuard.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                    DefaultCompactionGuard.DEFAULT_MAX_TRACKED_SESSIONS, failures);
            final TranscriptBuffer buffer = new TranscriptBuffer(SESSION);
            buffer.addUserMessage("first question with details");
            buffer.addAssistantMessage("first long answer");
            buffer.addUserMessage("second question");
            buffer.addAssistantMessage("second long answer");
            final List<Message> before = buffer.getMessages();
            final AtomicReference<CompactionResult> attempt = new AtomicReference<>();

            // The guard is the breaker's owner, so the compaction runs under it as it does in a turn: over the auto
            // threshold, with the execution's signal on the request.
            final Future<?> running = worker
                    .submit(() -> guard.decide(SESSION, "sys", List.of(Message.user("x".repeat(4300))),
                            LlmModel.builder().name("claude-x").build(), false, forced -> {
                                final CompactionResult result = engine.compact(CompactionRequest.builder()
                                        .transcriptBuffer(buffer).trigger(CompactionTrigger.AUTO)
                                        .model(LlmModel.builder().build()).hookRegistry(new DefaultHookRegistry())
                                        .executionCancellation(coordinator.getSignal()).build());
                                attempt.set(result);
                                return result;
                            }));
            assertThat(server.awaitAnswered(HANG_GUARD_SECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            running.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);

            assertThat(attempt.get().isFailure()).isTrue();
            assertThat(attempt.get().getError()).containsInstanceOf(LlmCallCancelledException.class);
            assertThat(buffer.getMessages()).as("the transcript is as it was").isEqualTo(before);
            assertThat(failures.get(SESSION)).as("an interrupted summary is not a failed compaction").isZero();
        }
    }

    // --- the ReAct loop's own call ------------------------------------------------------------------------------

    @Test
    @DisplayName("a turn: aborted after some text, it ends INTERRUPTED and the transcript keeps the streamed prefix")
    void turn_abortedAfterSomeText_endsInterruptedWithThePrefix() throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(AFTER_SOME_TEXT)) {
            final FirstTextTap client = new FirstTextTap(client(server));
            final AtomicReference<InterruptCoordinator> coordinator = new AtomicReference<>();

            final Future<OrcaAgentExecutionResult> running = runTurn(client, coordinator);
            assertThat(client.textArrived.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
            coordinator.get().requestInterrupt(InterruptReason.USER_SIGINT);
            final OrcaAgentExecutionResult result = running.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);

            // Not COMPLETED with the prefix as the final answer, which is what a quietly ended stream used to give.
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getConversationHistory()).filteredOn(message -> message.getRole() == Role.ASSISTANT)
                    .extracting(Message::getContent).containsExactly(PARTIAL);
        }
    }

    @Test
    @DisplayName("a turn: aborted before any text, it ends INTERRUPTED and no empty answer is written")
    void turn_abortedBeforeAnyText_endsInterruptedWithNoAnswer() throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(BEFORE_ANY_TEXT)) {
            final AtomicReference<InterruptCoordinator> coordinator = new AtomicReference<>();

            final Future<OrcaAgentExecutionResult> running = runTurn(client(server), coordinator);
            assertThat(server.awaitAnswered(HANG_GUARD_SECONDS)).isTrue();
            coordinator.get().requestInterrupt(InterruptReason.USER_SIGINT);
            final OrcaAgentExecutionResult result = running.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
            assertThat(result.getConversationHistory()).noneMatch(message -> message.getRole() == Role.ASSISTANT);
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static AnthropicLlmClient client(StallingSseServer server) {
        return new AnthropicLlmClient(
                AnthropicConfig.builder().apiKey("test-key").model("claude-x").baseUrl(server.url()).build());
    }

    private Future<LlmResponse> stream(LlmClient client, LlmStreamSink sink, InterruptCoordinator coordinator) {
        return worker.submit(() -> client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                List.of(), LlmModel.builder().build(), LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), sink,
                new SignalBackedLlmCancellation(coordinator.getSignal())));
    }

    /** Runs one streaming turn on the worker; the turn's coordinator is published to {@code coordinator}. */
    private Future<OrcaAgentExecutionResult> runTurn(LlmClient client,
            AtomicReference<InterruptCoordinator> coordinator) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final OrcaAgentExecutor executor = OrcaAgentExecutor.builder().llmClient(client)
                .transcriptManager(new DefaultTranscriptManager(new InMemorySessionRecordStore()))
                .toolExecutionManager(toolManager).hookExecutionManager(hookManager)
                .commandExecutionManager(new DefaultCommandExecutionManager(client))
                .subagentExecutionManager(new DefaultSubagentExecutionManager(client, toolManager, hookManager))
                .useStreaming(true).build();
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final DefaultAgent agent = DefaultAgent.builder().name("AbortAgent").maxIterations(3)
                .systemPrompt("You are a test agent").build();
        final OrcaAgentRuntime runtime = OrcaAgentRuntime.builder().id(AgentRuntimeId.from(agent)).agent(agent)
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                .build();
        return worker.submit(() -> executor.execute(runtime, OrcaAgentExecutionRequest.builder().userInput("hi")
                .sessionId(SessionId.generate()).interruptObserver(coordinator::set).build()));
    }

    /** Records what the client hands its sink, and says when the first text has arrived. */
    private static final class RecordingSink implements LlmStreamSink {
        final CountDownLatch textArrived = new CountDownLatch(1);
        private final List<LlmStreamChunk> chunks = new CopyOnWriteArrayList<>();

        @Override
        public void accept(LlmStreamChunk chunk) {
            chunks.add(chunk);
            if (chunk.getKind() == LlmStreamChunk.Kind.TEXT_DELTA) {
                textArrived.countDown();
            }
        }

        List<LlmStreamChunk.Kind> kinds() {
            return chunks.stream().map(LlmStreamChunk::getKind).toList();
        }
    }

    /**
     * Hands every call to the real client, and says when the loop's sink has been given the first text of a streamed
     * answer — so the turn is interrupted at a known point and not after a sleep.
     */
    private static final class FirstTextTap implements LlmClient {
        final CountDownLatch textArrived = new CountDownLatch(1);
        private final LlmClient delegate;

        FirstTextTap(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return delegate.sendMessage(systemPrompt, messages, tools, modelConfig);
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            return delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata, options,
                    chunk -> {
                        sink.accept(chunk);
                        if (chunk.getKind() == LlmStreamChunk.Kind.TEXT_DELTA) {
                            textArrived.countDown();
                        }
                    }, cancellation);
        }

        @Override
        public String getProviderName() {
            return delegate.getProviderName();
        }

        @Override
        public Optional<String> getDefaultModelName() {
            return delegate.getDefaultModelName();
        }
    }
}
