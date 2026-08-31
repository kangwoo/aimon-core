package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.event.SubagentStartHook;
import at.aimon.core.hook.event.SubagentStopHook;
import at.aimon.core.hook.execution.HookResult;

/**
 * Phase 3 WI-3.3.c — verifies {@link DefaultHookRegistry} register/get/clear semantics for the new subagent hook
 * lists.
 */
class DefaultHookRegistrySubagentTest {

    private static final SubagentStartHook START_A = ctx -> HookResult.allow();
    private static final SubagentStartHook START_B = ctx -> HookResult.allow();
    private static final SubagentStopHook STOP_A = ctx -> HookResult.allow();

    @Test
    void emptyRegistryReturnsEmptySubagentLists() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThat(registry.getHooks(HookEventType.SUBAGENT_START)).isEmpty();
        assertThat(registry.getHooks(HookEventType.SUBAGENT_STOP)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void registerAndGetSubagentStartHooksPreservesOrder() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.SUBAGENT_START, START_A);
        registry.register(HookEventType.SUBAGENT_START, START_B);

        assertThat(registry.getHooks(HookEventType.SUBAGENT_START)).containsExactly(START_A, START_B);
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void registerSubagentStopHookExposed() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.SUBAGENT_STOP, STOP_A);
        assertThat(registry.getHooks(HookEventType.SUBAGENT_STOP)).containsExactly(STOP_A);
    }

    @Test
    void registerSubagentStartHookRejectsNull() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException().isThrownBy(() -> registry.register(HookEventType.SUBAGENT_START, null));
    }

    @Test
    void registerSubagentStopHookRejectsNull() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException().isThrownBy(() -> registry.register(HookEventType.SUBAGENT_STOP, null));
    }

    @Test
    void unregisterRemovesSubagentHookAndReturnsTrue() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.SUBAGENT_START, START_A);
        assertThat(registry.unregister(HookEventType.SUBAGENT_START, START_A)).isTrue();
        assertThat(registry.unregister(HookEventType.SUBAGENT_START, START_A)).isFalse();
        assertThat(registry.getHooks(HookEventType.SUBAGENT_START)).isEmpty();
    }

    @Test
    void clearAllRemovesSubagentHooks() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.SUBAGENT_START, START_A);
        registry.register(HookEventType.SUBAGENT_STOP, STOP_A);

        registry.clearAll();

        assertThat(registry.getHooks(HookEventType.SUBAGENT_START)).isEmpty();
        assertThat(registry.getHooks(HookEventType.SUBAGENT_STOP)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }

}
