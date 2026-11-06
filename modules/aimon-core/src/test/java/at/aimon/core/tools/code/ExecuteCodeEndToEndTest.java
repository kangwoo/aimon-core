package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
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
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.toolinvocation.DefaultToolCallDispatcher;
import at.aimon.core.toolinvocation.SingleToolInvoker;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tools.ToolContextKeys;

/**
 * {@code ExecuteCode} over the real gated pipeline — dispatcher, approval gate, hooks, execution manager — with a
 * scripted engine standing in for the language runtime. Exercises what piece B could only test in isolation: the
 * CODE-path approval and hook behaviour and {@link ToolCallQuota} admission, end to end through the bridge.
 */
@DisplayName("ExecuteCode end to end — CODE path through the real gates")
class ExecuteCodeEndToEndTest {

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
                .sessionRegistry(registry).allowedTools(List.of()).coordinator(coordinator).build();
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
                .contains("[ExecuteCode: 1 tool call (Read x1)");
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
}
