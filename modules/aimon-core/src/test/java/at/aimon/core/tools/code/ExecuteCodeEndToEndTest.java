package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorFactory;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolCallPath;
import at.aimon.core.agent.tool.ToolCallQuota;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.agent.tool.ToolResult;
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
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.execution.SubagentOutcomePayload;
import at.aimon.core.toolinvocation.DefaultToolCallDispatcher;
import at.aimon.core.toolinvocation.SingleToolInvoker;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.ToolSpans;
import at.aimon.core.tools.task.TaskTool;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

/**
 * {@code ExecuteCode} over the real gated pipeline — dispatcher, approval gate, hooks, execution manager — with a
 * scripted engine standing in for the language runtime. Exercises what piece B could only test in isolation: the
 * CODE-path approval and hook behaviour and {@link ToolCallQuota} admission, end to end through the bridge.
 */
@DisplayName("ExecuteCode end to end — CODE path through the real gates")
class ExecuteCodeEndToEndTest {

    @TempDir
    Path tempDir;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<ToolCallOrigin> preToolOrigins = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final ScriptedEngine engine = ScriptedEngine.javascript();
    private final TestTool read = TestTool.reader("Read");
    private final TestTool write = TestTool.writer("Write");

    private DefaultHookRegistry hookRegistry;
    private DefaultToolRegistry registry;
    private DefaultInterruptCoordinator coordinator;
    private volatile HookResult preToolAnswer = HookResult.success();

    @BeforeEach
    void setUp() {
        hookRegistry = new DefaultHookRegistry();
        hookRegistry.register(HookEventType.PERMISSION_REQUEST, (PermissionRequestHook) context -> {
            events.add("permission:" + context.getToolName());
            return HookResult.success();
        });
        hookRegistry.register(HookEventType.PRE_TOOL, (PreToolHook) context -> {
            events.add("preTool:" + context.getOriginalToolUse().getName());
            preToolOrigins.add(context.getOrigin());
            return context.getOriginalToolUse().getName().equals(ExecuteCodeTool.TOOL_NAME)
                    ? HookResult.success()
                    : preToolAnswer;
        });
        hookRegistry.register(HookEventType.POST_TOOL, (PostToolHook) context -> {
            events.add("postTool:" + context.getToolUse().getName());
            return HookResult.withFeedback("audited " + context.getToolUse().getName());
        });
        registry = new DefaultToolRegistry();
        registry.register(
                new ExecuteCodeTool(List.of(engine), CodeExecutionLimits.builder().maxMutatingCallsPerTool(2).build()));
        registry.register(read);
        registry.register(write);
        coordinator = new DefaultInterruptCoordinator();
    }

    @AfterEach
    void tearDown() {
        coordinator.close();
    }

    private ToolCallDispatcher dispatcher() {
        final SideEffectApprovalGate gate = new SideEffectApprovalGate(prompt -> {
            prompts.add(prompt);
            return Decision.ALLOW;
        });
        final SingleToolInvoker invoker = new SingleToolInvoker(new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager(), gate);
        return DefaultToolCallDispatcher.builder().invoker(invoker).invokerType(InvokerType.MAIN_AGENT)
                .invokerName("agent").hookRegistry(hookRegistry).executionAttributes(Map.of()).toolRegistry(registry)
                .sessionRegistry(registry).allowedTools(List.of()).coordinator(coordinator)
                .programmatic(ProgrammaticToolSettings.none()).build();
    }

    private ToolContext context(ToolCallDispatcher dispatcher, ProgrammaticToolSettings settings, ToolCallQuota quota) {
        final ToolOfferContext offer = ToolOfferContext.builder().registry(registry).programmatic(settings).put(
                CodeOfferKeys.ENVIRONMENT_DESCRIPTOR,
                EnvironmentDescriptor.builder().workingDirectory("/w").isolation(EnvironmentIsolation.HOST).build())
                .build();
        final ToolContext.Builder builder = ToolContext.builder().put(ToolContextKeys.TOOL_CALL_DISPATCHER, dispatcher)
                .put(ToolContextKeys.TOOL_OFFER_CONTEXT, offer).put(ToolContextKeys.SESSION_ID, SessionId.of("s-1"));
        if (quota != null) {
            builder.put(ToolContextKeys.TOOL_CALL_QUOTA, quota);
        }
        return builder.build();
    }

    private static ProgrammaticToolSettings settings(String... tools) {
        return ProgrammaticToolSettings.builder().tools(List.of(tools)).build();
    }

    private static ToolUse executeCode(String id) {
        return ToolUse.of(id, ExecuteCodeTool.TOOL_NAME, Map.of("code", "// scripted"));
    }

    /** A program that calls each tool in order, awaiting each, and prints the results. */
    private static CodeExecutionOutcome callInOrder(ToolCallBridge bridge, List<String> tools) {
        final StringBuilder out = new StringBuilder();
        for (String tool : tools) {
            final ToolDispatchResult result = bridge.callAsync(tool, Map.of("path", "/p")).join();
            out.append(result.isError() ? "ERR " : "OK ").append(result.getText()).append('\n');
            if (bridge.limitExceeded().isPresent()) {
                return CodeExecutionOutcome.builder().status(CodeExecutionStatus.CANCELLED).stdout(out.toString())
                        .build();
            }
        }
        return ScriptedEngine.completed(out.toString());
    }

    @Test
    @DisplayName("ExecuteCode itself is not asked about; its nested read runs on the CODE path with hooks; the"
            + " observation carries output, hook feedback and the summary")
    void delegatedApprovalAndCodeOrigin() {
        engine.program((request, bridge) -> callInOrder(bridge, List.of("Read")));
        final ToolCallDispatcher dispatcher = dispatcher();

        final ToolDispatchResult result = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(),
                context(dispatcher, settings("Read"), null));

        assertThat(result.isError()).as(result.getText()).isFalse();
        assertThat(prompts).isEmpty();
        assertThat(result.getText()).contains("OK Read:/p").contains("[hook feedback]").contains("audited Read")
                .contains("[ExecuteCode (JavaScript): 1 tool call (Read x1)");
        assertThat(preToolOrigins).hasSize(2);
        final ToolCallOrigin nested = preToolOrigins.get(1);
        assertThat(nested.getPath()).isEqualTo(ToolCallPath.CODE);
        assertThat(nested.getParentToolUseId()).contains("tu-1");
        assertThat(nested.getLanguage()).contains("javascript");
        assertThat(result.getRenderPayload()).hasValueSatisfying(payload -> {
            assertThat(payload).containsEntry("code", "// scripted").containsEntry("status", "COMPLETED");
            assertThat((List<?>) payload.get("calls")).hasSize(1);
        });
    }

    @Test
    @DisplayName("a nested mutating call without a remembered approval is refused without a prompt; after a direct"
            + " approval in the same session it passes")
    void codePathNeverAsks() {
        engine.program((request, bridge) -> callInOrder(bridge, List.of("Write")));
        final ToolCallDispatcher dispatcher = dispatcher();
        final ToolContext context = context(dispatcher, settings("Write"), null);

        final ToolDispatchResult first = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(), context);
        assertThat(first.getText()).contains("ERR").contains("calls from code never ask");
        assertThat(prompts).isEmpty();
        assertThat(write.calls).isEmpty();

        // The direct call asks once and the answer is remembered for the session.
        final ToolDispatchResult direct = dispatcher.dispatch(ToolUse.of("tu-2", "Write", Map.of("path", "/d")),
                ToolCallOrigin.direct(), context);
        assertThat(direct.isError()).isFalse();
        assertThat(prompts).hasSize(1);

        final ToolDispatchResult second = dispatcher.dispatch(executeCode("tu-3"), ToolCallOrigin.direct(), context);
        assertThat(second.getText()).contains("OK Write:/p");
        assertThat(prompts).hasSize(1);
    }

    @Test
    @DisplayName("a PreTool ASK on the CODE path is a refusal, never a prompt")
    void preToolAskIsRefusedOnCodePath() {
        preToolAnswer = HookResult.ask("really read?");
        engine.program((request, bridge) -> callInOrder(bridge, List.of("Read")));
        final ToolCallDispatcher dispatcher = dispatcher();

        final ToolDispatchResult result = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(),
                context(dispatcher, settings("Read"), null));

        assertThat(result.getText()).contains("ERR");
        assertThat(read.calls).isEmpty();
        assertThat(prompts).isEmpty();
    }

    @Test
    @DisplayName("the fork's ToolCallQuota admits each nested call; a refusal stops the program at once with"
            + " LIMIT_EXCEEDED and marks the quota exceeded")
    void quotaAdmission() {
        engine.program((request, bridge) -> callInOrder(bridge, List.of("Read", "Read", "Read", "Read")));
        final ToolCallDispatcher dispatcher = dispatcher();
        final ToolCallQuota quota = ToolCallQuota.limitedTo(2);

        final ToolDispatchResult result = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(),
                context(dispatcher, settings("Read"), quota));

        assertThat(result.isError()).isTrue();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getText()).contains("stopped at a limit").contains("tool-call budget");
        assertThat(read.calls).hasSize(2);
        assertThat(quota.isExceeded()).isTrue();
        assertThat(quota.used()).isEqualTo(2);
    }

    /** A program that catches every refusal and runs to its end: it reports COMPLETED whatever the bridge said. */
    private static CodeExecutionOutcome callIgnoringRefusals(ToolCallBridge bridge, int count) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            final ToolDispatchResult result = bridge.callAsync("Read", Map.of("path", "/p")).join();
            out.append(result.isError() ? "caught\n" : "ok\n");
        }
        return ScriptedEngine.completed(out.append("finished\n").toString());
    }

    @Test
    @DisplayName("a program that catches the quota refusal and completes is still LIMIT_EXCEEDED, keeping its output")
    void caughtQuotaRefusalIsLimitExceeded() {
        engine.program((request, bridge) -> callIgnoringRefusals(bridge, 4));
        final ToolCallDispatcher dispatcher = dispatcher();

        final ToolDispatchResult result = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(),
                context(dispatcher, settings("Read"), ToolCallQuota.limitedTo(2)));

        assertThat(result.isError()).isTrue();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getText()).contains("stopped at a limit").contains("[output before the stop]")
                .contains("finished");
    }

    @Test
    @DisplayName("a program that catches the nested-call limit's refusal and completes is still LIMIT_EXCEEDED")
    void caughtCallLimitRefusalIsLimitExceeded() {
        registry.unregister(ExecuteCodeTool.TOOL_NAME);
        registry.register(
                new ExecuteCodeTool(List.of(engine), CodeExecutionLimits.builder().maxNestedCalls(2).build()));
        engine.program((request, bridge) -> callIgnoringRefusals(bridge, 4));
        final ToolCallDispatcher dispatcher = dispatcher();

        final ToolDispatchResult result = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(),
                context(dispatcher, settings("Read"), null));

        assertThat(result.isError()).isTrue();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getText()).contains("stopped at a limit").contains("[output before the stop]");
    }

    @Test
    @DisplayName("a protocol violation is not masked by a bridge limit: it stays PROTOCOL_VIOLATION, not retryable")
    void violationWinsOverLimit() {
        engine.program((request, bridge) -> {
            callIgnoringRefusals(bridge, 4);
            return CodeExecutionOutcome.builder().status(CodeExecutionStatus.PROTOCOL_VIOLATION)
                    .errorMessage("protocol violation: forged line").build();
        });
        final ToolCallDispatcher dispatcher = dispatcher();

        final ToolDispatchResult result = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(),
                context(dispatcher, settings("Read"), ToolCallQuota.limitedTo(2)));

        assertThat(result.isError()).isTrue();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getText()).contains("forged line").doesNotContain("stopped at a limit");
    }

    @Test
    @DisplayName("a MUTATING tool is capped per script; the refusals are counted in the summary")
    void writeCap() {
        engine.program((request, bridge) -> callInOrder(bridge, List.of("Write", "Write", "Write")));
        final ToolCallDispatcher dispatcher = dispatcher();
        final ToolContext context = context(dispatcher, settings("Write"), null);
        dispatcher.dispatch(ToolUse.of("tu-0", "Write", Map.of("path", "/d")), ToolCallOrigin.direct(), context);

        final ToolDispatchResult result = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(), context);

        assertThat(result.getText()).contains("limit 2 per script for Write; 2 already applied")
                .contains("1 refused by write cap");
        assertThat(write.calls).hasSize(3); // the direct one and two from code
    }

    @Test
    @DisplayName("a tool outside the frozen set is refused by the bridge, not dispatched")
    void outsideTheFrozenSet() {
        engine.program((request, bridge) -> callInOrder(bridge, List.of("Write")));
        final ToolCallDispatcher dispatcher = dispatcher();

        final ToolDispatchResult result = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(),
                context(dispatcher, settings("Read"), null));

        assertThat(result.getText()).contains("cannot be called from code here").contains("1 refused by not callable");
        assertThat(events).doesNotContain("permission:Write");
    }

    @Test
    @DisplayName("a code-only tool called directly is refused on the DIRECT path")
    void codeOnlyRefusedDirectly() {
        final ToolCallDispatcher dispatcher = dispatcher();
        final ProgrammaticToolSettings codeOnly = ProgrammaticToolSettings.builder().tools(List.of("Read"))
                .codeOnly(List.of("Read")).build();

        final ToolDispatchResult result = dispatcher.dispatch(ToolUse.of("tu-1", "Read", Map.of("path", "/p")),
                ToolCallOrigin.direct(), context(dispatcher, codeOnly, null));

        assertThat(result.isError()).isTrue();
        assertThat(result.getText()).contains("offered only to code");
        assertThat(read.calls).isEmpty();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("cancelling the execution cancels the program's child signal, and nested calls see that signal")
    void childCancellation() throws Exception {
        final CompletableFuture<Boolean> sawChild = new CompletableFuture<>();
        engine.program((request, bridge) -> {
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!request.getCancellation().isCancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            sawChild.complete(request.getCancellation().isCancelled());
            return CodeExecutionOutcome.builder().status(CodeExecutionStatus.CANCELLED).build();
        });
        final ToolCallDispatcher dispatcher = dispatcher();
        final DefaultInterruptCoordinator execution = new DefaultInterruptCoordinator();
        final ToolContext context = ToolContext.builder()
                .putAll(context(dispatcher, settings("Read"), null).getContext())
                .put(InterruptToolKeys.CANCELLATION_SIGNAL, execution.getSignal()).build();

        final CompletableFuture<ToolDispatchResult> running = CompletableFuture
                .supplyAsync(() -> dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(), context));
        Thread.sleep(100);
        execution.requestInterrupt(InterruptReason.USER_SIGINT);

        assertThat(sawChild.get(10, TimeUnit.SECONDS)).isTrue();
        final ToolDispatchResult result = running.get(10, TimeUnit.SECONDS);
        assertThat(result.isError()).isTrue();
        assertThat(result.getText()).contains("cancelled");
        execution.close();
    }

    @Test
    @DisplayName("concurrent nested calls all complete and are each recorded")
    void concurrentCalls() {
        engine.program((request, bridge) -> {
            final List<CompletableFuture<ToolDispatchResult>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(bridge.callAsync("Read", Map.of("path", "/" + i)));
            }
            futures.forEach(CompletableFuture::join);
            return ScriptedEngine.completed("done\n");
        });
        final ToolCallDispatcher dispatcher = dispatcher();

        final ToolDispatchResult result = dispatcher.dispatch(executeCode("tu-1"), ToolCallOrigin.direct(),
                context(dispatcher, settings("Read"), null));

        assertThat(result.getText()).contains("done").contains("10 tool calls (Read x10)");
        assertThat(read.calls).hasSize(10);
    }

    // ---------------------------------------------------------------- fork variant (PTC design B.6, "End to end")

    private static final String MAIN_PROMPT = "E2E-MAIN-PROMPT";
    private static final String FORK_PROMPT = "E2E-FORK-PROMPT";

    @Test
    @DisplayName("fork: the main agent calls Task, the fork's ExecuteCode makes 2 nested calls — the fork's quota counts"
            + " them and their TOOL spans (tool.path=code) parent under the fork's ExecuteCode span")
    void forkExecuteCodeCountsQuotaAndNestsSpans() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());
        final ForkRoutingLlm llm = new ForkRoutingLlm();
        // Only the factory is told about the tracer: it reaches the subagent manager and executor it builds.
        final OrcaAgentExecutor executor = new OrcaAgentExecutorFactory().withTracer(tracer).create(llm,
                new DefaultTranscriptManager(new InMemorySessionRecordStore()));
        final InMemorySubagentRegistry subagents = new InMemorySubagentRegistry();
        subagents.register(Subagent.builder().name("coder").systemPrompt(FORK_PROMPT).maxIterations(3)
                .tools(List.of(ExecuteCodeTool.TOOL_NAME, "Read")).programmatic(settings("Read")).build());
        final CapturingTaskTool task = new CapturingTaskTool(subagents, registry, hookRegistry,
                executor.getSubagentExecutionManager());
        registry.register(task);
        engine.program((request, bridge) -> callInOrder(bridge, List.of("Read", "Read")));

        final OrcaAgentExecutionResult result = executor.execute(forkRuntime(subagents),
                OrcaAgentExecutionRequest.builder().userInput("go").sessionId(SessionId.of("s-fork")).build());

        assertThat(result.isSuccess()).isTrue();
        // The fork was offered ExecuteCode (its own opt-in, under the main agent's ceiling) and ran it.
        assertThat(llm.forkTools).isNotEmpty();
        assertThat(llm.forkTools.get(0)).extracting(ToolDefinition::getName).contains(ExecuteCodeTool.TOOL_NAME);
        assertThat(llm.forkObservation).contains("OK Read:/p").contains("2 tool calls (Read x2)");
        assertThat(read.calls).hasSize(2);

        // Quota: the fork counts its own ExecuteCode call and both nested calls.
        assertThat(task.lastResult).isNotNull();
        final Map<String, Object> payload = task.lastResult.getRenderPayload();
        assertThat(payload).containsEntry(SubagentOutcomePayload.TOOL_CALLS, 3);

        // Spans: SUBAGENT → ITERATION → TOOL(ExecuteCode) → TOOL(Read, tool.path=code) x2.
        final List<TraceSpan> spans = store.bySession("s-fork");
        final TraceSpan subagent = spans.stream().filter(s -> s.getType() == SpanType.SUBAGENT).findFirst()
                .orElseThrow();
        final Set<String> forkIterations = spans.stream().filter(s -> s.getType() == SpanType.ITERATION)
                .filter(s -> s.getParentSpanId().orElse("").equals(subagent.getSpanId())).map(TraceSpan::getSpanId)
                .collect(Collectors.toSet());
        final TraceSpan forkExecuteCode = spans.stream()
                .filter(s -> s.getType() == SpanType.TOOL && s.getName().equals(ExecuteCodeTool.TOOL_NAME))
                .filter(s -> forkIterations.contains(s.getParentSpanId().orElse(""))).findFirst().orElseThrow();
        assertThat(forkExecuteCode.getAttributes()).containsEntry(ToolSpans.PATH, ToolSpans.PATH_DIRECT)
                .containsEntry("invokerType", "SUBAGENT");
        final List<TraceSpan> nested = spans.stream()
                .filter(s -> s.getType() == SpanType.TOOL && s.getName().equals("Read")).toList();
        assertThat(nested).hasSize(2).allSatisfy(span -> {
            assertThat(span.getParentSpanId()).contains(forkExecuteCode.getSpanId());
            assertThat(span.getAttributes()).containsEntry(ToolSpans.PATH, ToolSpans.PATH_CODE);
        });
    }

    private OrcaAgentRuntime forkRuntime(SubagentRegistry subagents) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final ExecutionEnvironment host = new HostEnvironment(TestExecutionEnvironments.of(fileSystem));
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("MainAgent").maxIterations(4).systemPrompt(MAIN_PROMPT)
                        .model(LlmModel.builder().name("gpt-4").build()).programmatic(settings("Read")).build())
                .toolRegistry(registry).hookRegistry(hookRegistry)
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands")).subagentRegistry(subagents)
                .skillRegistry(new EmptySkillRegistry()).controlFileSystem(fileSystem)
                .executionEnvironmentProvider(request -> request.parent().orElse(host)).build();
    }

    /** Task, keeping the result it returned so the test can read the fork's outcome payload. */
    private static final class CapturingTaskTool extends TaskTool {
        private volatile ToolResult lastResult;

        CapturingTaskTool(SubagentRegistry subagents, DefaultToolRegistry registry, DefaultHookRegistry hooks,
                SubagentExecutionManager manager) {
            super(LlmModel.builder().name("gpt-4").build(), subagents, registry, hooks, manager);
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            final ToolResult result = super.execute(input, context);
            lastResult = result;
            return result;
        }
    }

    /** Main: Task(coder), then text. Fork: ExecuteCode, then text, keeping the observation and its offered tools. */
    private static final class ForkRoutingLlm implements LlmClient {
        private final List<List<ToolDefinition>> forkTools = new CopyOnWriteArrayList<>();
        private volatile String forkObservation = "";

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            final boolean sawResult = messages.stream().anyMatch(Message::hasToolResults);
            if (systemPrompt.contains(FORK_PROMPT)) {
                forkTools.add(List.copyOf(tools));
                if (sawResult) {
                    messages.stream().filter(Message::hasToolResults)
                            .forEach(m -> m.getToolUseResults().forEach(r -> forkObservation += r.getContent()));
                    return LlmResponse.text("fork done");
                }
                return LlmResponse.of("", List.of(executeCode("f-1")));
            }
            if (sawResult) {
                return LlmResponse.text("main done");
            }
            return LlmResponse.of("", List.of(ToolUse.of("m-1", "Task",
                    Map.of("subagent_name", "coder", "prompt", "run code", "description", "run code"))));
        }

        @Override
        public String getProviderName() {
            return "ForkRouting";
        }
    }

    /** The test environment, declaring HOST isolation so {@code programmatic-engine: auto} picks JavaScript. */
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

    /** No skills. */
    private static final class EmptySkillRegistry implements SkillRegistry {
        @Override
        public Optional<Skill> getSkill(String skillName) {
            return Optional.empty();
        }

        @Override
        public List<Skill> getAllSkills() {
            return List.of();
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
}
