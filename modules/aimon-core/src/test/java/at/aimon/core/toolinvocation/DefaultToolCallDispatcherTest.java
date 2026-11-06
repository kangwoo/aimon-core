package at.aimon.core.toolinvocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tools.ToolContextKeys;

/**
 * The gated {@link DefaultToolCallDispatcher}, driven over the real pipeline: a real hook registry and execution
 * manager, a real approval gate, a real tool registry (tool-call-dispatcher design §7).
 */
@DisplayName("DefaultToolCallDispatcher — the single entrance to the tool gates")
class DefaultToolCallDispatcherTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<ToolCallOrigin> permissionOrigins = new CopyOnWriteArrayList<>();
    private final List<ToolCallOrigin> preToolOrigins = new CopyOnWriteArrayList<>();
    private final List<ToolCallOrigin> postToolOrigins = new CopyOnWriteArrayList<>();
    private final List<Integer> preToolIterations = new CopyOnWriteArrayList<>();
    private final List<InvokerType> invokerTypes = new CopyOnWriteArrayList<>();

    private DefaultHookRegistry hookRegistry;
    private DefaultToolRegistry registry;
    private SingleToolInvoker invoker;
    private InterruptCoordinator coordinator;

    @BeforeEach
    void setUp() {
        hookRegistry = new DefaultHookRegistry();
        hookRegistry.register(HookEventType.PERMISSION_REQUEST, (PermissionRequestHook) context -> {
            events.add("permission");
            permissionOrigins.add(context.getOrigin());
            invokerTypes.add(context.getInvokerType());
            return HookResult.success();
        });
        hookRegistry.register(HookEventType.PRE_TOOL, (PreToolHook) context -> {
            events.add("preTool");
            preToolOrigins.add(context.getOrigin());
            preToolIterations.add(context.getIterationCount());
            return HookResult.success();
        });
        hookRegistry.register(HookEventType.POST_TOOL, (PostToolHook) context -> {
            events.add("postTool");
            postToolOrigins.add(context.getOrigin());
            return HookResult.success();
        });
        registry = new DefaultToolRegistry();
        registry.register(new RecordingTool("Write", SideEffectLevel.MUTATING, events));
        registry.register(new RecordingTool("Read", SideEffectLevel.READ_ONLY, events));
        final SideEffectApprovalGate gate = new SideEffectApprovalGate(prompt -> {
            events.add("approval");
            return Decision.ALLOW;
        });
        invoker = new SingleToolInvoker(new DefaultToolExecutionManager(), new DefaultHookExecutionManager(), gate);
        coordinator = new DefaultInterruptCoordinator();
    }

    @AfterEach
    void tearDown() {
        coordinator.close();
    }

    private DefaultToolCallDispatcher dispatcher(InvokerType type, List<AllowedTool> allowedTools) {
        return DefaultToolCallDispatcher.builder().invoker(invoker).invokerType(type).invokerName("agent")
                .hookRegistry(hookRegistry).executionAttributes(Map.of()).toolRegistry(registry)
                .sessionRegistry(registry).allowedTools(allowedTools).coordinator(coordinator).build();
    }

    private static ToolContext contextAt(int iteration) {
        return ToolContext.builder().put(ToolContextKeys.ITERATION_COUNT, iteration).build();
    }

    private static ToolUse write(String id) {
        return ToolUse.of(id, "Write", Map.of());
    }

    @Test
    @DisplayName("main loop, fork loop and slash-command skill loop all pass the same gates in the same order")
    void everyLoopPassesTheSameGatesInTheSameOrder() {
        final List<String> expected = List.of("permission", "approval", "preTool", "tool:Write", "postTool");

        dispatcher(InvokerType.MAIN_AGENT, List.of()).dispatch(write("main"), ToolCallOrigin.direct(), contextAt(1));
        final List<String> main = List.copyOf(events);
        events.clear();
        dispatcher(InvokerType.SUBAGENT, List.of()).dispatch(write("fork"), ToolCallOrigin.direct(), contextAt(1));
        final List<String> fork = List.copyOf(events);
        events.clear();
        dispatcher(InvokerType.MAIN_AGENT, List.of()).narrow(registry, List.of(AllowedTool.parse("Write")))
                .dispatch(write("skill"), ToolCallOrigin.skill("audit"), contextAt(0));
        final List<String> skill = List.copyOf(events);

        assertThat(main).isEqualTo(expected);
        assertThat(fork).isEqualTo(expected);
        assertThat(skill).isEqualTo(expected);
        assertThat(invokerTypes).containsExactly(InvokerType.MAIN_AGENT, InvokerType.SUBAGENT, InvokerType.MAIN_AGENT);
    }

    @Test
    @DisplayName("the origin of each call reaches the PermissionRequest, PreTool and PostTool contexts")
    void originReachesAllThreeHookContexts() {
        final ToolCallDispatcher dispatcher = dispatcher(InvokerType.MAIN_AGENT, List.of());
        final ToolCallOrigin code = ToolCallOrigin.code("tu-parent", "javascript");

        dispatcher.dispatch(write("a"), ToolCallOrigin.direct(), contextAt(1));
        dispatcher.dispatch(write("b"), code, contextAt(1));
        dispatcher.dispatch(write("c"), ToolCallOrigin.skill("audit"), contextAt(1));

        final List<ToolCallOrigin> expected = List.of(ToolCallOrigin.direct(), code, ToolCallOrigin.skill("audit"));
        assertThat(permissionOrigins).isEqualTo(expected);
        assertThat(preToolOrigins).isEqualTo(expected);
        assertThat(postToolOrigins).isEqualTo(expected);
    }

    @Test
    @DisplayName("the loop's iteration is read from the context it hands over; absent reads as 0")
    void iterationComesFromTheContext() {
        final ToolCallDispatcher dispatcher = dispatcher(InvokerType.MAIN_AGENT, List.of());

        dispatcher.dispatch(write("a"), ToolCallOrigin.direct(), contextAt(7));
        dispatcher.dispatch(write("b"), ToolCallOrigin.direct(), ToolContext.empty());

        assertThat(preToolIterations).containsExactly(7, 0);
    }

    @Test
    @DisplayName("narrow refuses a registry holding a tool the original does not — even when the original is unrestricted")
    void narrowRefusesAWiderRegistry() {
        final ToolRegistry wider = new DefaultToolRegistry();
        wider.register(registry.findByName("Write").orElseThrow());
        wider.register(new RecordingTool("Bash", SideEffectLevel.MUTATING, events));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> dispatcher(InvokerType.MAIN_AGENT, List.of()).narrow(wider, List.of()))
                .withMessageContaining("Bash");
        assertThatIllegalArgumentException().isThrownBy(
                () -> dispatcher(InvokerType.MAIN_AGENT, List.of(AllowedTool.parse("Write"))).narrow(wider, List.of()))
                .withMessageContaining("Bash");
    }

    @Test
    @DisplayName("narrow refuses a different tool registered under a name the original knows")
    void narrowRefusesAnImpostorUnderAKnownName() {
        final ToolRegistry impostor = new DefaultToolRegistry();
        impostor.register(new RecordingTool("Write", SideEffectLevel.MUTATING, events));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> dispatcher(InvokerType.MAIN_AGENT, List.of()).narrow(impostor, List.of()))
                .withMessageContaining("Write");
    }

    @Test
    @DisplayName("narrow accepts a subset of the original's tools and runs calls against it")
    void narrowAcceptsASubset() {
        final ToolRegistry subset = new DefaultToolRegistry();
        subset.register(registry.findByName("Read").orElseThrow());

        final ToolCallDispatcher narrowed = dispatcher(InvokerType.MAIN_AGENT, List.of()).narrow(subset, List.of());
        final ToolDispatchResult read = narrowed.dispatch(ToolUse.of("r", "Read", Map.of()), ToolCallOrigin.direct(),
                ToolContext.empty());
        final ToolDispatchResult write = narrowed.dispatch(write("w"), ToolCallOrigin.direct(), ToolContext.empty());

        assertThat(read.isError()).isFalse();
        assertThat(write.isError()).as("Write is not in the narrowed registry").isTrue();
        assertThat(events).doesNotContain("tool:Write");
    }

    @Test
    @DisplayName("narrow intersects the allow-lists: a tool outside the narrower list is refused when the call runs")
    void narrowIntersectsTheAllowList() {
        final ToolCallDispatcher narrowed = dispatcher(InvokerType.MAIN_AGENT, List.of()).narrow(registry,
                List.of(AllowedTool.parse("Read")));

        final ToolDispatchResult result = narrowed.dispatch(write("w"), ToolCallOrigin.skill("audit"),
                ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(events).doesNotContain("tool:Write");
    }

    @Test
    @DisplayName("narrow cannot widen a restricted allow-list, and refuses two lists with nothing in common")
    void narrowCannotWidenTheAllowList() {
        final ToolCallDispatcher readOnly = dispatcher(InvokerType.MAIN_AGENT, List.of(AllowedTool.parse("Read")));

        final ToolDispatchResult widened = readOnly.narrow(registry, List.of()).dispatch(write("w"),
                ToolCallOrigin.direct(), ToolContext.empty());

        assertThat(widened.isError()).isTrue();
        assertThat(events).doesNotContain("tool:Write");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> readOnly.narrow(registry, List.of(AllowedTool.parse("Write"))))
                .withMessageContaining("nothing in common");
    }

    @Test
    @DisplayName("dispatch never throws: a failure around the pipeline comes back as an error result")
    void dispatchNeverThrows() {
        final ToolRegistry broken = mock(ToolRegistry.class);
        when(broken.findByName(any())).thenThrow(new IllegalStateException("registry down"));
        final ToolCallDispatcher dispatcher = DefaultToolCallDispatcher.builder().invoker(invoker)
                .invokerType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(hookRegistry)
                .executionAttributes(Map.of()).toolRegistry(broken).sessionRegistry(broken).allowedTools(List.of())
                .coordinator(coordinator).build();

        final ToolDispatchResult result = dispatcher.dispatch(write("w"), ToolCallOrigin.direct(), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getText()).contains("registry down");
    }

    @Test
    @DisplayName("hook feedback comes back beside the text, and the direct-path conversion appends it once")
    void feedbackTravelsBesideTheText() {
        hookRegistry.register(HookEventType.PRE_TOOL, (PreToolHook) context -> HookResult.withFeedback("mind it"));

        final ToolDispatchResult result = dispatcher(InvokerType.MAIN_AGENT, List.of())
                .dispatch(ToolUse.of("r", "Read", Map.of()), ToolCallOrigin.direct(), ToolContext.empty());

        assertThat(result.getText()).isEqualTo("ran Read");
        assertThat(result.getHookFeedback()).containsExactly("mind it");
        final ToolUseResult converted = ToolDispatchResults.toToolUseResult(result);
        assertThat(converted.getContent()).startsWith("ran Read\n\n<system-reminder").contains("mind it");
    }

    /** Records each execution in the shared event list. */
    private static final class RecordingTool implements Tool {
        private final String name;
        private final SideEffectLevel sideEffectLevel;
        private final List<String> events;

        RecordingTool(String name, SideEffectLevel sideEffectLevel, List<String> events) {
            this.name = name;
            this.sideEffectLevel = sideEffectLevel;
            this.events = events;
        }

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of(name, name + " tool", Map.of("type", "object"));
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return sideEffectLevel;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            events.add("tool:" + name);
            return ToolResult.success("ran " + name);
        }
    }
}
