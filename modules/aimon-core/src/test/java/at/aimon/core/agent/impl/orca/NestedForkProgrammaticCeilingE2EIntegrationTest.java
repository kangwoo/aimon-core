package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
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
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.tools.code.CodeSpanAttributes;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tools.task.TaskTool;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * E2E test of the programmatic ceiling across two fork levels (ptc-leftovers-core §1.9): main → {@code Task(outer)} →
 * {@code Task(inner)} → {@code ExecuteCode}. The middle fork's own effective programmatic settings — its opt-in
 * narrowed by the main agent's — are the ceiling of the fork it spawns, so the grandchild can call from code only
 * what every level above it allowed.
 *
 * <p>
 * Driven from the outside: the real {@link OrcaAgentExecutor}, {@link DefaultSubagentExecutionManager} and
 * {@link TaskTool}, with an {@link ExecuteCodeTool} over a scripted JavaScript engine that records the callable set it
 * was given, on a HOST environment so {@code ExecuteCode} is genuinely offerable — the control case proves it.
 */
@DisplayName("Nested fork programmatic ceiling e2e (main → Task(outer) → Task(inner) → ExecuteCode)")
class NestedForkProgrammaticCeilingE2EIntegrationTest {

    private static final String MAIN_PROMPT = "MAIN-AGENT-PROMPT";
    private static final String OUTER_PROMPT = "OUTER-FORK-PROMPT";
    private static final String INNER_PROMPT = "INNER-FORK-PROMPT";
    private static final ProgrammaticToolSettings READ_GREP = ProgrammaticToolSettings.builder()
            .tools(List.of("Read", "Grep")).build();
    private static final ProgrammaticToolSettings READ_ONLY = ProgrammaticToolSettings.builder().tools(List.of("Read"))
            .build();
    private static final List<String> FORK_TOOLS = List.of("Task", "Read", "Grep", ExecuteCodeTool.TOOL_NAME);

    @TempDir
    Path tempDir;

    private RoutingLlmClient llmClient;
    private RecordingEngine engine;
    private DefaultSubagentExecutionManager subagentManager;
    private OrcaAgentExecutor executor;
    private InMemorySubagentRegistry subagentRegistry;
    private DefaultToolRegistry toolRegistry;
    private DefaultHookRegistry hookRegistry;
    private InMemoryTraceSpanStore spans;
    private String outerName;
    private String innerName;
    private ListAppender<ILoggingEvent> warnings;
    private Logger managerLogger;

    @BeforeEach
    void setUp() {
        llmClient = new RoutingLlmClient();
        engine = new RecordingEngine();
        spans = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(spans, SpanExporter.noop());
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        subagentManager = new DefaultSubagentExecutionManager(llmClient, toolManager, hookManager);
        subagentManager.withTracer(tracer);
        executor = new OrcaAgentExecutor(llmClient, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, new DefaultCommandExecutionManager(llmClient, toolManager), subagentManager);
        executor.tracer = tracer;

        // Unique per test: the caller-ceiling WARN is once per subagent name for the JVM.
        final String suffix = UUID.randomUUID().toString().substring(0, 8);
        outerName = "outer-" + suffix;
        innerName = "inner-" + suffix;
        llmClient.outerName = outerName;
        llmClient.innerName = innerName;
        subagentRegistry = new InMemorySubagentRegistry();
        hookRegistry = new DefaultHookRegistry();
        toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new ReaderTool("Read"));
        toolRegistry.register(new ReaderTool("Grep"));
        toolRegistry.register(new ExecuteCodeTool(List.of(engine), CodeExecutionLimits.defaults()));
        toolRegistry.register(new TaskTool(LlmModel.builder().name("gpt-4").build(), subagentRegistry, toolRegistry,
                hookRegistry, subagentManager));

        warnings = new ListAppender<>();
        warnings.start();
        managerLogger = (Logger) LoggerFactory.getLogger(DefaultSubagentExecutionManager.class);
        managerLogger.addAppender(warnings);
    }

    @AfterEach
    void tearDown() {
        managerLogger.detachAppender(warnings);
        subagentManager.close();
    }

    @Test
    @DisplayName("1 (control): outer declares [Read, Grep] — inner is offered ExecuteCode with {Read, Grep}")
    void control() {
        registerForks(READ_GREP);

        assertThat(executor.execute(runtime(), request()).isSuccess()).isTrue();

        assertThat(llmClient.innerTools).hasSize(1);
        assertThat(names(llmClient.innerTools.get(0))).contains(ExecuteCodeTool.TOOL_NAME);
        assertThat(engine.callable).singleElement().satisfies(set -> assertThat(set).containsOnly("Read", "Grep"));
        assertThat(engine.grepResults).singleElement().satisfies(result -> assertThat(result).isEqualTo("Grep ok"));
    }

    @Test
    @DisplayName("2: outer declares [Read] — inner is offered ExecuteCode with {Read} only; a program calling Grep is"
            + " refused as not callable")
    void narrowedByTheMiddleFork() {
        registerForks(READ_ONLY);

        assertThat(executor.execute(runtime(), request()).isSuccess()).isTrue();

        assertThat(llmClient.innerTools).hasSize(1);
        assertThat(names(llmClient.innerTools.get(0))).contains(ExecuteCodeTool.TOOL_NAME);
        assertThat(engine.callable).singleElement().satisfies(set -> assertThat(set).containsOnly("Read"));
        assertThat(engine.grepResults).singleElement()
                .satisfies(result -> assertThat(result).contains("Tool 'Grep' cannot be called from code here"));
        // The trace names the refusal kind.
        assertThat(spans.bySession(llmClient.sessionId)).filteredOn(span -> "Grep".equals(span.getName()))
                .singleElement().satisfies(span -> assertThat(span.getAttributes())
                        .containsEntry(CodeSpanAttributes.REFUSED, "not callable"));
    }

    @Test
    @DisplayName("3: outer declares nothing — inner is not offered ExecuteCode; its iteration spans carry"
            + " ptc.unavailable_reason and the caller-ceiling WARN says why (TC-30)")
    void middleForkWithoutPtc() {
        registerForks(ProgrammaticToolSettings.none());

        assertThat(executor.execute(runtime(), request()).isSuccess()).isTrue();

        assertThat(llmClient.innerTools).hasSize(1);
        assertThat(names(llmClient.innerTools.get(0))).contains("Read", "Grep")
                .doesNotContain(ExecuteCodeTool.TOOL_NAME);
        assertThat(engine.callable).isEmpty();
        final List<TraceSpan> innerIterations = spans.bySession(llmClient.sessionId).stream()
                .filter(span -> span.getType() == SpanType.ITERATION)
                .filter(span -> innerName.equals(span.getAttributes().get("subagent"))).toList();
        // The inner fork's offer carries the narrowed settings (none), and the manager's reason rides along with them,
        // so each of its iterations says why it got no ExecuteCode (TC-30) — not only the once-per-name WARN.
        assertThat(innerIterations).isNotEmpty()
                .allSatisfy(span -> assertThat(span.getAttributes().get(CodeSpanAttributes.UNAVAILABLE_REASON))
                        .isEqualTo("the subagent sets programmatic-tools [Read, Grep], but its caller opens no tool "
                                + "to code, and a caller's programmatic settings are its ceiling"));
        assertThat(warnings.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage).anySatisfy(
                        message -> assertThat(message).contains("'" + innerName + "'").contains("caller allows none"));
    }

    // ---------------------------------------------------------------- fixture

    private void registerForks(ProgrammaticToolSettings outerProgrammatic) {
        subagentRegistry.register(Subagent.builder().name(outerName).systemPrompt(OUTER_PROMPT).maxIterations(3)
                .tools(FORK_TOOLS).programmatic(outerProgrammatic).build());
        subagentRegistry.register(Subagent.builder().name(innerName).systemPrompt(INNER_PROMPT).maxIterations(3)
                .tools(List.of("Read", "Grep", ExecuteCodeTool.TOOL_NAME)).programmatic(READ_GREP).build());
    }

    private static List<String> names(List<ToolDefinition> tools) {
        return tools.stream().map(ToolDefinition::getName).toList();
    }

    private OrcaAgentRuntime runtime() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(fileSystem, ".aimon/commands");
        final ExecutionEnvironment host = new HostEnvironment(TestExecutionEnvironments.of(fileSystem));
        final ProgrammaticToolSettings mainProgrammatic = ProgrammaticToolSettings.builder()
                .tools(List.of("Read", "Grep")).engine(ProgrammaticEngineChoice.JAVASCRIPT).build();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("NestedAgent").maxIterations(4).systemPrompt(MAIN_PROMPT)
                        .model(LlmModel.builder().name("gpt-4").build())
                        .allowedTools(FORK_TOOLS.stream().map(AllowedTool::parse).toList())
                        .programmatic(mainProgrammatic).build())
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills"))
                .controlFileSystem(fileSystem).executionEnvironmentProvider(request -> request.parent().orElse(host))
                .build();
    }

    private OrcaAgentExecutionRequest request() {
        final SessionId session = SessionId.generate();
        llmClient.sessionId = session.value();
        return OrcaAgentExecutionRequest.builder().userInput("go").sessionId(session).build();
    }

    /** HOST isolation, so the JavaScript engine is offerable. */
    private static final class HostEnvironment implements ExecutionEnvironment {
        private final ExecutionEnvironment delegate;

        HostEnvironment(ExecutionEnvironment delegate) {
            this.delegate = delegate;
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
                    .isolation(EnvironmentIsolation.HOST).build();
        }

        @Override
        public String stage(StagedResource resource) {
            return delegate.stage(resource);
        }
    }

    /** A JavaScript engine whose program calls Grep once; records the callable set and Grep's answer. */
    private static final class RecordingEngine implements CodeExecutionEngine {
        private final List<List<String>> callable = new CopyOnWriteArrayList<>();
        private final List<String> grepResults = new CopyOnWriteArrayList<>();

        @Override
        public String language() {
            return JAVASCRIPT;
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
        public CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge, ToolContext context) {
            callable.add(request.getTools().stream().map(ToolDefinition::getName).toList());
            final ToolDispatchResult grep = bridge.callAsync("Grep", Map.of("path", "x")).join();
            grepResults.add(grep.getText());
            return CodeExecutionOutcome.builder().status(CodeExecutionStatus.COMPLETED).stdout(grep.getText() + "\n")
                    .build();
        }
    }

    /** A READ_ONLY, code-callable tool. */
    private static final class ReaderTool implements Tool {
        private final String name;

        ReaderTool(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of(name, name + " tool", Map.of("type", "object", "properties",
                    Map.of("path", Map.of("type", "string", "description", "a path"))));
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

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success(name + " ok");
        }
    }

    /**
     * Answers by which loop is calling, told apart by system prompt: the main loop and the outer fork each spawn the
     * next level through {@code Task}, the inner fork runs {@code ExecuteCode} when it is offered; every loop answers
     * text once it has seen a tool result. Records the tools offered to the inner fork.
     */
    private static final class RoutingLlmClient implements LlmClient {
        private final List<List<ToolDefinition>> innerTools = new CopyOnWriteArrayList<>();
        private volatile String outerName;
        private volatile String innerName;
        private volatile String sessionId;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            if (sawToolResult(messages)) {
                return LlmResponse.text("done");
            }
            if (systemPrompt.contains(INNER_PROMPT)) {
                innerTools.add(List.copyOf(tools));
                if (names(tools).contains(ExecuteCodeTool.TOOL_NAME)) {
                    return LlmResponse.of("Running",
                            List.of(ToolUse.of("i-1", ExecuteCodeTool.TOOL_NAME, Map.of("code", "scripted"))));
                }
                return LlmResponse.text("inner done without code");
            }
            if (systemPrompt.contains(OUTER_PROMPT)) {
                return LlmResponse.of("Spawning", List.of(task("o-1", innerName)));
            }
            return LlmResponse.of("Spawning", List.of(task("m-1", outerName)));
        }

        private static ToolUse task(String id, String subagent) {
            return ToolUse.of(id, "Task", Map.of("subagent_name", subagent, "prompt", "go on", "description", "go on"));
        }

        private static boolean sawToolResult(List<Message> messages) {
            return messages.stream().map(Message::getToolUseResults).flatMap(List::stream)
                    .map(ToolUseResult::getToolUseId).findAny().isPresent();
        }

        @Override
        public String getProviderName() {
            return "Routing";
        }
    }
}
