package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.context.ContextCaller;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.DefaultContextEngine;
import at.aimon.core.agent.context.RollingContextEngine;
import at.aimon.core.agent.context.ViewProjection;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionLogStorage;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ModelContextWindowRegistry;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.subagent.task.codec.JsonSessionSnapshotCodec;
import at.aimon.core.tools.session.SessionHistoryTool;

/**
 * The wiring the context-engine live tests share: a real {@link LlmClient} behind the real executor path, a version-2
 * transcript sealed into an in-memory segment store, and a context engine whose thresholds are low enough that a few
 * calls cross them.
 *
 * <p>
 * <strong>Why the runtime is built here and not by {@code OrcaAgentRuntimeFactory}.</strong> The factory builds its
 * engines over the framework's model-window table, where the smallest window is tens of thousands of tokens; reaching a
 * rolling cycle there costs hundreds of thousands of billed tokens. The engine's own builder takes a
 * {@link ModelContextWindowRegistry}, so this rig hands it a small window instead — every other collaborator is the one
 * the factory would build ({@link DefaultCompactionEngine}, {@link SessionHistoryTool},
 * {@link HeuristicTokenEstimator}).
 * No production seam was added for the tests.
 *
 * <p>
 * <strong>What the numbers do.</strong> Effective window 7,000 tokens; the rolling engine triggers at 30% of it
 * (2,100), keeps a 10% tail and aims for an 8% summary, and elides tool results over 300 tokens. A filler turn adds
 * about 400 tokens, so a rolling cycle comes every three turns or so once
 * the first one is in. The summary calls go through a
 * {@link SummaryCallRecorder}, so a test can see every one of them, and whether the provider accepted it.
 *
 * <p>
 * Kept in step with the copy in {@code aimon-llm-anthropic}: the two provider modules share no test source set, and
 * this
 * wiring is the part of the scenario that does not depend on the provider.
 */
final class ContextEngineLiveRig {

    /** The fact planted in the first report, and asked for at the end. */
    static final String VAULT_CODE = "ZEBRA-7731";

    static final String REPORT_TOOL = "fetch_report";

    /**
     * How much of each matched message {@code SessionHistory} returns. Kept well under the rolling tail budget (700
     * tokens here): a fresh tool result larger than the tail is absorbed by the next cut and elided before the model
     * has
     * read it. That is an engine hazard in its own right, and not what this scenario sets out to test.
     */
    static final int HISTORY_RESULT_CHARS = 800;

    /** Filler turns a scenario may spend reaching its rolling cycles; the assertions then say how many came. */
    static final int MAX_FILLER_TURNS = 12;

    static final String SYSTEM_PROMPT = "You are a terse assistant in a test. Follow the user's formatting instructions"
            + " exactly. When you need something from earlier in the conversation that is no longer shown to you, use"
            + " the " + SessionHistoryTool.TOOL_NAME + " tool to search the session's full history.";

    private static final ModelContextWindowRegistry SMALL_WINDOW = InMemoryModelContextWindowRegistry.builder()
            .defaultLimits(ModelContextLimits.builder().contextWindow(8_000).reservedOutputTokens(1_000)
                    .autoCompactBuffer(500).warningBuffer(500).blockingBuffer(300).build())
            .build();

    private final LlmClient client;
    private final SummaryCallRecorder summaries;
    private final LlmModel model;
    private final SessionId sessionId;
    private final InMemorySessionRecordStore records;
    private final InMemorySessionLogSegmentStore segments;
    private final DefaultTranscriptManager transcripts;
    private final OrcaAgentExecutor executor;
    private final OrcaAgentRuntime runtime;
    private final List<CompactionMetadata> compactions = new ArrayList<>();

    private ContextEngineLiveRig(LlmClient client, LlmModel model, Path baseDir, boolean rolling, SessionId sessionId,
            InMemorySessionRecordStore records, InMemorySessionLogSegmentStore segments,
            SummaryCallRecorder summaries) {
        this.client = client;
        this.model = model;
        this.sessionId = sessionId;
        this.records = records;
        this.segments = segments;
        this.summaries = summaries;
        final TokenEstimator estimator = new HeuristicTokenEstimator();
        final SessionLogStorage storage = SessionLogStorage.builder(segments).minSealTokens(200)
                .tokenEstimator(estimator).build();
        this.transcripts = new DefaultTranscriptManager(records, SessionCheckpointMailbox.disabled(),
                SessionLogFormat.V2, storage);

        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        this.executor = new OrcaAgentExecutor(client, transcripts, toolManager, hookManager,
                new DefaultCommandExecutionManager(client),
                new DefaultSubagentExecutionManager(client, toolManager, hookManager));
        final CompactionEngine compactionEngine = DefaultCompactionEngine.withDefaults(summaries, estimator,
                hookManager);
        final ContextEngine engine = rolling
                ? rollingEngine(compactionEngine, estimator)
                : viewModeEngine(compactionEngine, estimator);

        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(baseDir.toString()));
        fileSystem.initialize();
        final DefaultToolRegistry tools = new DefaultToolRegistry();
        tools.register(new ReportTool());
        if (rolling) {
            tools.register(new SessionHistoryTool(SessionHistoryTool.DEFAULT_MAX_SCAN_TOKENS, HISTORY_RESULT_CHARS,
                    estimator));
        }
        final DefaultAgent agent = DefaultAgent.builder().name("ContextEngineLiveAgent").maxIterations(6)
                .systemPrompt(SYSTEM_PROMPT).model(model).build();
        this.runtime = OrcaAgentRuntime.builder().id(AgentRuntimeId.from(agent)).agent(agent).toolRegistry(tools)
                .hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).contextEngine(engine).build();
    }

    /** A rig over the rolling engine, with a fresh session. */
    static ContextEngineLiveRig rolling(LlmClient client, LlmModel model, Path baseDir) {
        return new ContextEngineLiveRig(client, model, baseDir, true, SessionId.generate(),
                new InMemorySessionRecordStore(), new InMemorySessionLogSegmentStore(),
                new SummaryCallRecorder(client));
    }

    /** A rig over the default engine in view mode (a version-2 transcript), with a fresh session. */
    static ContextEngineLiveRig defaultViewMode(LlmClient client, LlmModel model, Path baseDir) {
        return new ContextEngineLiveRig(client, model, baseDir, false, SessionId.generate(),
                new InMemorySessionRecordStore(), new InMemorySessionLogSegmentStore(),
                new SummaryCallRecorder(client));
    }

    private static ContextEngine rollingEngine(CompactionEngine compactionEngine, TokenEstimator estimator) {
        return RollingContextEngine.builder().compactionEngine(compactionEngine)
                .modelContextWindowRegistry(SMALL_WINDOW).tokenEstimator(estimator).writeFormat(SessionLogFormat.V2)
                .autoCompactRatio(0.3).tailTokenRatio(0.1).summaryTokenRatio(0.08).minTailRatio(0.04)
                .pruneMinTokens(300).build();
    }

    @SuppressWarnings("deprecation") // view mode is decided by the guard's type; DefaultCompactionGuard is the one
    private static ContextEngine viewModeEngine(CompactionEngine compactionEngine, TokenEstimator estimator) {
        // The framework's window table, so AUTO never fires here: the only compaction is the one the test forces.
        final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine,
                InMemoryModelContextWindowRegistry.withDefaults(), estimator);
        return DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(compactionEngine)
                .tokenEstimator(estimator).writeFormat(SessionLogFormat.V2).build();
    }

    /**
     * Saves nothing new: re-reads this rig's session record, puts it through the version-2 codec, and returns a rig
     * over a fresh record store holding only the decoded record, sharing this rig's segment store — what a restart on
     * another node over the same backends looks like.
     */
    ContextEngineLiveRig reloadThroughCodec(Path baseDir) {
        final SessionSnapshot stored = storedSnapshot();
        final JsonSessionSnapshotCodec codec = new JsonSessionSnapshotCodec(SessionLogFormat.V2);
        final SessionSnapshot decoded = codec.decode(codec.encode(stored));
        assertThat(decoded.getLogState().getFormat()).as("the record round-trips as version 2")
                .isEqualTo(SessionLogFormat.V2);
        assertThat(decoded.getLogState()).as("the version-2 codec round trip is lossless")
                .isEqualTo(stored.getLogState());
        final InMemorySessionRecordStore reloaded = new InMemorySessionRecordStore();
        reloaded.mergeFromSnapshot(decoded);
        return new ContextEngineLiveRig(client, model, baseDir,
                runtime.getContextEngine() instanceof RollingContextEngine, sessionId, reloaded, segments, summaries);
    }

    /** Runs one turn of this rig's session and requires it to succeed. */
    OrcaAgentExecutionResult turn(String input) {
        final OrcaAgentExecutionResult result = executor.execute(runtime, OrcaAgentExecutionRequest.builder()
                .sessionId(sessionId).userInput(input).userContextInjection(false).build());
        assertThat(result.isSuccess()).as("turn '%s' failed: %s", input, result.getErrorMessage()).isTrue();
        compactions.addAll(result.getCompactionEvents());
        return result;
    }

    /** What {@code /compact} does, against this rig's saved session: one MANUAL compaction, then a save. */
    CompactionResult compactNow() {
        final TranscriptBuffer buffer = transcripts.initialize(sessionId, SYSTEM_PROMPT);
        final ContextRequest request = ContextRequest.builder().transcriptBuffer(buffer).systemPrompt(SYSTEM_PROMPT)
                .model(model).hookRegistry(runtime.getHookRegistry()).environment(runtime.getEnvironment())
                .caller(ContextCaller.session()).build();
        final CompactionResult result = runtime.getContextEngine().compactNow(request, null);
        if (result.isSuccess()) {
            transcripts.save(buffer);
        }
        return result;
    }

    /** The session's log as stored now. */
    SessionLogState storedLog() {
        return storedSnapshot().getLogState();
    }

    /** The messages the next call would be sent, projected from the stored log. */
    List<Message> storedView() {
        return ViewProjection.of(storedLog()).getMessages();
    }

    private SessionSnapshot storedSnapshot() {
        return SessionSnapshot.from(
                records.load(sessionId).orElseThrow(() -> new AssertionError("no record stored for " + sessionId)));
    }

    /** Every compaction the executor reported across this rig's turns, in order. */
    List<CompactionMetadata> compactions() {
        return List.copyOf(compactions);
    }

    long compactionsOfKind(CompactionKind kind) {
        return compactions.stream().filter(metadata -> metadata.getKind() == kind).count();
    }

    SummaryCallRecorder summaries() {
        return summaries;
    }

    /** The seq of the logged tool result that carries {@code needle}, if any. */
    static Optional<Long> seqOfToolResultContaining(SessionLogState log, String needle) {
        return log.getEntries().stream()
                .filter(entry -> entry.getMessage().getToolUseResults().stream()
                        .anyMatch(result -> result.getContent() != null && result.getContent().contains(needle)))
                .map(entry -> entry.getSeq()).findFirst();
    }

    /**
     * How many entries the log holds, counting those sealed out of the record into segments: a version-2 log is
     * append-only, and sealing moves entries, it does not remove them.
     */
    static int loggedEntryCount(SessionLogState log) {
        return log.getEntries().size() + log.getManifest().stream().mapToInt(line -> line.getEntryCount()).sum();
    }

    /** Whether {@code response}'s turn called {@link #REPORT_TOOL}. */
    static boolean calledTheReportTool(OrcaAgentExecutionResult result) {
        return result.getConversationHistory().stream().flatMap(message -> message.getToolUses().stream())
                .anyMatch(use -> REPORT_TOOL.equals(use.getName()));
    }

    /**
     * A roughly 400-token note for a filler turn: plain prose, so the rolling engine cannot shrink it by eliding a tool
     * result and has to summarize.
     */
    static String fillerNote(int n) {
        final StringBuilder note = new StringBuilder("Meeting note ").append(n)
                .append(". Store this note; reply with only the word: noted.\n");
        final String[] topics = {"the staging database migration", "the on-call rotation",
                "the quarterly capacity plan", "the alert noise review", "the backup restore drill",
                "the certificate renewal calendar"};
        for (int i = 0; i < 10; i++) {
            note.append("Item ").append(i + 1).append(": the team discussed ").append(topics[(n + i) % topics.length])
                    .append(" and agreed to revisit it after the next release, with owner number ").append(n * 10 + i)
                    .append(" tracking the follow-up.\n");
        }
        return note.toString();
    }

    /**
     * The report the first turn fetches: about a thousand tokens of log lines with the vault code near the top, so a
     * {@code SessionHistory} match cut at {@link #HISTORY_RESULT_CHARS} still shows it.
     */
    static String plantedReport() {
        final StringBuilder report = new StringBuilder("Report R-1 (operations log excerpt)\n");
        for (int i = 0; i < 40; i++) {
            if (i == 5) {
                report.append("line 5: NOTICE the vault access code for this environment is ").append(VAULT_CODE)
                        .append(".\n");
                continue;
            }
            report.append("line ").append(i).append(": INFO scheduled job completed on host app-").append(i % 7)
                    .append(" with exit status 0 in ").append(100 + i).append(" ms\n");
        }
        return report.toString();
    }

    /** Returns the planted report for {@code R-1} and a short one for anything else. */
    private static final class ReportTool extends AbstractTool {

        ReportTool() {
            super(REPORT_TOOL, "Fetches an operations report by id.",
                    Map.of("type", "object", "additionalProperties", false, "properties",
                            Map.of("report_id", Map.of("type", "string", "description", "The report id, e.g. R-1")),
                            "required", List.of("report_id")));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            Objects.requireNonNull(input, "Input cannot be null");
            Objects.requireNonNull(context, "Context cannot be null");
            final String id = input.getStringOrNull("report_id");
            if (id == null || id.isBlank()) {
                return ToolResult.error("report_id is required");
            }
            return ToolResult
                    .success("R-1".equalsIgnoreCase(id.trim()) ? plantedReport() : "Report " + id + ": no entries.");
        }
    }

    /**
     * The client the compaction engine summarizes through: forwards to the real one and records each summary call — the
     * role its input ends on, and whether the provider refused it.
     */
    static final class SummaryCallRecorder implements LlmClient {

        private final LlmClient delegate;
        private final List<Role> lastRoles = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();

        SummaryCallRecorder(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            // An empty input has no last role; record it as null so the assertion names what went wrong.
            lastRoles.add(messages.isEmpty() ? null : messages.get(messages.size() - 1).getRole());
            try {
                return delegate.sendMessage(systemPrompt, messages, tools, modelConfig, metadata);
            } catch (RuntimeException e) {
                failures.add(e.toString());
                throw e;
            }
        }

        @Override
        public String getProviderName() {
            return delegate.getProviderName();
        }

        @Override
        public Optional<String> getDefaultModelName() {
            return delegate.getDefaultModelName();
        }

        int count() {
            return lastRoles.size();
        }

        List<Role> lastRoles() {
            return List.copyOf(lastRoles);
        }

        List<String> failures() {
            return List.copyOf(failures);
        }
    }
}
