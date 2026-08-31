package at.aimon.core.hook.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PermissionDeniedHook;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.execution.HookContext;

/**
 * Verifies that {@link RewakeEnvelopes#from} rejects async-rewakes scheduled for synchronous-only permission events
 * (design §6.2). Permission decisions must be answered synchronously to be meaningful — by the time a rewake fires,
 * the requesting tool dispatch has already moved on.
 */
class RewakeEnvelopesTest {

    @Test
    void permissionRequestEventIsRejected() {
        final HookContext ctx = mock(HookContext.class);
        when(ctx.getInvokerName()).thenReturn("agent-x");
        final PermissionRequestHook hook = mock(PermissionRequestHook.class);
        when(hook.getHookId()).thenReturn("perm-request-hook");
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(5)))
                .reason("retry-permission").build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RewakeEnvelopes.from(HookEventType.PERMISSION_REQUEST, ctx, hook, spec))
                .withMessageContaining("permissionRequest").withMessageContaining("perm-request-hook");
    }

    @Test
    void permissionDeniedEventIsRejected() {
        final HookContext ctx = mock(HookContext.class);
        when(ctx.getInvokerName()).thenReturn("agent-x");
        final PermissionDeniedHook hook = mock(PermissionDeniedHook.class);
        when(hook.getHookId()).thenReturn("perm-denied-hook");
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(5)))
                .reason("retry-after-denied").build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RewakeEnvelopes.from(HookEventType.PERMISSION_DENIED, ctx, hook, spec))
                .withMessageContaining("permissionDenied").withMessageContaining("perm-denied-hook");
    }

    @Test
    void onConfigReloadEventIsAccepted() {
        // Sanity check: the rejection is targeted, not over-broad. Other lifecycle events build envelopes normally.
        final HookContext ctx = mock(HookContext.class);
        when(ctx.getInvokerName()).thenReturn("agent-x");
        final at.aimon.core.hook.event.OnConfigReloadHook hook = mock(
                at.aimon.core.hook.event.OnConfigReloadHook.class);
        when(hook.getHookId()).thenReturn("reload-hook");
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(5)))
                .reason("retry-reload").build();

        final RewakeEnvelope env = RewakeEnvelopes.from(HookEventType.ON_CONFIG_RELOAD, ctx, hook, spec);

        assertThat(env.getOriginalEventType()).isEqualTo(HookEventType.ON_CONFIG_RELOAD);
        assertThat(env.getOriginatingHookId()).isEqualTo("reload-hook");
    }
}
