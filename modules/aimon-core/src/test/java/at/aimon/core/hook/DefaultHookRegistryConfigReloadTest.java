package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.event.OnConfigReloadHook;
import at.aimon.core.hook.execution.HookResult;

/**
 * Phase 3 WI-3.3.e — verifies {@link DefaultHookRegistry} register/get/clear semantics for the new
 * {@code OnConfigReload} hook list.
 */
class DefaultHookRegistryConfigReloadTest {

    private static final OnConfigReloadHook HOOK_A = ctx -> HookResult.allow();
    private static final OnConfigReloadHook HOOK_B = ctx -> HookResult.allow();

    @Test
    void emptyRegistryReturnsEmptyList() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThat(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void registerAndGetPreservesOrder() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_CONFIG_RELOAD, HOOK_A);
        registry.register(HookEventType.ON_CONFIG_RELOAD, HOOK_B);

        assertThat(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).containsExactly(HOOK_A, HOOK_B);
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void registerRejectsNull() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException().isThrownBy(() -> registry.register(HookEventType.ON_CONFIG_RELOAD, null));
    }

    @Test
    void unregisterRemovesAndReturnsTrue() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_CONFIG_RELOAD, HOOK_A);
        assertThat(registry.unregister(HookEventType.ON_CONFIG_RELOAD, HOOK_A)).isTrue();
        assertThat(registry.unregister(HookEventType.ON_CONFIG_RELOAD, HOOK_A)).isFalse();
        assertThat(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).isEmpty();
    }

    @Test
    void clearAllRemovesConfigReloadHooks() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_CONFIG_RELOAD, HOOK_A);

        registry.clearAll();

        assertThat(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }
}
