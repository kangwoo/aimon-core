package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.compact.DefaultPromptSizeRecoveryStrategy;
import at.aimon.core.agent.context.DefaultContextEngine;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SeqRange;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogManifestEntry;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionLogStorage;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.memory.ExecutionMemoryUpdate;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * The default context engine in view mode, driven by the executor over a version-2 transcript: compaction shrinks what
 * the model is sent and nothing else.
 *
 * <p>
 * Three claims of the context-engine design, end to end: the model sees the {@code [boundary, summary]} pair followed
 * by the input it has not answered yet, verbatim (the in-place mode summarized that input too — context-engine
 * §13.10), the record keeps every message that was said (L3), and the execution that was compacted is still offered
 * to memory as it was said (L4).
 */
@DisplayName("OrcaAgentExecutor with DefaultContextEngine in view mode")
class OrcaAgentExecutorViewModeTest {

    private static final Principal CALLER = Principal.user("alice", "Alice");

    /** Auto-compact at 1200 estimated tokens, blocking at 1400. */
    private static final ModelContextLimits TINY = ModelContextLimits.builder().contextWindow(2000)
            .reservedOutputTokens(500).autoCompactBuffer(300).warningBuffer(200).blockingBuffer(100).build();

    /** About 1230 estimated tokens: past the auto-compact threshold, short of the blocking limit. */
    private static final String LONG_QUESTION = "x".repeat(4300);

    @TempDir
    Path tempDir;

    private final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();

    @Test
    @DisplayName("a compaction sends the marker pair and the unanswered input, keeps the log whole and still feeds memory the original")
    void compactionShrinksOnlyTheView() {
        final RecordingClient llm = new RecordingClient("first answer", "THE SUMMARY", "second answer",
                "THE SECOND SUMMARY", "third answer");
        final List<ExecutionMemoryUpdate> fed = new ArrayList<>();
        final OrcaAgentExecutor executor = new OrcaAgentExecutorFactory().withExecutionMemorySink(fed::add).create(llm,
                new DefaultTranscriptManager(repository, SessionCheckpointMailbox.disabled(), SessionLogFormat.V2));
        final OrcaAgentRuntime runtime = runtime(executor, llm);
        final SessionId sessionId = SessionId.generate();

        executor.execute(runtime, request(sessionId, "first question"));
        executor.execute(runtime, request(sessionId, LONG_QUESTION));

        // Calls: turn 1, the summary, turn 2. The summary saw what the model had already answered. The new question,
        // which it had not, is not summarized: the model is sent it as it was asked.
        assertThat(llm.calls).hasSize(3);
        assertThat(llm.calls.get(1)).extracting(Message::getContent).contains("first question", "first answer")
                .doesNotContain(LONG_QUESTION);
        final List<Message> sentAfterCompaction = llm.calls.get(2);
        assertThat(sentAfterCompaction).hasSize(3);
        assertThat(sentAfterCompaction.get(0).getContent()).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(sentAfterCompaction.get(1).getContent()).startsWith(CompactBoundary.SUMMARY_OPEN_PREFIX)
                .contains("THE SUMMARY");
        assertThat(sentAfterCompaction.get(2).getContent()).isEqualTo(LONG_QUESTION);

        final SessionLogState stored = repository.load(sessionId).orElseThrow().getLogState();
        assertThat(stored.getFormat()).isEqualTo(SessionLogFormat.V2);
        assertThat(stored.getConversationMessages()).extracting(Message::getContent).as("the log keeps every word")
                .containsExactly("first question", "first answer", LONG_QUESTION, "second answer");
        assertThat(stored.getViewState().getSummarySpan())
                .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 2)));

        assertThat(fed).hasSize(2);
        assertThat(fed.get(1).getMessages()).extracting(Message::getContent)
                .as("the compacted execution is ingested as it was said, not as its summary")
                .containsExactly(LONG_QUESTION, "second answer");

        // A later turn — its buffer rebuilt from the record — projects the same span. The long question has been
        // answered by now, so this turn's compaction absorbs it and its answer, and stops before the new question.
        executor.execute(runtime, request(sessionId, "third question"));
        assertThat(llm.calls).hasSize(5);
        final List<Message> secondSummaryInput = llm.calls.get(3);
        assertThat(secondSummaryInput.get(0).getContent()).isEqualTo(sentAfterCompaction.get(0).getContent());
        assertThat(secondSummaryInput.get(1).getContent()).isEqualTo(sentAfterCompaction.get(1).getContent());
        assertThat(secondSummaryInput).extracting(Message::getContent).contains(LONG_QUESTION, "second answer")
                .doesNotContain("third question");
        final List<Message> resumed = llm.calls.get(4);
        assertThat(resumed).hasSize(3);
        assertThat(resumed.get(1).getContent()).contains("THE SECOND SUMMARY");
        assertThat(resumed.get(2).getContent()).isEqualTo("third question");
    }

    @Test
    @DisplayName("with a segment store, the compacted range leaves the record mid-turn and stays readable")
    void compactionSealsTheSummarizedRange() {
        final RecordingClient llm = new RecordingClient("first answer", "THE SUMMARY", "second answer",
                "THE SECOND SUMMARY", "third answer");
        final List<ExecutionMemoryUpdate> fed = new ArrayList<>();
        final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
        final DefaultTranscriptManager transcripts = new DefaultTranscriptManager(repository,
                SessionCheckpointMailbox.disabled(), SessionLogFormat.V2,
                SessionLogStorage.builder(segments).minSealTokens(0).build());
        final OrcaAgentExecutor executor = new OrcaAgentExecutorFactory().withExecutionMemorySink(fed::add).create(llm,
                transcripts);
        final OrcaAgentRuntime runtime = runtime(executor, llm);
        final SessionId sessionId = SessionId.generate();

        executor.execute(runtime, request(sessionId, "first question"));
        executor.execute(runtime, request(sessionId, LONG_QUESTION));

        final SessionLogState stored = repository.load(sessionId).orElseThrow().getLogState();
        // Sealed mid-turn. The span ends at seq 2, where this turn's unanswered question starts, so the sealed range
        // is the first turn alone and the turn's rewind point at seq 2 falls on its edge (session-log §5.1).
        assertThat(stored.getManifest()).extracting(SessionLogManifestEntry::getRange)
                .containsExactly(SeqRange.of(0, 2));
        assertThat(stored.getConversationMessages()).extracting(Message::getContent).as("only what the view shows")
                .containsExactly(LONG_QUESTION, "second answer");
        assertThat(segments.list(sessionId)).hasSize(1);

        final List<String> wholeLog = transcripts.getLogReader().orElseThrow().read(sessionId, 0, Long.MAX_VALUE)
                .getEntries().stream().filter(entry -> entry.getOrigin() == LogOrigin.CONVERSATION)
                .map(entry -> entry.getMessage().getContent()).toList();
        assertThat(wholeLog).containsExactly("first question", "first answer", LONG_QUESTION, "second answer");
        assertThat(fed.get(1).getMessages()).extracting(Message::getContent)
                .as("sealing mid-turn does not shrink the ingest delta")
                .containsExactly(LONG_QUESTION, "second answer");

        // The next turn's compaction widens the span over the answered question, and that range is sealed too.
        executor.execute(runtime, request(sessionId, "third question"));
        assertThat(llm.calls.get(3).get(1).getContent()).contains("THE SUMMARY");
        assertThat(llm.calls.get(4).get(1).getContent()).contains("THE SECOND SUMMARY");
        assertThat(llm.calls.get(4)).extracting(Message::getContent).contains("third question")
                .doesNotContain(LONG_QUESTION);
        final SessionLogState afterThird = repository.load(sessionId).orElseThrow().getLogState();
        assertThat(afterThird.getViewState().getSummarySpan())
                .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 4)));
        assertThat(afterThird.getConversationMessages()).extracting(Message::getContent)
                .containsExactly("third question", "third answer");
        final List<String> wholeLogAfterThird = transcripts.getLogReader().orElseThrow()
                .read(sessionId, 0, Long.MAX_VALUE).getEntries().stream()
                .filter(entry -> entry.getOrigin() == LogOrigin.CONVERSATION)
                .map(entry -> entry.getMessage().getContent()).toList();
        assertThat(wholeLogAfterThird).containsExactly("first question", "first answer", LONG_QUESTION, "second answer",
                "third question", "third answer");
    }

    @Test
    @DisplayName("an input over the auto threshold with nothing before it is sent as it is, and no compaction is reported")
    void anUnansweredInputAloneIsNotCompactedAndNotReportedAsOne() {
        final RecordingClient llm = new RecordingClient("the answer");
        final OrcaAgentExecutor executor = new OrcaAgentExecutorFactory().create(llm,
                new DefaultTranscriptManager(repository, SessionCheckpointMailbox.disabled(), SessionLogFormat.V2));
        final OrcaAgentRuntime runtime = runtime(executor, llm);
        final SessionId sessionId = SessionId.generate();

        final var result = executor.execute(runtime, request(sessionId, LONG_QUESTION));

        // One call: the turn itself. No summary was asked for, so the result carries no compaction record and the
        // record no span.
        assertThat(llm.calls).hasSize(1);
        assertThat(llm.calls.get(0)).extracting(Message::getContent).containsExactly(LONG_QUESTION);
        assertThat(result.getCompactionEvents()).isEmpty();
        assertThat(repository.load(sessionId).orElseThrow().getLogState().getViewState().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a tool result over the blocking limit is summarized with what precedes it, and the turn completes")
    void aToolResultOverTheBlockingLimitDoesNotFailTheTurn() {
        final LimitedProvider llm = new LimitedProvider(TINY.getBlockingLimit());
        final OrcaAgentExecutor executor = new OrcaAgentExecutorFactory().create(llm,
                new DefaultTranscriptManager(repository, SessionCheckpointMailbox.disabled(), SessionLogFormat.V2));
        final DefaultToolRegistry tools = new DefaultToolRegistry();
        tools.register(new ReportTool());
        final OrcaAgentRuntime runtime = runtime(executor, llm, tools);
        final SessionId sessionId = SessionId.generate();

        // Iteration 1 calls the tool; its result alone is over the blocking limit. The executor asks the engine once
        // before iteration 2 and sends what it is given, so a compaction that summarized only "read the report" would
        // hand the provider a request it rejects, and recovery has nothing it may drop.
        final var result = executor.execute(runtime, request(sessionId, "read the report"));

        assertThat(result.isSuccess()).as("the turn's outcome (%s; %d request(s) refused as too long)",
                result.getErrorMessage(), llm.rejected).isTrue();
        assertThat(llm.rejected).as("requests the provider refused as too long").isZero();
        assertThat(result.getFinalAnswer()).isEqualTo("the report says so");
        assertThat(llm.summaryCalls).as("one summary call for the whole view").hasSize(1);
        assertThat(llm.summaryCalls.get(0)).anySatisfy(message -> assertThat(message.hasToolResults()).isTrue());
        final List<Message> sentAfterCompaction = llm.agentCalls.get(1);
        assertThat(sentAfterCompaction).hasSize(2);
        assertThat(sentAfterCompaction.get(0).getContent()).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(sentAfterCompaction.get(1).getContent()).contains("THE SUMMARY");
        assertThat(result.getCompactionEvents()).hasSize(1);

        final SessionLogState stored = repository.load(sessionId).orElseThrow().getLogState();
        assertThat(stored.getViewState().getSummarySpan())
                .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 3)));
        assertThat(stored.getConversationMessages()).as("the log keeps the call, its result and the answer").hasSize(4);
    }

    // ============================== helpers ==============================

    private static OrcaAgentExecutionRequest request(SessionId sessionId, String userInput) {
        return OrcaAgentExecutionRequest.builder().userInput(userInput).sessionId(sessionId).principal(CALLER).build();
    }

    private OrcaAgentRuntime runtime(OrcaAgentExecutor executor, LlmClient llm) {
        return runtime(executor, llm, new DefaultToolRegistry());
    }

    private OrcaAgentRuntime runtime(OrcaAgentExecutor executor, LlmClient llm, DefaultToolRegistry toolRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();
        final DefaultCompactionEngine compactionEngine = new DefaultCompactionEngine(llm, estimator,
                executor.getHookExecutionManager());
        final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine,
                InMemoryModelContextWindowRegistry.builder().defaultLimits(TINY).build(), estimator);
        final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard)
                .compactionEngine(compactionEngine).recoveryStrategy(new DefaultPromptSizeRecoveryStrategy())
                .tokenEstimator(estimator).writeFormat(SessionLogFormat.V2).build();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                .executionEnvironmentProvider(TestExecutionEnvironments.provider(fileSystem)).contextEngine(engine)
                .build();
    }

    /** Returns a report of about 1700 estimated tokens: over the blocking limit on its own. */
    private static final class ReportTool extends AbstractTool {

        ReportTool() {
            super("Report", "returns the report",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("r".repeat(6000));
        }
    }

    /**
     * A provider with a prompt limit: an agent request estimated at or over it is refused with
     * {@link LlmPromptTooLongException}, as a provider refuses one over its window. The first agent request is
     * answered with a tool call, the later ones with text. A summary request is always answered — how large a summary
     * call may be is the compaction engine's matter, not what this double is for.
     */
    private static final class LimitedProvider implements LlmClient {

        private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();
        private final int limit;
        private final List<List<Message>> agentCalls = new ArrayList<>();
        private final List<List<Message>> summaryCalls = new ArrayList<>();
        private int rejected;

        LimitedProvider(int limit) {
            this.limit = limit;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            if (metadata.getFeature().filter(LlmCallMetadata.Feature.COMPACTION::equals).isPresent()) {
                summaryCalls.add(List.copyOf(messages));
                return LlmResponse.of("THE SUMMARY", List.of(), TokenUsage.of(5, 5, 10));
            }
            final int estimated = estimator.estimate(systemPrompt, messages);
            if (estimated >= limit) {
                rejected++;
                throw new LlmPromptTooLongException("prompt is too long: " + estimated + " tokens >= " + limit);
            }
            agentCalls.add(List.copyOf(messages));
            return agentCalls.size() == 1
                    ? LlmResponse.of("", List.of(ToolUse.of("t1", "Report", Map.of())), TokenUsage.of(5, 5, 10))
                    : LlmResponse.of("the report says so", List.of(), TokenUsage.of(5, 5, 10));
        }

        @Override
        public String getProviderName() {
            return "Limited";
        }
    }

    /** Answers from a script, in order, and records the messages of every call. */
    private static final class RecordingClient implements LlmClient {

        private final List<String> answers;
        private final List<List<Message>> calls = new ArrayList<>();

        RecordingClient(String... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            calls.add(List.copyOf(messages));
            final String answer = answers.isEmpty() ? "done" : answers.remove(0);
            return LlmResponse.of(answer, List.of(), TokenUsage.of(5, 5, 10));
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }
    }
}
