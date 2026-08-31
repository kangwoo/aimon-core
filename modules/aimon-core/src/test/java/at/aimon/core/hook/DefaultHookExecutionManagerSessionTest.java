package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionEndHook;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnSessionStartHook;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookExecutor;
import at.aimon.core.hook.execution.HookResult;

/**
 * Phase 3 WI-3.3.d — verifies that {@link DefaultHookExecutionManager} routes OnSession* hooks through the executor.
 */
class DefaultHookExecutionManagerSessionTest {

    private static final Environment ENV = Environment.createDefault();
    private static final SessionId CID = SessionId.generate();

    @Test
    void executeOnSessionStartRoutesToOnSessionStartHooks() {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(List.of(HookResult.allow()));
        final HookExecutionManager manager = managerWith(executor);

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.ON_SESSION_START)).thenReturn(List.<OnSessionStartHook>of());

        final OnSessionStartContext ctx = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).sessionId(CID).build();

        final List<HookResult> results = manager.executeOnSessionStart(ctx);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDecision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void executeOnSessionEndRoutesToOnSessionEndHooks() {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(List.of(HookResult.allow()));
        final HookExecutionManager manager = managerWith(executor);

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.ON_SESSION_END)).thenReturn(List.<OnSessionEndHook>of());

        final OnSessionEndContext ctx = OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).sessionId(CID).clean(true).build();

        final List<HookResult> results = manager.executeOnSessionEnd(ctx);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDecision()).isEqualTo(Decision.ALLOW);
    }

    private static HookExecutionManager managerWith(HookExecutor executor) {
        return DefaultHookExecutionManager.builder().executor(executor).askPromptHandler(AskPromptHandler.denyAll())
                .build();
    }
}
