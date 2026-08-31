package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.FlowControl;
import at.aimon.core.hook.execution.HookExecutor;
import at.aimon.core.hook.execution.HookResult;

/**
 * Phase 3 WI-3.2.c — verifies that {@link DefaultHookExecutionManager#executePreTool} promotes {@link Decision#ASK}
 * results returned by the underlying executor into concrete {@link Decision#ALLOW} or {@link Decision#DENY} via the
 * configured {@link AskPromptHandler}.
 */
class DefaultHookExecutionManagerAskPromotionTest {

    @Test
    void askResultPromotedToAllowWhenHandlerAllows() {
        final HookExecutionManager manager = managerWithExecutor(List.of(HookResult.ask("Run risky command?")),
                AskPromptHandler.allowAll());

        final List<HookResult> results = manager.executePreTool(stubContext());

        assertThat(results).hasSize(1);
        final HookResult promoted = results.get(0);
        assertThat(promoted.getDecision()).isEqualTo(Decision.ALLOW);
        assertThat(promoted.getFlowControl()).isEqualTo(FlowControl.CONTINUE);
        assertThat(promoted.isBlocked()).isFalse();
        assertThat(promoted.getFeedback()).contains("Run risky command?");
    }

    @Test
    void askResultPromotedToDenyWhenHandlerDenies() {
        final HookExecutionManager manager = managerWithExecutor(List.of(HookResult.ask("Run risky command?")),
                AskPromptHandler.denyAll());

        final List<HookResult> results = manager.executePreTool(stubContext());

        assertThat(results).hasSize(1);
        final HookResult promoted = results.get(0);
        assertThat(promoted.getDecision()).isEqualTo(Decision.DENY);
        assertThat(promoted.getFlowControl()).isEqualTo(FlowControl.BLOCK);
        assertThat(promoted.isBlocked()).isTrue();
        assertThat(promoted.getFeedback()).contains("Run risky command?");
    }

    @Test
    void mixedResultsLeaveNonAskUntouched() {
        final HookResult allow = HookResult.allow();
        final HookResult ask = HookResult.ask("ok?");
        final HookResult deny = HookResult.deny("nope");

        final HookExecutionManager manager = managerWithExecutor(List.of(allow, ask, deny),
                AskPromptHandler.allowAll());

        final List<HookResult> results = manager.executePreTool(stubContext());

        assertThat(results).hasSize(3);
        assertThat(results.get(0)).isSameAs(allow);
        assertThat(results.get(1).getDecision()).isEqualTo(Decision.ALLOW);
        assertThat(results.get(2)).isSameAs(deny);
    }

    @Test
    void noAskResultsPassThroughUnchanged() {
        final List<HookResult> input = List.of(HookResult.allow(), HookResult.deny("blocked"));
        final HookExecutionManager manager = managerWithExecutor(input, AskPromptHandler.allowAll());

        final List<HookResult> results = manager.executePreTool(stubContext());

        assertThat(results).isEqualTo(input);
    }

    private static HookExecutionManager managerWithExecutor(List<HookResult> executorOutput,
            AskPromptHandler askPromptHandler) {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(executorOutput);
        return DefaultHookExecutionManager.builder().executor(executor).askPromptHandler(askPromptHandler).build();
    }

    private static PreToolContext stubContext() {
        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.<PreToolHook>of());
        final PreToolContext context = mock(PreToolContext.class);
        when(context.getHookRegistry()).thenReturn(registry);
        return context;
    }
}
