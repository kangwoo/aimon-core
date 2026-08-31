package at.aimon.core.hook.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;

/**
 * Phase 3 WI-3.3.c — verifies {@link SubagentStartContext} / {@link SubagentStopContext} builder validation and field
 * exposure.
 */
class SubagentContextsTest {

    private static final Environment ENV = Environment.createDefault();

    @Test
    void subagentStartContextRequiresMandatoryFields() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException()
                .isThrownBy(() -> SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("main")
                        .hookRegistry(registry).environment(ENV).subagentName("Explore").goal("find auth").build());
    }

    @Test
    void subagentStartContextExposesAllFields() {
        final HookRegistry registry = new DefaultHookRegistry();
        final Instant ts = Instant.parse("2026-05-08T00:00:00Z");

        final SubagentStartContext ctx = SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).subagentName("Explore").taskId("t-1")
                .goal("find auth files").description("auth audit").timestamp(ts).build();

        assertThat(ctx.getInvokerType()).isEqualTo(InvokerType.MAIN_AGENT);
        assertThat(ctx.getInvokerName()).isEqualTo("main");
        assertThat(ctx.getSubagentName()).isEqualTo("Explore");
        assertThat(ctx.getTaskId()).isEqualTo("t-1");
        assertThat(ctx.getGoal()).isEqualTo("find auth files");
        assertThat(ctx.getDescription()).isEqualTo("auth audit");
        assertThat(ctx.getTimestamp()).isEqualTo(ts);
    }

    @Test
    void subagentStartDescriptionDefaultsToEmpty() {
        final HookRegistry registry = new DefaultHookRegistry();
        final SubagentStartContext ctx = SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).subagentName("Explore").taskId("t-1")
                .goal("g").build();
        assertThat(ctx.getDescription()).isEmpty();
    }

    @Test
    void subagentStopContextExposesSuccessAndErrorPath() {
        final HookRegistry registry = new DefaultHookRegistry();

        final SubagentStopContext success = SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).subagentName("Explore").taskId("t-1")
                .success(true).build();
        assertThat(success.isSuccess()).isTrue();
        assertThat(success.getErrorMessage()).isEmpty();

        final SubagentStopContext failure = SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).environment(ENV).subagentName("Explore").taskId("t-1")
                .success(false).errorMessage("LLM timeout").build();
        assertThat(failure.isSuccess()).isFalse();
        assertThat(failure.getErrorMessage()).contains("LLM timeout");
    }
}
