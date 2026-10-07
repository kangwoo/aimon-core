package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ContextEngineKind;
import at.aimon.core.agent.DefaultAgent;
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
import at.aimon.core.agent.prompt.SystemPromptParts;
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
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ModelContextWindowRegistry;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
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
 * <strong>The comparison profile.</strong> Those numbers are chosen to reach a rolling cycle in a few turns, and they
 * put the two engines on different windows, so they compare nothing. {@link #forComparison} is a second wiring for
 * the tasks of {@link ContextPressureTasks}: both engines over one window (effective 14,000 tokens), the rolling
 * engine at the ratios it ships with, {@code SessionHistory} registered for rolling only — as the runtime factory
 * does — a system prompt that names no tool, and a report tool that hands each report out once. The window is 16K and
 * not 8K because a {@code SessionHistory} result's size does not scale with the window; at 8K one retrieval can leave
 * the rolling engine nothing legal to do. The executor's client is wrapped in a {@link MainCallRecorder} there, and
 * only there (context-engine §13.11).
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

    /** Filler turns a scenario may spend reaching its rolling cycles; the assertions then say how many came. */
    static final int MAX_FILLER_TURNS = 12;

    static final String SYSTEM_PROMPT = "You are a terse assistant in a test. Follow the user's formatting instructions"
            + " exactly. When you need something from earlier in the conversation that is no longer shown to you, use"
            + " the " + SessionHistoryTool.TOOL_NAME + " tool to search the session's full history.";

    private static final ModelContextWindowRegistry SMALL_WINDOW = InMemoryModelContextWindowRegistry.builder()
            .defaultLimits(ModelContextLimits.builder().contextWindow(8_000).reservedOutputTokens(1_000)
                    .autoCompactBuffer(500).warningBuffer(500).blockingBuffer(300).build())
            .build();

    /**
     * The comparison profile's window: effective 14,000, the default engine's AUTO at 13,000 and blocking at 13,600.
     * The buffers keep the proportions the 200K defaults have.
     */
    static final ModelContextLimits COMPARISON_LIMITS = ModelContextLimits.builder().contextWindow(16_000)
            .reservedOutputTokens(2_000).autoCompactBuffer(1_000).warningBuffer(1_000).blockingBuffer(400).build();

    private static final ModelContextWindowRegistry COMPARISON_WINDOW = InMemoryModelContextWindowRegistry.builder()
            .defaultLimits(COMPARISON_LIMITS).build();

    /** What a comparison rig's report tool answers a report id it has already handed out with. */
    static final String ALREADY_DELIVERED = " was already delivered earlier in this conversation. The source does not"
            + " serve a report twice.";

    private final LlmClient client;
    private final Wiring wiring;
    private final SummaryCallRecorder summaries;
    private final LlmModel model;
    private final SessionId sessionId;
    private final InMemorySessionRecordStore records;
    private final InMemorySessionLogSegmentStore segments;
    private final DefaultTranscriptManager transcripts;
    private final OrcaAgentExecutor executor;
    private final OrcaAgentRuntime runtime;
    private final MainCallRecorder mainCalls;
    private final List<CompactionMetadata> compactions = new ArrayList<>();

    private ContextEngineLiveRig(LlmClient client, LlmModel model, Path baseDir, Wiring wiring, SessionId sessionId,
            InMemorySessionRecordStore records, InMemorySessionLogSegmentStore segments,
            SummaryCallRecorder summaries) {
        this.client = client;
        this.wiring = wiring;
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
        // Only the comparison wiring records the executor's calls; the two scenario wirings hand it the client as
        // they always did.
        this.mainCalls = wiring.comparison ? new MainCallRecorder(client, estimator) : null;
        this.executor = new OrcaAgentExecutor(mainCalls != null ? mainCalls : client, transcripts, toolManager,
                hookManager, new DefaultCommandExecutionManager(client),
                new DefaultSubagentExecutionManager(client, toolManager, hookManager));
        final CompactionEngine compactionEngine = DefaultCompactionEngine.withDefaults(summaries, estimator,
                hookManager);
        final boolean rolling = wiring.rolling;
        final ContextEngine engine;
        if (wiring.comparison) {
            engine = rolling
                    ? comparisonRollingEngine(compactionEngine, estimator)
                    : comparisonDefaultEngine(compactionEngine, estimator);
        } else {
            engine = rolling ? rollingEngine(compactionEngine, estimator) : viewModeEngine(compactionEngine, estimator);
        }

        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(baseDir.toString()));
        fileSystem.initialize();
        final DefaultToolRegistry tools = new DefaultToolRegistry();
        tools.register(new ReportTool(wiring.reports, wiring.reportDesk));
        if (rolling) {
            // The tool's own default cut: its fresh result may exceed the rolling tail, and the engine keeps it
            // verbatim until the model has answered it (context-engine §13.10).
            tools.register(new SessionHistoryTool());
        }
        final DefaultAgent agent = DefaultAgent.builder().name("ContextEngineLiveAgent").maxIterations(6)
                .systemPrompt(wiring.systemPrompt).model(model).build();
        this.runtime = OrcaAgentRuntime.builder().id(AgentRuntimeId.from(agent)).agent(agent).toolRegistry(tools)
                .hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                .contextEngine(engine).build();
    }

    /** A rig over the rolling engine, with a fresh session. */
    static ContextEngineLiveRig rolling(LlmClient client, LlmModel model, Path baseDir) {
        return new ContextEngineLiveRig(client, model, baseDir, Wiring.scenario(true), SessionId.generate(),
                new InMemorySessionRecordStore(), new InMemorySessionLogSegmentStore(),
                new SummaryCallRecorder(client));
    }

    /** A rig over the default engine in view mode (a version-2 transcript), with a fresh session. */
    static ContextEngineLiveRig defaultViewMode(LlmClient client, LlmModel model, Path baseDir) {
        return new ContextEngineLiveRig(client, model, baseDir, Wiring.scenario(false), SessionId.generate(),
                new InMemorySessionRecordStore(), new InMemorySessionLogSegmentStore(),
                new SummaryCallRecorder(client));
    }

    /**
     * A rig in the comparison profile, with a fresh session: {@code kind}'s engine over {@link #COMPARISON_LIMITS},
     * the tools the runtime factory would register for it, and a report tool that hands each of {@code reports} out
     * once.
     */
    static ContextEngineLiveRig forComparison(ContextEngineKind kind, LlmClient client, LlmModel model, Path baseDir,
            Map<String, String> reports) {
        Objects.requireNonNull(kind, "kind cannot be null");
        return new ContextEngineLiveRig(client, model, baseDir,
                Wiring.comparison(kind == ContextEngineKind.ROLLING, reports), SessionId.generate(),
                new InMemorySessionRecordStore(), new InMemorySessionLogSegmentStore(),
                new SummaryCallRecorder(client));
    }

    /** The view size at which the comparison profile's rolling engine compacts: 60% of the effective window. */
    static int comparisonRollingThreshold() {
        return Math.min(
                (int) (RollingContextEngine.DEFAULT_AUTO_COMPACT_RATIO * COMPARISON_LIMITS.getEffectiveContextWindow()),
                COMPARISON_LIMITS.getAutoCompactThreshold());
    }

    /** The shipped ratios, untouched: what is measured is the configuration that is deployed. */
    private static ContextEngine comparisonRollingEngine(CompactionEngine compactionEngine, TokenEstimator estimator) {
        return RollingContextEngine.builder().compactionEngine(compactionEngine)
                .modelContextWindowRegistry(COMPARISON_WINDOW).tokenEstimator(estimator)
                .writeFormat(SessionLogFormat.V2).build();
    }

    @SuppressWarnings("deprecation") // as viewModeEngine: the guard's type is what selects view mode
    private static ContextEngine comparisonDefaultEngine(CompactionEngine compactionEngine, TokenEstimator estimator) {
        final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine, COMPARISON_WINDOW, estimator);
        return DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(compactionEngine)
                .tokenEstimator(estimator).writeFormat(SessionLogFormat.V2).build();
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
        return new ContextEngineLiveRig(client, model, baseDir, wiring, sessionId, reloaded, segments, summaries);
    }

    /** Runs one turn of this rig's session and requires it to succeed. */
    OrcaAgentExecutionResult turn(String input) {
        final OrcaAgentExecutionResult result = tryTurn(input);
        assertThat(result.isSuccess()).as("turn '%s' failed: %s", input, result.getErrorMessage()).isTrue();
        return result;
    }

    /**
     * Runs one turn of this rig's session and returns whatever came of it. A comparison run counts a failed turn; it
     * does not stop on one.
     */
    OrcaAgentExecutionResult tryTurn(String input) {
        final OrcaAgentExecutionResult result = executor.execute(runtime,
                OrcaAgentExecutionRequest.builder().sessionId(sessionId).userInput(input).build());
        compactions.addAll(result.getCompactionEvents());
        return result;
    }

    /** What {@code /compact} does, against this rig's saved session: one MANUAL compaction, then a save. */
    CompactionResult compactNow() {
        final TranscriptBuffer buffer = transcripts.initialize(sessionId, wiring.systemPrompt);
        final ContextRequest request = ContextRequest.builder().transcriptBuffer(buffer)
                .systemPrompt(wiring.systemPrompt).model(model).hookRegistry(runtime.getHookRegistry())
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

    /** The executor's calls. Recorded in the comparison profile only. */
    MainCallRecorder mainCalls() {
        if (mainCalls == null) {
            throw new IllegalStateException("only a comparison rig records the executor's calls");
        }
        return mainCalls;
    }

    /** Which reports were handed out; empty outside the comparison profile, where a report is served every time. */
    Optional<ReportDesk> reportDesk() {
        return Optional.ofNullable(wiring.reportDesk);
    }

    /** The effective window this rig's engine decides against. */
    int effectiveWindow() {
        return wiring.limits.getEffectiveContextWindow();
    }

    /** The view size past which this rig's engine refuses to send without compacting. */
    int blockingLimit() {
        return wiring.limits.getBlockingLimit();
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
     * {@code SessionHistory} match cut at {@link SessionHistoryTool#DEFAULT_MAX_RESULT_CHARS} still shows it.
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

    /**
     * Returns the report filed under the id asked for and a short one for anything else. With a {@link ReportDesk} a
     * report is handed out once; asked again, the tool says so — as a result, not an error, since an error invites a
     * retry.
     */
    private static final class ReportTool extends AbstractTool {

        private final Map<String, String> reports;
        private final ReportDesk desk;

        ReportTool(Map<String, String> reports, ReportDesk desk) {
            super(REPORT_TOOL, "Fetches an operations report by id.",
                    Map.of("type", "object", "additionalProperties", false, "properties",
                            Map.of("report_id", Map.of("type", "string", "description", "The report id, e.g. R-1")),
                            "required", List.of("report_id")));
            this.reports = reports;
            this.desk = desk;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            Objects.requireNonNull(input, "Input cannot be null");
            Objects.requireNonNull(context, "Context cannot be null");
            final String id = input.getStringOrNull("report_id");
            if (id == null || id.isBlank()) {
                return ToolResult.error("report_id is required");
            }
            for (Map.Entry<String, String> report : reports.entrySet()) {
                if (!report.getKey().equalsIgnoreCase(id.trim())) {
                    continue;
                }
                if (desk != null && !desk.handOut(report.getKey())) {
                    return ToolResult.success("Report " + report.getKey() + ALREADY_DELIVERED);
                }
                return ToolResult.success(report.getValue());
            }
            return ToolResult.success("Report " + id + ": no entries.");
        }
    }

    /**
     * Which reports a comparison rig has handed out. A source that serves a thing once — a rotated log, a consumed
     * queue — is what the comparison's report tool plays, and this is where that source's state lives: in the rig, for
     * one rig's lifetime, so two engines' runs cannot touch each other. Thread-safe.
     */
    static final class ReportDesk {

        private final Set<String> served = ConcurrentHashMap.newKeySet();
        private final AtomicInteger refused = new AtomicInteger();

        /** Marks {@code id} handed out. False, and counted, when it already was. */
        boolean handOut(String id) {
            if (served.add(id)) {
                return true;
            }
            refused.incrementAndGet();
            return false;
        }

        boolean served(String id) {
            return served.contains(id);
        }

        /** How many times a report was asked for again and not given. */
        int refusedCount() {
            return refused.get();
        }
    }

    /** What separates one wiring of this rig from another. Immutable but for the desk it carries. */
    private static final class Wiring {

        private final boolean rolling;
        private final boolean comparison;
        private final String systemPrompt;
        private final ModelContextLimits limits;
        private final Map<String, String> reports;
        private final ReportDesk reportDesk;

        private Wiring(boolean rolling, boolean comparison, String systemPrompt, ModelContextLimits limits,
                Map<String, String> reports, ReportDesk reportDesk) {
            this.rolling = rolling;
            this.comparison = comparison;
            this.systemPrompt = systemPrompt;
            this.limits = limits;
            this.reports = reports;
            this.reportDesk = reportDesk;
        }

        /** The live scenario's wiring: the small window for rolling, the framework's table for the default engine. */
        static Wiring scenario(boolean rolling) {
            return new Wiring(rolling, false, SYSTEM_PROMPT,
                    rolling ? SMALL_WINDOW.resolve("") : InMemoryModelContextWindowRegistry.withDefaults().resolve(""),
                    Map.of("R-1", plantedReport()), null);
        }

        static Wiring comparison(boolean rolling, Map<String, String> reports) {
            return new Wiring(rolling, true, ContextPressureTasks.SYSTEM_PROMPT, COMPARISON_LIMITS,
                    Map.copyOf(Objects.requireNonNull(reports, "reports cannot be null")), new ReportDesk());
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

    /**
     * The client a comparison rig's executor calls through: forwards to the real one and records each call — the
     * estimated size of the view it sent, the input tokens the provider reported, and the tools the answer asked for.
     *
     * <p>
     * <strong>Every overload goes to the same overload of the delegate.</strong> The executor's gateway calls the
     * system-prompt-parts overloads, which are {@code default} methods on {@link LlmClient} that a provider client
     * overrides for cache boundaries, cancellation and streaming. Forwarding only the {@code String} overload, as
     * {@link SummaryCallRecorder} may, would let the defaults concatenate the parts and change the request the
     * provider is sent — and the rig would measure a request no unwrapped client makes.
     */
    static final class MainCallRecorder implements LlmClient {

        private final LlmClient delegate;
        private final TokenEstimator estimator;
        private final List<Integer> sentViewTokens = new CopyOnWriteArrayList<>();
        private final List<String> toolUses = new CopyOnWriteArrayList<>();
        private final AtomicLong reportedPromptTokens = new AtomicLong();

        MainCallRecorder(LlmClient delegate, TokenEstimator estimator) {
            this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
            this.estimator = Objects.requireNonNull(estimator, "estimator cannot be null");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
            sent(systemPrompt, messages);
            return received(delegate.sendMessage(systemPrompt, messages, tools));
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            sent(systemPrompt, messages);
            return received(delegate.sendMessage(systemPrompt, messages, tools, modelConfig));
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            sent(systemPrompt, messages);
            return received(delegate.sendMessage(systemPrompt, messages, tools, modelConfig, metadata));
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
            sent(systemPromptParts.concatenated(), messages);
            return received(delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, metadata));
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            sent(systemPromptParts.concatenated(), messages);
            return received(
                    delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, metadata, cancellation));
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink) {
            sent(systemPromptParts.concatenated(), messages);
            return received(delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata,
                    options, sink));
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            sent(systemPromptParts.concatenated(), messages);
            return received(delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata,
                    options, sink, cancellation));
        }

        @Override
        public String getProviderName() {
            return delegate.getProviderName();
        }

        @Override
        public Optional<String> getDefaultModelName() {
            return delegate.getDefaultModelName();
        }

        private void sent(String systemPrompt, List<Message> messages) {
            sentViewTokens.add(estimator.estimate(systemPrompt, messages));
        }

        private LlmResponse received(LlmResponse response) {
            if (response.hasTokenUsage()) {
                reportedPromptTokens.addAndGet(response.getTokenUsage().getPromptTokens());
            }
            for (ToolUse use : response.getToolUses()) {
                toolUses.add(use.getName());
            }
            return response;
        }

        /** The calls made, counting one that the provider then refused. */
        int count() {
            return sentViewTokens.size();
        }

        /** The estimated size of each call's view, system prompt included, in call order. */
        List<Integer> sentViewTokens() {
            return List.copyOf(sentViewTokens);
        }

        /**
         * The input tokens the provider reported, summed over the calls that were answered. Both provider clients put
         * the provider's whole input count here, and neither asks for prompt caching, so the two are the same
         * quantity; a scripted client reports none.
         */
        long reportedPromptTokens() {
            return reportedPromptTokens.get();
        }

        /** How many times an answer asked for the tool named {@code toolName}. */
        int toolUsesNamed(String toolName) {
            return (int) toolUses.stream().filter(toolName::equals).count();
        }
    }
}
