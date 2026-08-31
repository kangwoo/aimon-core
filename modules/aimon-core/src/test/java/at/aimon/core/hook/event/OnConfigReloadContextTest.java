package at.aimon.core.hook.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;

/**
 * Phase 3 WI-3.3.e — verifies {@link OnConfigReloadContext} builder validation and field exposure.
 */
class OnConfigReloadContextTest {

    private static final Environment ENV = Environment.createDefault();

    @Test
    void builderRequiresMandatoryFields() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException().isThrownBy(() -> OnConfigReloadContext.builder()
                .invokerType(InvokerType.MAIN_AGENT).invokerName("main").environment(ENV).build());
        assertThatNullPointerException().isThrownBy(() -> OnConfigReloadContext.builder()
                .invokerType(InvokerType.MAIN_AGENT).invokerName("main").hookRegistry(registry).build());
    }

    @Test
    void builderRejectsNegativeReloadCounter() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OnConfigReloadContext.builder().invokerType(InvokerType.MAIN_AGENT)
                        .invokerName("main").hookRegistry(registry).environment(ENV).reloadCounter(-1L).build());
    }

    @Test
    void exposesAllFieldsForSuccessfulReload() {
        final HookRegistry registry = new DefaultHookRegistry();
        final Instant ts = Instant.parse("2026-05-08T00:00:00Z");

        final OnConfigReloadContext ctx = OnConfigReloadContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("config-watcher").hookRegistry(registry).environment(ENV).reloadCounter(7L)
                .configSource("/etc/aimon/hooks.json").successful(true).timestamp(ts).build();

        assertThat(ctx.getInvokerType()).isEqualTo(InvokerType.MAIN_AGENT);
        assertThat(ctx.getInvokerName()).isEqualTo("config-watcher");
        assertThat(ctx.getReloadCounter()).isEqualTo(7L);
        assertThat(ctx.getConfigSource()).isEqualTo("/etc/aimon/hooks.json");
        assertThat(ctx.isSuccessful()).isTrue();
        assertThat(ctx.getFailureReason()).isEmpty();
        assertThat(ctx.getTimestamp()).isEqualTo(ts);
    }

    @Test
    void exposesFailurePathWithReason() {
        final HookRegistry registry = new DefaultHookRegistry();
        final OnConfigReloadContext ctx = OnConfigReloadContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("config-watcher").hookRegistry(registry).environment(ENV).reloadCounter(3L)
                .successful(false).failureReason("malformed json").build();

        assertThat(ctx.isSuccessful()).isFalse();
        assertThat(ctx.getFailureReason()).isEqualTo("malformed json");
        assertThat(ctx.getConfigSource()).isEmpty();
    }

    @Test
    void defaultsAreApplied() {
        final HookRegistry registry = new DefaultHookRegistry();
        final OnConfigReloadContext ctx = OnConfigReloadContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("config-watcher").hookRegistry(registry).environment(ENV).build();

        assertThat(ctx.getReloadCounter()).isZero();
        assertThat(ctx.getConfigSource()).isEmpty();
        assertThat(ctx.isSuccessful()).isTrue();
        assertThat(ctx.getFailureReason()).isEmpty();
        assertThat(ctx.getTimestamp()).isNotNull();
        assertThat(ctx.getExecutionAttributes()).isEmpty();
    }
}
