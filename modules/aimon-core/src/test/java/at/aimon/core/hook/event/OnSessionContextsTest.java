package at.aimon.core.hook.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;

/**
 * Phase 3 WI-3.3.d — verifies {@link OnSessionStartContext} / {@link OnSessionEndContext} builder validation and field
 * exposure, plus the "no session behind this firing" case the three session-labelled contexts share (
 * {@link PreCompactContext} included, since a rewake replay drives it too).
 */
class OnSessionContextsTest {

    private static final Environment ENV = Environment.createDefault();
    private static final SessionId CID = SessionId.generate();

    @Test
    void onSessionStartContextRequiresMandatoryFields() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException().isThrownBy(() -> OnSessionStartContext.builder()
                .invokerType(InvokerType.MAIN_AGENT).invokerName("main").environment(ENV).sessionId(CID).build());
    }

    /**
     * The session id is <em>not</em> mandatory, and that is the point: a rewake replay fires this chain with no
     * session behind it. Before it became optional the replay minted {@code SessionId.of("rewake:" + envelopeId)},
     * which a hook could not tell apart from a real session.
     */
    @Test
    void onSessionContextsAcceptNoSessionAtAll() {
        final HookRegistry registry = new DefaultHookRegistry();
        final ExecutionId run = ExecutionId.of("rewake:env-1");

        final OnSessionStartContext start = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).executionId(run).build();
        assertThat(start.getSessionId()).isEmpty();
        assertThat(start.getExecutionId()).contains(run);

        final OnSessionEndContext end = OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).executionId(run).build();
        assertThat(end.getSessionId()).isEmpty();
        assertThat(end.getExecutionId()).contains(run);
    }

    @Test
    void onSessionContextsCarryNoExecutionIdWhenSessionBacked() {
        final HookRegistry registry = new DefaultHookRegistry();

        final OnSessionStartContext start = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).sessionId(CID).build();
        assertThat(start.getExecutionId()).isEmpty();

        final OnSessionEndContext end = OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).sessionId(CID).build();
        assertThat(end.getExecutionId()).isEmpty();
    }

    @Test
    void onSessionStartContextExposesAllFields() {
        final HookRegistry registry = new DefaultHookRegistry();
        final Instant ts = Instant.parse("2026-05-08T00:00:00Z");

        final OnSessionStartContext ctx = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).sessionId(CID)
                .agentRuntimeId("agent:default").timestamp(ts).build();

        assertThat(ctx.getInvokerType()).isEqualTo(InvokerType.MAIN_AGENT);
        assertThat(ctx.getInvokerName()).isEqualTo("main");
        assertThat(ctx.getSessionId()).contains(CID);
        assertThat(ctx.getAgentRuntimeId()).isEqualTo("agent:default");
        assertThat(ctx.getTimestamp()).isEqualTo(ts);
    }

    @Test
    void onSessionStartRuntimeIdDefaultsToEmpty() {
        final HookRegistry registry = new DefaultHookRegistry();
        final OnSessionStartContext ctx = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).sessionId(CID).build();
        assertThat(ctx.getAgentRuntimeId()).isEmpty();
    }

    @Test
    void onSessionEndContextExposesCleanAndAbnormalPaths() {
        final HookRegistry registry = new DefaultHookRegistry();

        final OnSessionEndContext clean = OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).sessionId(CID).clean(true).build();
        assertThat(clean.isClean()).isTrue();
        assertThat(clean.getTerminationReason()).isEmpty();

        final OnSessionEndContext abnormal = OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).sessionId(CID).clean(false)
                .terminationReason("crashed").build();
        assertThat(abnormal.isClean()).isFalse();
        assertThat(abnormal.getTerminationReason()).contains("crashed");
    }

    /**
     * {@code PreCompactContext} keeps its session id as a plain {@code String} rather than a {@code SessionId}, so
     * "no session" reads as empty there instead of an empty {@code Optional} — same honesty, different shape.
     */
    @Test
    void preCompactContextTreatsAnUnsetSessionIdAsEmpty() {
        final HookRegistry registry = new DefaultHookRegistry();
        final ExecutionId run = ExecutionId.of("rewake:env-1");

        final PreCompactContext ctx = PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).trigger(CompactionTrigger.MANUAL)
                .executionId(run).build();

        assertThat(ctx.getSessionIdValue()).isEmpty();
        assertThat(ctx.getExecutionId()).contains(run);
    }
}
