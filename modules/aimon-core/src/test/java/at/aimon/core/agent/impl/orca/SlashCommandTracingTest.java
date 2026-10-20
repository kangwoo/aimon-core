package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.command.system.CompactCommand;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
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
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.core.tracing.impl.TracingLlmClient;

/**
 * ST-1(1): a slash command turn opens a {@code COMMAND} span under its {@code TURN}, and a fork the command starts
 * nests under it — so the trace says which command launched the subagent.
 */
@DisplayName("Slash command tracing — TURN → COMMAND → SUBAGENT")
class SlashCommandTracingTest {

    private static final String SESSION = "conv-cmd";

    @TempDir
    Path tempDir;

    private InMemoryTraceSpanStore store;
    private Tracer tracer;
    private FixedAnswerLlmClient answers;
    private LlmClient llm;
    private DefaultHookExecutionManager hookManager;
    private DefaultSubagentExecutionManager subagentManager;
    private OrcaAgentExecutor executor;
    private InMemorySkillRegistry skillRegistry;
    private InMemorySubagentRegistry subagentRegistry;
    private ProbeCommand probe;
    private LocalExecutionEnvironmentProvider environmentProvider;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceSpanStore();
        tracer = new DefaultTracer(store, SpanExporter.noop());
        answers = new FixedAnswerLlmClient("reviewed");
        llm = new TracingLlmClient(answers, tracer);
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        hookManager = new DefaultHookExecutionManager();
        // The convenience constructor: withTracer reaches the executor it built (ST-2), so the fork has iterations.
        subagentManager = new DefaultSubagentExecutionManager(llm, toolManager, hookManager).withTracer(tracer);
        executor = new OrcaAgentExecutor(llm, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, new DefaultCommandExecutionManager(llm), subagentManager);
        executor.tracer = tracer;
        skillRegistry = new InMemorySkillRegistry();
        subagentRegistry = new InMemorySubagentRegistry();
        probe = new ProbeCommand();
    }

    @AfterEach
    void tearDown() {
        subagentManager.close();
        if (environmentProvider != null) {
            environmentProvider.close();
        }
    }

    @Test
    @DisplayName("a fork-mode skill typed as /review nests TURN → COMMAND → SUBAGENT → ITERATION → LLM")
    void forkSkillNestsUnderTheCommand() {
        skillRegistry.add(forkSkill("review", "code-reviewer", "Review: $ARGUMENTS"));
        subagentRegistry.add(Subagent.of("code-reviewer",
                SubagentMetadata.builder().description("reviewer").maxIterations(2).build(),
                SubagentContent.of("you review")));

        final OrcaAgentExecutionResult result = executor.execute(runtime(), request("/review Foo.java"));

        assertThat(result.isSuccess()).isTrue();
        final List<TraceSpan> spans = store.bySession(SESSION);
        final TraceSpan turn = only(spans, SpanType.TURN);
        final TraceSpan command = only(spans, SpanType.COMMAND);
        assertThat(command.getName()).isEqualTo("command:/review");
        assertThat(command.getParentSpanId()).contains(turn.getSpanId());
        assertThat(command.getStatus()).isEqualTo(SpanStatus.OK);
        final TraceSpan subagent = only(spans, SpanType.SUBAGENT);
        assertThat(subagent.getParentSpanId()).contains(command.getSpanId());
        final TraceSpan iteration = only(spans, SpanType.ITERATION);
        assertThat(iteration.getParentSpanId()).contains(subagent.getSpanId());
        assertThat(only(spans, SpanType.LLM).getParentSpanId()).contains(iteration.getSpanId());
        assertThat(spans).allSatisfy(s -> assertThat(s.getTraceId()).isEqualTo(turn.getSpanId()));
    }

    @Test
    @DisplayName("a direct command leaves a childless OK COMMAND span, and its tools see metadata pointing at it")
    void directCommandLeavesAChildlessCommandSpan() {
        final OrcaAgentExecutionResult result = executor.execute(runtime(), request("/probe"));

        assertThat(result.isSuccess()).isTrue();
        final List<TraceSpan> spans = store.bySession(SESSION);
        final TraceSpan turn = only(spans, SpanType.TURN);
        final TraceSpan command = only(spans, SpanType.COMMAND);
        assertThat(command.getName()).isEqualTo("command:/probe");
        assertThat(command.getParentSpanId()).contains(turn.getSpanId());
        assertThat(command.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(spans).noneSatisfy(s -> assertThat(s.getParentSpanId()).contains(command.getSpanId()));
        assertThat(outputsOf(command)).containsEntry("success", true).containsEntry("responseChars", "probed".length());

        final SpanContext seenByCommand = SpanContext.readFrom(probe.metadata).orElseThrow();
        assertThat(seenByCommand.getSpanId()).isEqualTo(command.getSpanId());
    }

    @Test
    @DisplayName("an unknown command ends its COMMAND span in ERROR and the turn fails as before")
    void unknownCommandEndsInError() {
        final OrcaAgentExecutionResult result = executor.execute(runtime(), request("/nope"));

        assertThat(result.isSuccess()).isFalse();
        final TraceSpan command = only(store.bySession(SESSION), SpanType.COMMAND);
        assertThat(command.getName()).isEqualTo("command:/nope");
        assertThat(command.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(outputsOf(command)).containsEntry("success", false);
    }

    @Test
    @DisplayName("summary-only policy records argsChars but not the raw arguments; a content policy records both")
    void rawArgumentsFollowThePayloadPolicy() {
        executor.execute(runtime(), request("/probe some args"));
        assertThat(inputsOf(only(store.bySession(SESSION), SpanType.COMMAND))).containsEntry("command", "probe")
                .containsEntry("argsChars", "some args".length()).doesNotContainKey("rawArguments");

        store = new InMemoryTraceSpanStore();
        tracer = new DefaultTracer(store, SpanExporter.noop());
        executor.tracer = tracer;
        executor.tracePayloadPolicy = TracePayloadPolicy.full();
        executor.execute(runtime(), request("/probe some args"));
        assertThat(inputsOf(only(store.bySession(SESSION), SpanType.COMMAND)))
                .containsEntry("argsChars", "some args".length()).containsEntry("rawArguments", "some args");
    }

    @Test
    @DisplayName("ST-10/ST-5: /compact nests COMMAND > COMPACTION > LLM, in the turn's trace")
    void compactSummaryCallNestsUnderTheCommand() {
        final DefaultCompactionEngine compactionEngine = DefaultCompactionEngine.withDefaults(llm,
                new HeuristicTokenEstimator(), hookManager);
        final CompactCommand compact = new CompactCommand(
                compactionEngine, new DefaultCompactionGuard(compactionEngine,
                        InMemoryModelContextWindowRegistry.withDefaults(), new HeuristicTokenEstimator()),
                new DefaultHookRegistry(), hookManager);
        final OrcaAgentRuntime runtime = runtime(compact);
        executor.execute(runtime, request("hi")); // something to compact

        final Principal alice = Principal.user("alice");
        final OrcaAgentExecutionResult result = executor.execute(runtime, OrcaAgentExecutionRequest.builder()
                .userInput("/compact").sessionId(SessionId.of(SESSION)).principal(alice).build());

        assertThat(result.isSuccess()).isTrue();
        final List<TraceSpan> spans = store.bySession(SESSION);
        final TraceSpan command = only(spans, SpanType.COMMAND);
        assertThat(command.getName()).isEqualTo("command:/compact");
        final TraceSpan summary = spans.stream().filter(s -> s.getType() == SpanType.LLM)
                .filter(s -> LlmCallMetadata.Feature.COMPACTION.equals(s.getAttributes().get("feature"))).findFirst()
                .orElseThrow(() -> new AssertionError("no COMPACTION LLM span"));
        final TraceSpan compaction = only(spans, SpanType.COMPACTION);
        assertThat(compaction.getParentSpanId()).contains(command.getSpanId());
        assertThat(compaction.getName()).isEqualTo("compaction:manual");
        assertThat(compaction.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(inputsOf(compaction)).containsEntry("trigger", "MANUAL");
        assertThat(outputsOf(compaction)).containsEntry("outcome", "compacted");
        assertThat(summary.getParentSpanId()).contains(compaction.getSpanId());
        assertThat(summary.getTraceId()).isEqualTo(command.getTraceId());
        assertThat(summary.getSessionId()).isEqualTo(command.getSessionId());
        // Only the span position is taken from the command context: the call is still attributed to /compact.
        final LlmCallMetadata summaryCall = answers.metadata.stream()
                .filter(m -> m.getFeature().filter(LlmCallMetadata.Feature.COMPACTION::equals).isPresent()).findFirst()
                .orElseThrow(() -> new AssertionError("no COMPACTION call"));
        assertThat(summaryCall.getComponent()).contains("compact-command");
        assertThat(summaryCall.getPrincipal()).contains(alice);
        assertThat(summaryCall.getTraceId()).contains(SESSION);
    }

    @Test
    @DisplayName("ST-5: /compact on an empty conversation opens no COMPACTION span")
    void compactOfAnEmptyConversationHasNoCompactionSpan() {
        final DefaultCompactionEngine compactionEngine = DefaultCompactionEngine.withDefaults(llm,
                new HeuristicTokenEstimator(), hookManager);
        final CompactCommand compact = new CompactCommand(
                compactionEngine, new DefaultCompactionGuard(compactionEngine,
                        InMemoryModelContextWindowRegistry.withDefaults(), new HeuristicTokenEstimator()),
                new DefaultHookRegistry(), hookManager);

        executor.execute(runtime(compact), request("/compact"));

        final List<TraceSpan> spans = store.bySession(SESSION);
        assertThat(only(spans, SpanType.COMMAND).getName()).isEqualTo("command:/compact");
        assertThat(spans).noneMatch(s -> s.getType() == SpanType.COMPACTION);
    }

    @Test
    @DisplayName("ST-5: traced, the command's tool context carries the compaction observer; tracing off, it does not")
    void compactionObserverKeyOnlyWhenTraced() {
        executor.execute(runtime(), request("/probe"));
        assertThat(probe.observerPresent).isTrue();

        executor.tracer = Tracer.noop();
        executor.execute(runtime(), request("/probe"));
        assertThat(probe.observerPresent).isFalse();
    }

    /**
     * Tracing off, the command's tools get the turn's metadata untouched. The turn's own metadata instance is not
     * observable from outside the executor, so this asserts what it must be — the turn defaults with no reserved span
     * tags — rather than instance identity.
     */
    @Test
    @DisplayName("tracing off: no spans, and the command's tool context carries the turn's metadata unrotated")
    void tracingOffLeavesTheCommandContextUnchanged() {
        executor.tracer = Tracer.noop();

        executor.execute(runtime(), request("/probe"));

        assertThat(store.bySession(SESSION)).isEmpty();
        assertThat(probe.metadata).isNotNull();
        assertThat(probe.metadata.getTags()).doesNotContainKeys(SpanContext.TAG_TRACE_ID,
                SpanContext.TAG_PARENT_SPAN_ID);
        assertThat(probe.metadata.getTraceId()).contains(SESSION);
        assertThat(probe.metadata.getFeature()).contains("react-loop");
        assertThat(probe.metadata.getComponent()).contains("TestAgent");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static TraceSpan only(List<TraceSpan> spans, SpanType type) {
        return spans.stream().filter(s -> s.getType() == type).reduce((a, b) -> {
            throw new AssertionError("more than one " + type + " span");
        }).orElseThrow(() -> new AssertionError("no " + type + " span"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inputsOf(TraceSpan span) {
        return (Map<String, Object>) span.getInputs().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputsOf(TraceSpan span) {
        return (Map<String, Object>) span.getOutputs().orElseThrow();
    }

    private static OrcaAgentExecutionRequest request(String input) {
        return OrcaAgentExecutionRequest.builder().userInput(input).sessionId(SessionId.of(SESSION)).build();
    }

    private OrcaAgentRuntime runtime() {
        return runtime(probe);
    }

    private OrcaAgentRuntime runtime(SystemCommand command) {
        environmentProvider = LocalExecutionEnvironmentProvider.builder().workspaceRoot(tempDir.resolve("workspace"))
                .contentSearch(false).build();
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(List.of(command), skillRegistry,
                fileSystem, ".aimon/commands");
        commandRegistry.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(2).systemPrompt("You are a test agent")
                        .model(LlmModel.builder().name("gpt-4").build()).build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(commandRegistry).subagentRegistry(subagentRegistry).skillRegistry(skillRegistry)
                .controlFileSystem(fileSystem).executionEnvironmentProvider(environmentProvider).build();
    }

    private static Skill forkSkill(String name, String agentName, String body) {
        return Skill.builder().name(name)
                .metadata(SkillMetadata.builder().name(name).description("fixture — " + name)
                        .invokePolicy(InvokePolicy.of(true, true)).executionMode(ExecutionMode.FORK)
                        .forkAgentName(agentName).build())
                .content(SkillContent.of(body)).build();
    }

    /** A direct command recording the call metadata its tool context carried. */
    private static final class ProbeCommand extends SystemCommand implements DirectExecutable {
        private LlmCallMetadata metadata;
        private boolean observerPresent;

        ProbeCommand() {
            super("probe", "records its tool context");
        }

        @Override
        public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
            metadata = context.getToolContext().get(ToolContextKeys.LLM_CALL_METADATA_KEY).orElse(null);
            observerPresent = context.getToolContext().get(ToolContextKeys.COMPACTION_OBSERVER).isPresent();
            return CommandExecutionResult.success("probed");
        }
    }

    /** Answers every call with one fixed final answer, recording each call's metadata. */
    private static final class FixedAnswerLlmClient implements LlmClient {
        private final String answer;
        private final List<LlmCallMetadata> metadata = new ArrayList<>();

        FixedAnswerLlmClient(String answer) {
            this.answer = answer;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text(answer);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata callMetadata) {
            metadata.add(callMetadata);
            return sendMessage(systemPrompt, messages, tools, modelConfig);
        }

        @Override
        public String getProviderName() {
            return "Fixed";
        }
    }

    /** In-memory {@link SkillRegistry}. */
    private static final class InMemorySkillRegistry implements SkillRegistry {
        private final Map<String, Skill> skills = new HashMap<>();

        void add(Skill skill) {
            skills.put(skill.getName(), skill);
        }

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return Optional.ofNullable(skills.get(skillName));
        }

        @Override
        public List<Skill> getAllSkills() {
            return new ArrayList<>(skills.values());
        }

        @Override
        public void reloadSkill(String skillName) {
            // no-op
        }

        @Override
        public void reloadAll() {
            // no-op
        }
    }

    /** In-memory {@link SubagentRegistry}. */
    private static final class InMemorySubagentRegistry implements SubagentRegistry {
        private final Map<String, Subagent> subagents = new HashMap<>();

        void add(Subagent subagent) {
            subagents.put(subagent.getName(), subagent);
        }

        @Override
        public Optional<Subagent> getSubagent(String subagentName) {
            return Optional.ofNullable(subagents.get(subagentName));
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return new ArrayList<>(subagents.values());
        }

        @Override
        public void reloadSubagent(String subagentName) {
            // no-op
        }

        @Override
        public void reloadAll() {
            // no-op
        }
    }
}
