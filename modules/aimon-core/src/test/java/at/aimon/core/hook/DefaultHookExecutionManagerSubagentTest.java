package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStartHook;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.event.SubagentStopHook;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookExecutor;
import at.aimon.core.hook.execution.HookResult;

/**
 * Phase 3 WI-3.3.c — verifies that {@link DefaultHookExecutionManager} routes Subagent* hooks through the executor.
 */
class DefaultHookExecutionManagerSubagentTest {

    private static final Environment ENV = Environment.createDefault();

    @Test
    void executeSubagentStartRoutesToSubagentStartHooks() {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(List.of(HookResult.allow()));
        final HookExecutionManager manager = managerWith(executor);

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.SUBAGENT_START)).thenReturn(List.<SubagentStartHook>of());

        final SubagentStartContext ctx = SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).subagentName("Explore").taskId("t-1")
                .goal("g").build();

        final List<HookResult> results = manager.executeSubagentStart(ctx);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDecision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void executeSubagentStopRoutesToSubagentStopHooks() {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(List.of(HookResult.allow()));
        final HookExecutionManager manager = managerWith(executor);

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.SUBAGENT_STOP)).thenReturn(List.<SubagentStopHook>of());

        final SubagentStopContext ctx = SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).subagentName("Explore").taskId("t-1")
                .success(true).build();

        final List<HookResult> results = manager.executeSubagentStop(ctx);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDecision()).isEqualTo(Decision.ALLOW);
    }

    private static HookExecutionManager managerWith(HookExecutor executor) {
        return DefaultHookExecutionManager.builder().executor(executor).askPromptHandler(AskPromptHandler.denyAll())
                .build();
    }
}
