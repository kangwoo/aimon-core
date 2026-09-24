package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.context.DefaultContextEngine;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.command.DefaultCommandRegistry;
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
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.memory.ExecutionMemoryUpdate;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * The default context engine in view mode, driven by the executor over a version-2 transcript: compaction shrinks what
 * the model is sent and nothing else.
 *
 * <p>
 * Three claims of the context-engine design, end to end: the model sees exactly what the in-place mode showed it
 * (the {@code [boundary, summary]} pair), the record keeps every message that was said (L3), and the execution that
 * was compacted is still offered to memory as it was said (L4).
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
    @DisplayName("a compaction sends the marker pair, keeps the log whole and still feeds memory the original")
    void compactionShrinksOnlyTheView() {
        final RecordingClient llm = new RecordingClient("first answer", "THE SUMMARY", "second answer", "third answer");
        final List<ExecutionMemoryUpdate> fed = new ArrayList<>();
        final OrcaAgentExecutor executor = new OrcaAgentExecutorFactory().withExecutionMemorySink(fed::add).create(llm,
                new DefaultTranscriptManager(repository, SessionCheckpointMailbox.disabled(), SessionLogFormat.V2));
        final OrcaAgentRuntime runtime = runtime(executor, llm);
        final SessionId sessionId = SessionId.generate();

        executor.execute(runtime, request(sessionId, "first question"));
        executor.execute(runtime, request(sessionId, LONG_QUESTION));

        // Calls: turn 1, the summary, turn 2. The summary saw the whole view, the new question included.
        assertThat(llm.calls).hasSize(3);
        assertThat(llm.calls.get(1)).extracting(Message::getContent).contains("first question", "first answer",
                LONG_QUESTION);
        final List<Message> sentAfterCompaction = llm.calls.get(2);
        assertThat(sentAfterCompaction).hasSize(2);
        assertThat(sentAfterCompaction.get(0).getContent()).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(sentAfterCompaction.get(1).getContent()).startsWith(CompactBoundary.SUMMARY_OPEN_PREFIX)
                .contains("THE SUMMARY");

        final SessionLogState stored = repository.load(sessionId).orElseThrow().getLogState();
        assertThat(stored.getFormat()).isEqualTo(SessionLogFormat.V2);
        assertThat(stored.getConversationMessages()).extracting(Message::getContent).as("the log keeps every word")
                .containsExactly("first question", "first answer", LONG_QUESTION, "second answer");
        assertThat(stored.getViewState().getSummarySpan()).isPresent();

        assertThat(fed).hasSize(2);
        assertThat(fed.get(1).getMessages()).extracting(Message::getContent)
                .as("the compacted execution is ingested as it was said, not as its summary")
                .containsExactly(LONG_QUESTION, "second answer");

        // A later turn — its buffer rebuilt from the record — projects the same span and appends after it.
        executor.execute(runtime, request(sessionId, "third question"));
        final List<Message> resumed = llm.calls.get(3);
        assertThat(resumed.get(0).getContent()).isEqualTo(sentAfterCompaction.get(0).getContent());
        assertThat(resumed.get(1).getContent()).isEqualTo(sentAfterCompaction.get(1).getContent());
        assertThat(resumed).extracting(Message::getContent).contains("second answer", "third question");
    }

    // ============================== helpers ==============================

    private static OrcaAgentExecutionRequest request(SessionId sessionId, String userInput) {
        return OrcaAgentExecutionRequest.builder().userInput(userInput).sessionId(sessionId).principal(CALLER).build();
    }

    private OrcaAgentRuntime runtime(OrcaAgentExecutor executor, LlmClient llm) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();
        final DefaultCompactionEngine compactionEngine = new DefaultCompactionEngine(llm, estimator,
                executor.getHookExecutionManager());
        final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine,
                InMemoryModelContextWindowRegistry.builder().defaultLimits(TINY).build(), estimator);
        final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard)
                .compactionEngine(compactionEngine).tokenEstimator(estimator).writeFormat(SessionLogFormat.V2).build();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).contextEngine(engine).build();
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
