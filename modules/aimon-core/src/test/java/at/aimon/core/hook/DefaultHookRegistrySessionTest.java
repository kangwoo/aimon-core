package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.event.OnSessionEndHook;
import at.aimon.core.hook.event.OnSessionStartHook;
import at.aimon.core.hook.execution.HookResult;

/**
 * Phase 3 WI-3.3.d — verifies {@link DefaultHookRegistry} register/get/clear semantics for the new session-lifecycle
 * hook lists.
 */
class DefaultHookRegistrySessionTest {

    private static final OnSessionStartHook START_A = ctx -> HookResult.allow();
    private static final OnSessionStartHook START_B = ctx -> HookResult.allow();
    private static final OnSessionEndHook END_A = ctx -> HookResult.allow();

    @Test
    void emptyRegistryReturnsEmptySessionLists() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThat(registry.getHooks(HookEventType.ON_SESSION_START)).isEmpty();
        assertThat(registry.getHooks(HookEventType.ON_SESSION_END)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void registerAndGetOnSessionStartHooksPreservesOrder() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_SESSION_START, START_A);
        registry.register(HookEventType.ON_SESSION_START, START_B);

        assertThat(registry.getHooks(HookEventType.ON_SESSION_START)).containsExactly(START_A, START_B);
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void registerOnSessionEndHookExposed() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_SESSION_END, END_A);
        assertThat(registry.getHooks(HookEventType.ON_SESSION_END)).containsExactly(END_A);
    }

    @Test
    void registerOnSessionStartHookRejectsNull() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException().isThrownBy(() -> registry.register(HookEventType.ON_SESSION_START, null));
    }

    @Test
    void registerOnSessionEndHookRejectsNull() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException().isThrownBy(() -> registry.register(HookEventType.ON_SESSION_END, null));
    }

    @Test
    void unregisterRemovesSessionHookAndReturnsTrue() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_SESSION_START, START_A);
        assertThat(registry.unregister(HookEventType.ON_SESSION_START, START_A)).isTrue();
        assertThat(registry.unregister(HookEventType.ON_SESSION_START, START_A)).isFalse();
        assertThat(registry.getHooks(HookEventType.ON_SESSION_START)).isEmpty();
    }

    @Test
    void clearAllRemovesSessionHooks() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_SESSION_START, START_A);
        registry.register(HookEventType.ON_SESSION_END, END_A);

        registry.clearAll();

        assertThat(registry.getHooks(HookEventType.ON_SESSION_START)).isEmpty();
        assertThat(registry.getHooks(HookEventType.ON_SESSION_END)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }

}
