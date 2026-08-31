package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.OnConfigReloadHook;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookExecutor;
import at.aimon.core.hook.execution.HookResult;

/**
 * Phase 3 WI-3.3.e — verifies that {@link DefaultHookExecutionManager} routes OnConfigReload hooks through the
 * executor.
 */
class DefaultHookExecutionManagerConfigReloadTest {

    private static final Environment ENV = Environment.createDefault();

    @Test
    void executeOnConfigReloadRoutesToOnConfigReloadHooks() {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(List.of(HookResult.allow()));
        final HookExecutionManager manager = managerWith(executor);

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).thenReturn(List.<OnConfigReloadHook>of());

        final OnConfigReloadContext ctx = OnConfigReloadContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("config-watcher").hookRegistry(registry).environment(ENV).reloadCounter(1L)
                .configSource("/etc/aimon/hooks.json").build();

        final List<HookResult> results = manager.executeOnConfigReload(ctx);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDecision()).isEqualTo(Decision.ALLOW);
    }

    private static HookExecutionManager managerWith(HookExecutor executor) {
        return DefaultHookExecutionManager.builder().executor(executor).askPromptHandler(AskPromptHandler.denyAll())
                .build();
    }
}
