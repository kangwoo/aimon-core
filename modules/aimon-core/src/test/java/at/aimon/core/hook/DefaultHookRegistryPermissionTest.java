package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.event.PermissionDeniedHook;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.execution.HookResult;

/**
 * Phase 3 WI-3.3.a — verifies {@link DefaultHookRegistry} register/get/clear semantics for the new permission hook
 * lists.
 */
class DefaultHookRegistryPermissionTest {

    private static final PermissionRequestHook ALWAYS_ALLOW = ctx -> HookResult.allow();
    private static final PermissionRequestHook ALWAYS_DENY = ctx -> HookResult.deny("nope");
    private static final PermissionDeniedHook AUDIT = ctx -> HookResult.allow();

    @Test
    void emptyRegistryReturnsEmptyPermissionLists() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThat(registry.getHooks(HookEventType.PERMISSION_REQUEST)).isEmpty();
        assertThat(registry.getHooks(HookEventType.PERMISSION_DENIED)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void registerAndGetPermissionRequestHooksPreservesOrder() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.PERMISSION_REQUEST, ALWAYS_ALLOW);
        registry.register(HookEventType.PERMISSION_REQUEST, ALWAYS_DENY);

        assertThat(registry.getHooks(HookEventType.PERMISSION_REQUEST)).containsExactly(ALWAYS_ALLOW, ALWAYS_DENY);
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void registerPermissionDeniedHookExposed() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.PERMISSION_DENIED, AUDIT);
        assertThat(registry.getHooks(HookEventType.PERMISSION_DENIED)).containsExactly(AUDIT);
    }

    @Test
    void registerPermissionRequestHookRejectsNull() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException().isThrownBy(() -> registry.register(HookEventType.PERMISSION_REQUEST, null));
    }

    @Test
    void registerPermissionDeniedHookRejectsNull() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException().isThrownBy(() -> registry.register(HookEventType.PERMISSION_DENIED, null));
    }

    @Test
    void unregisterRemovesHookAndReturnsTrue() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.PERMISSION_REQUEST, ALWAYS_ALLOW);
        assertThat(registry.unregister(HookEventType.PERMISSION_REQUEST, ALWAYS_ALLOW)).isTrue();
        assertThat(registry.unregister(HookEventType.PERMISSION_REQUEST, ALWAYS_ALLOW)).isFalse();
        assertThat(registry.getHooks(HookEventType.PERMISSION_REQUEST)).isEmpty();
    }

    @Test
    void clearAllRemovesPermissionHooks() {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.PERMISSION_REQUEST, ALWAYS_ALLOW);
        registry.register(HookEventType.PERMISSION_DENIED, AUDIT);

        registry.clearAll();

        assertThat(registry.getHooks(HookEventType.PERMISSION_REQUEST)).isEmpty();
        assertThat(registry.getHooks(HookEventType.PERMISSION_DENIED)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }

}
