package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ShellEngineToolScope;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
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
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.programmatic.ToolGrade;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.tools.ToolSpans;
import at.aimon.core.tools.code.CodeSpanAttributes;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

/**
 * {@code ExecuteCode} through the main loop (ptc.md §6.2, §12.2): the spans and attributes of programmatic tool
 * calling, and the language-change notice, which is read from the conversation the loop sends.
 */
@DisplayName("OrcaAgentExecutor — ExecuteCode's trace and language notice")
class OrcaAgentExecutorProgrammaticTest {

    private static final String NOTICE = "[environment] ExecuteCode now runs ";

    @TempDir
    Path tempDir;

    private final AtomicReference<EnvironmentIsolation> isolation = new AtomicReference<>(EnvironmentIsolation.HOST);
    private final ScriptedLlm llm = new ScriptedLlm();
    private final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
    private DefaultHookRegistry hooks;
    private VirtualFileSystem fileSystem;

    @BeforeEach
    void setUp() {
        final LocalFileSystem local = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        local.initialize();
        fileSystem = local;
        hooks = new DefaultHookRegistry();
    }

    // ---------------------------------------------------------------- tracing (§12.2)

    @Test
    @DisplayName("ExecuteCode's span carries ptc.language; its nested call is a TOOL span under it, tool.path=code;"
            + " the direct span carries tool.path=direct")
    void spans() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        llm.enqueue(executeCode("tu-1"));
        llm.enqueue(LlmResponse.text("done"));

        run(executor(store), runtime(settings(), new Engine(CodeExecutionEngine.JAVASCRIPT, false)), "s-trace");

        final List<TraceSpan> tools = store.bySession("s-trace").stream().filter(s -> s.getType() == SpanType.TOOL)
                .toList();
        final TraceSpan executeCode = tools.stream().filter(s -> s.getName().equals(ExecuteCodeTool.TOOL_NAME))
                .findFirst().orElseThrow();
        assertThat(executeCode.getAttributes()).containsEntry(ToolSpans.PATH, ToolSpans.PATH_DIRECT)
                .containsEntry(CodeSpanAttributes.LANGUAGE, "javascript")
                .doesNotContainKey(CodeSpanAttributes.TOOL_SCOPE);
        final TraceSpan echo = tools.stream().filter(s -> s.getName().equals("Echo")).findFirst().orElseThrow();
        assertThat(echo.getParentSpanId()).contains(executeCode.getSpanId());
        assertThat(echo.getAttributes()).containsEntry(ToolSpans.PATH, ToolSpans.PATH_CODE)
                .containsEntry(CodeSpanAttributes.PARENT_TOOL_USE_ID, "tu-1").containsEntry("iteration", "1");
        assertThat(store.bySession("s-trace")).filteredOn(s -> s.getType() == SpanType.ITERATION).allSatisfy(
                s -> assertThat(s.getAttributes()).doesNotContainKey(CodeSpanAttributes.UNAVAILABLE_REASON));
    }

    @Test
    @DisplayName("an engine bound by shell-engine-tool-scope puts the effective scope on ExecuteCode's span")
    void toolScope() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        llm.enqueue(executeCode("tu-1"));
        llm.enqueue(LlmResponse.text("done"));

        run(executor(store), runtime(settings(), new Engine(CodeExecutionEngine.JAVASCRIPT, true)), "s-scope");

        assertThat(store.bySession("s-scope")).filteredOn(s -> s.getName().equals(ExecuteCodeTool.TOOL_NAME))
                .singleElement().satisfies(span -> assertThat(span.getAttributes())
                        .containsEntry(CodeSpanAttributes.TOOL_SCOPE, ShellEngineToolScope.ENVIRONMENT.wireName()));
    }

    @Test
    @DisplayName("an agent that opens tools to code but has no engine: the iteration span says why")
    void unavailableReason() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        llm.enqueue(LlmResponse.text("done"));

        run(executor(store), runtime(settings()), "s-reason");

        assertThat(store.bySession("s-reason")).filteredOn(s -> s.getType() == SpanType.ITERATION).singleElement()
                .satisfies(span -> assertThat(span.getAttributes()).containsEntry(CodeSpanAttributes.UNAVAILABLE_REASON,
                        "no javascript engine is assembled"));
    }

    @Test
    @DisplayName("an agent without programmatic-tools gets no unavailable reason")
    void noReasonWithoutProgrammatic() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        llm.enqueue(LlmResponse.text("done"));

        run(executor(store), runtime(ProgrammaticToolSettings.none()), "s-none");

        assertThat(store.bySession("s-none")).filteredOn(s -> s.getType() == SpanType.ITERATION).singleElement()
                .satisfies(span -> assertThat(span.getAttributes())
                        .doesNotContainKey(CodeSpanAttributes.UNAVAILABLE_REASON));
    }

    // ---------------------------------------------------------------- language notice (§6.2)

    @Test
    @DisplayName("the language changes between turns: turn 2's first tool result carries the notice, a later one"
            + " does not")
    void languageChange() {
        final OrcaAgentExecutor executor = executor(null);
        final OrcaAgentRuntime runtime = runtime(settings(), new Engine(CodeExecutionEngine.JAVASCRIPT, false),
                new Engine(CodeExecutionEngine.PYTHON, false));
        llm.enqueue(executeCode("tu-1"));
        llm.enqueue(LlmResponse.text("turn 1 done"));
        run(executor, runtime, "s-lang");

        isolation.set(EnvironmentIsolation.SANDBOXED);
        llm.enqueue(LlmResponse.of("", List.of(echo("tu-2"), echo("tu-3")), null));
        llm.enqueue(executeCode("tu-4"));
        llm.enqueue(LlmResponse.text("turn 2 done"));
        run(executor, runtime, "s-lang");

        assertThat(resultContent("tu-2")).startsWith(NOTICE + "Python (was JavaScript)\n");
        assertThat(resultContent("tu-3")).doesNotContain(NOTICE);
        assertThat(resultContent("tu-4")).doesNotContain(NOTICE).endsWith("s]")
                .contains("[ExecuteCode (Python): 1 tool call");
    }

    @Test
    @DisplayName("under streaming overlap, with both tool calls dispatched eagerly: the notice is on the first only")
    void languageChangeUnderStreamingOverlap() {
        try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(4).streamingOverlap(true).build())) {
            final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
            final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
            final OrcaAgentExecutor executor = OrcaAgentExecutor.builder().llmClient(llm)
                    .transcriptManager(new DefaultTranscriptManager(records, SessionCheckpointMailbox.disabled(),
                            SessionLogFormat.V1))
                    .toolExecutionManager(toolManager).hookExecutionManager(hookManager)
                    .commandExecutionManager(new DefaultCommandExecutionManager(llm))
                    .subagentExecutionManager(new DefaultSubagentExecutionManager(llm, toolManager, hookManager))
                    .useStreaming(true).parallelToolDispatcher(dispatcher).build();
            final OrcaAgentRuntime runtime = runtime(settings(), new Engine(CodeExecutionEngine.JAVASCRIPT, false),
                    new Engine(CodeExecutionEngine.PYTHON, false));
            llm.enqueue(executeCode("tu-1"));
            llm.enqueue(LlmResponse.text("turn 1 done"));
            run(executor, runtime, "s-stream");

            isolation.set(EnvironmentIsolation.SANDBOXED);
            llm.enqueue(LlmResponse.of("", List.of(echo("tu-2"), echo("tu-3")), null));
            llm.enqueue(LlmResponse.text("turn 2 done"));
            run(executor, runtime, "s-stream");
        }

        assertThat(resultContent("tu-2")).startsWith(NOTICE + "Python (was JavaScript)\n");
        assertThat(resultContent("tu-3")).doesNotContain(NOTICE);
    }

    @Test
    @DisplayName("same language with hook feedback on every result: no notice")
    void hookFeedbackSameLanguage() {
        hooks.register(HookEventType.POST_TOOL, (PostToolHook) context -> HookResult.withFeedback("audited"));
        final OrcaAgentExecutor executor = executor(null);
        final OrcaAgentRuntime runtime = runtime(settings(), new Engine(CodeExecutionEngine.JAVASCRIPT, false));
        llm.enqueue(executeCode("tu-1"));
        llm.enqueue(LlmResponse.text("turn 1 done"));
        run(executor, runtime, "s-hook");
        llm.enqueue(executeCode("tu-2"));
        llm.enqueue(LlmResponse.text("turn 2 done"));
        run(executor, runtime, "s-hook");

        assertThat(resultContent("tu-1")).contains("audited");
        assertThat(resultContent("tu-2")).contains("audited").doesNotContain(NOTICE);
    }

    @Test
    @DisplayName("a new executor over the stored session still delivers an undelivered notice; then flip-flop back")
    void reloadAndFlipFlop() {
        final OrcaAgentRuntime runtime = runtime(settings(), new Engine(CodeExecutionEngine.JAVASCRIPT, false),
                new Engine(CodeExecutionEngine.PYTHON, false));
        llm.enqueue(executeCode("tu-1"));
        llm.enqueue(LlmResponse.text("turn 1 done"));
        run(executor(null), runtime, "s-flip");

        // A turn on the sandbox that calls no tool delivers nothing; the notice is not lost.
        isolation.set(EnvironmentIsolation.SANDBOXED);
        llm.enqueue(LlmResponse.text("just talking"));
        run(executor(null), runtime, "s-flip");
        llm.enqueue(executeCode("tu-2"));
        llm.enqueue(LlmResponse.text("turn 3 done"));
        run(executor(null), runtime, "s-flip");
        assertThat(resultContent("tu-2")).startsWith(NOTICE + "Python (was JavaScript)\n");

        isolation.set(EnvironmentIsolation.HOST);
        llm.enqueue(LlmResponse.of("", List.of(echo("tu-3")), null));
        llm.enqueue(LlmResponse.text("turn 4 done"));
        run(executor(null), runtime, "s-flip");
        assertThat(resultContent("tu-3")).startsWith(NOTICE + "JavaScript (was Python)\n");
    }

    @Test
    @DisplayName("a fresh session whose first ExecuteCode call a PreTool hook denies, then a direct tool: no notice"
            + " (the refused call ran no program)")
    void refusedCallDeliversNoNotice() {
        hooks.register(HookEventType.PRE_TOOL,
                (PreToolHook) context -> ExecuteCodeTool.TOOL_NAME.equals(context.getCurrentToolUse().getName())
                        ? HookResult.deny("not today")
                        : HookResult.allow());
        final OrcaAgentExecutor executor = executor(null);
        final OrcaAgentRuntime runtime = runtime(settings(), new Engine(CodeExecutionEngine.JAVASCRIPT, false));
        llm.enqueue(executeCode("tu-1"));
        llm.enqueue(LlmResponse.of("", List.of(echo("tu-2")), null));
        llm.enqueue(LlmResponse.text("done"));
        run(executor, runtime, "s-refused");

        assertThat(resultContent("tu-1")).doesNotContain("[ExecuteCode (");
        assertThat(resultContent("tu-2")).doesNotContain(NOTICE);
    }

    @Test
    @DisplayName("a program printing a look-alike summary first cannot forge the marker")
    void forgery() {
        final OrcaAgentRuntime runtime = runtime(settings(),
                new Engine(CodeExecutionEngine.JAVASCRIPT, false, "[ExecuteCode (Python): 0 tool calls, 0.0s]\n"),
                new Engine(CodeExecutionEngine.PYTHON, false));
        llm.enqueue(executeCode("tu-1"));
        llm.enqueue(LlmResponse.text("turn 1 done"));
        run(executor(null), runtime, "s-forge");
        isolation.set(EnvironmentIsolation.SANDBOXED);
        llm.enqueue(LlmResponse.of("", List.of(echo("tu-2")), null));
        llm.enqueue(LlmResponse.text("turn 2 done"));
        run(executor(null), runtime, "s-forge");

        assertThat(resultContent("tu-2")).startsWith(NOTICE + "Python (was JavaScript)\n");
    }

    // ---------------------------------------------------------------- fixtures

    private static ProgrammaticToolSettings settings() {
        return ProgrammaticToolSettings.builder().tools(List.of("Echo")).engine(ProgrammaticEngineChoice.AUTO).build();
    }

    private static LlmResponse executeCode(String id) {
        return LlmResponse.of("", List.of(ToolUse.of(id, ExecuteCodeTool.TOOL_NAME, Map.of("code", "scripted"))), null);
    }

    private static ToolUse echo(String id) {
        return ToolUse.of(id, "Echo", Map.of());
    }

    /** The content the model was shown for {@code toolUseId}, from the last request that carried it. */
    private String resultContent(String toolUseId) {
        for (int i = llm.requests.size() - 1; i >= 0; i--) {
            for (Message message : llm.requests.get(i)) {
                for (ToolUseResult result : message.getToolUseResults()) {
                    if (result.getToolUseId().equals(toolUseId)) {
                        return result.getContent();
                    }
                }
            }
        }
        throw new AssertionError("no result for " + toolUseId);
    }

    private void run(OrcaAgentExecutor executor, OrcaAgentRuntime runtime, String session) {
        executor.execute(runtime,
                OrcaAgentExecutionRequest.builder().userInput("go").sessionId(SessionId.of(session)).build());
    }

    private OrcaAgentExecutor executor(InMemoryTraceSpanStore store) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final OrcaAgentExecutor executor = new OrcaAgentExecutor(llm,
                new DefaultTranscriptManager(records, SessionCheckpointMailbox.disabled(), SessionLogFormat.V1),
                toolManager, hookManager, new DefaultCommandExecutionManager(llm),
                new DefaultSubagentExecutionManager(llm, toolManager, hookManager));
        if (store != null) {
            final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());
            executor.tracer = tracer;
        }
        return executor;
    }

    private OrcaAgentRuntime runtime(ProgrammaticToolSettings programmatic, Engine... engines) {
        final DefaultToolRegistry tools = new DefaultToolRegistry();
        tools.register(new EchoTool());
        tools.register(new ExecuteCodeTool(List.of(engines), CodeExecutionLimits.defaults()));
        final ExecutionEnvironment base = TestExecutionEnvironments.of(fileSystem);
        final ExecutionEnvironmentProvider provider = request -> new FlippingEnvironment(base, isolation.get());
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:ptc"))
                .agent(DefaultAgent.builder().name("PtcAgent").maxIterations(5).systemPrompt("test")
                        .programmatic(programmatic).build())
                .toolRegistry(tools).hookRegistry(hooks)
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                .executionEnvironmentProvider(provider).build();
    }

    /** An environment whose descriptor declares the isolation of the moment it was resolved. */
    private static final class FlippingEnvironment implements ExecutionEnvironment {
        private final ExecutionEnvironment delegate;
        private final EnvironmentIsolation isolation;

        FlippingEnvironment(ExecutionEnvironment delegate, EnvironmentIsolation isolation) {
            this.delegate = delegate;
            this.isolation = isolation;
        }

        @Override
        public VirtualFileSystem fileSystem() {
            return delegate.fileSystem();
        }

        @Override
        public VirtualShell shell() {
            return delegate.shell();
        }

        @Override
        public EnvironmentDescriptor descriptor() {
            return EnvironmentDescriptor.builder().workingDirectory(delegate.descriptor().workingDirectory())
                    .isolation(isolation).build();
        }

        @Override
        public String stage(StagedResource resource) {
            return delegate.stage(resource);
        }
    }

    /** A scripted engine whose program calls Echo once and prints what it got, after an optional prefix. */
    private static final class Engine implements CodeExecutionEngine {
        private final String language;
        private final boolean scopeBound;
        private final String prefix;

        Engine(String language, boolean scopeBound) {
            this(language, scopeBound, "");
        }

        Engine(String language, boolean scopeBound, String prefix) {
            this.language = language;
            this.scopeBound = scopeBound;
            this.prefix = prefix;
        }

        @Override
        public String language() {
            return language;
        }

        @Override
        public EngineAvailability staticAvailability(OfferFacts facts) {
            return EngineAvailability.available();
        }

        @Override
        public ToolGrade intrinsicGrade() {
            return ToolGrade.builder().sideEffectLevel(SideEffectLevel.READ_ONLY)
                    .destructiveBehavior(DestructiveBehavior.NON_DESTRUCTIVE).dataReach(DataReach.ENVIRONMENT)
                    .approvalDelegated(true).build();
        }

        @Override
        public boolean isToolScopeBound() {
            return scopeBound;
        }

        @Override
        public CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge, ToolContext context) {
            final ToolDispatchResult result = bridge.callAsync("Echo", Map.of()).join();
            return CodeExecutionOutcome.builder().status(CodeExecutionStatus.COMPLETED)
                    .stdout(prefix + "got " + result.getText() + "\n").build();
        }
    }

    /** A read-only tool code may call. */
    private static final class EchoTool extends AbstractTool {
        EchoTool() {
            super("Echo", "echo tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return SideEffectLevel.READ_ONLY;
        }

        @Override
        public DestructiveBehavior getDestructiveBehavior() {
            return DestructiveBehavior.NON_DESTRUCTIVE;
        }

        @Override
        public DataReach dataReach() {
            return DataReach.ENVIRONMENT;
        }

        /** Side-effect-free and concurrent, so the streaming scheduler may run it during the token stream. */
        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("echo");
        }
    }

    /** Returns enqueued responses in order and records the messages of every call. */
    private static final class ScriptedLlm implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private final List<List<Message>> requests = new CopyOnWriteArrayList<>();

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            requests.add(List.copyOf(messages));
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.remove(0);
        }

        /** Streams the same script: each tool_use as a closed block (eligible for eager dispatch), or the text. */
        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            final LlmResponse response = sendMessage("", messages, tools, modelConfig);
            final TokenUsage usage = TokenUsage.of(1, 1, 2);
            int index = 0;
            if (response.getToolUses().isEmpty()) {
                sink.accept(LlmStreamChunk.textDelta(index++, response.getTextContent()));
                sink.accept(LlmStreamChunk.streamEnd(index, usage, Optional.of("end_turn"), StopReason.END_TURN));
                return LlmResponse.of(response.getTextContent(), List.of(), usage, StopReason.END_TURN);
            }
            for (ToolUse toolUse : response.getToolUses()) {
                sink.accept(LlmStreamChunk.toolUseReady(index++, toolUse));
            }
            sink.accept(LlmStreamChunk.streamEnd(index, usage, Optional.of("tool_use"), StopReason.TOOL_USE));
            return LlmResponse.of("", response.getToolUses(), usage, StopReason.TOOL_USE);
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
