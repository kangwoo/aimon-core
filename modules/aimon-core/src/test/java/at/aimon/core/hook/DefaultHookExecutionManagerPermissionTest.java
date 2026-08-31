package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.event.PermissionDeniedContext;
import at.aimon.core.hook.event.PermissionDeniedHook;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.FlowControl;
import at.aimon.core.hook.execution.HookExecutor;
import at.aimon.core.hook.execution.HookResult;

/**
 * Phase 3 WI-3.3.b — verifies that {@link DefaultHookExecutionManager} routes Permission* hooks through the executor
 * and applies ASK promotion to {@code executePermissionRequest} results.
 */
class DefaultHookExecutionManagerPermissionTest {

    private static final Environment ENV = Environment.createDefault();

    @Test
    void executePermissionRequestRoutesToPermissionRequestHooks() {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(List.of(HookResult.allow()));
        final HookExecutionManager manager = managerWith(executor, AskPromptHandler.denyAll());

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PERMISSION_REQUEST)).thenReturn(List.<PermissionRequestHook>of());

        final PermissionRequestContext ctx = PermissionRequestContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).toolName("Bash").toolInput(ToolInput.of())
                .build();

        final List<HookResult> results = manager.executePermissionRequest(ctx);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDecision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void executePermissionRequestPromotesAskViaHandler() {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(List.of(HookResult.ask("approve?")));
        final HookExecutionManager manager = managerWith(executor, AskPromptHandler.denyAll());

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PERMISSION_REQUEST)).thenReturn(List.<PermissionRequestHook>of());

        final PermissionRequestContext ctx = PermissionRequestContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).toolName("Bash").toolInput(ToolInput.of())
                .build();

        final List<HookResult> results = manager.executePermissionRequest(ctx);

        assertThat(results).hasSize(1);
        final HookResult promoted = results.get(0);
        assertThat(promoted.getDecision()).isEqualTo(Decision.DENY);
        assertThat(promoted.getFlowControl()).isEqualTo(FlowControl.BLOCK);
        assertThat(promoted.isBlocked()).isTrue();
        assertThat(promoted.getFeedback()).contains("approve?");
    }

    @Test
    void executePermissionDeniedRoutesToPermissionDeniedHooks() {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(List.of(HookResult.allow()));
        final HookExecutionManager manager = managerWith(executor, AskPromptHandler.denyAll());

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PERMISSION_DENIED)).thenReturn(List.<PermissionDeniedHook>of());

        final PermissionDeniedContext ctx = PermissionDeniedContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).toolName("Bash").toolInput(ToolInput.of())
                .denyReason("policy").build();

        final List<HookResult> results = manager.executePermissionDenied(ctx);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDecision()).isEqualTo(Decision.ALLOW);
    }

    private static HookExecutionManager managerWith(HookExecutor executor, AskPromptHandler askPromptHandler) {
        return DefaultHookExecutionManager.builder().executor(executor).askPromptHandler(askPromptHandler).build();
    }
}
