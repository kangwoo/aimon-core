package at.aimon.core.hook.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;

class PermissionContextsTest {

    @Test
    void permissionRequestContextRequiresMandatoryFields() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException()
                .isThrownBy(() -> PermissionRequestContext.builder().origin(ToolCallOrigin.direct()).invokerName("a")
                        .hookRegistry(registry).toolName("Bash").toolInput(ToolInput.of()).build());
    }

    @Test
    void permissionRequestContextBuildsAndExposesAllFields() {
        final HookRegistry registry = new DefaultHookRegistry();
        final Principal principal = Principal.user("u1", "alice");
        final ToolInput input = ToolInput.of(Map.of("command", "echo hi"));
        final Instant ts = Instant.parse("2026-05-08T00:00:00Z");

        final PermissionRequestContext ctx = PermissionRequestContext.builder().origin(ToolCallOrigin.direct())
                .invokerType(InvokerType.MAIN_AGENT).invokerName("main").hookRegistry(registry).toolName("Bash")
                .toolInput(input).principal(principal).timestamp(ts).executionAttributes(Map.of("k", "v")).build();

        assertThat(ctx.getInvokerType()).isEqualTo(InvokerType.MAIN_AGENT);
        assertThat(ctx.getInvokerName()).isEqualTo("main");
        assertThat(ctx.getHookRegistry()).isSameAs(registry);
        assertThat(ctx.getToolName()).isEqualTo("Bash");
        assertThat(ctx.getToolInput()).isEqualTo(input);
        assertThat(ctx.getPrincipal()).contains(principal);
        assertThat(ctx.getTimestamp()).isEqualTo(ts);
        assertThat(ctx.getExecutionAttributes()).containsEntry("k", "v");
    }

    @Test
    void permissionRequestPrincipalIsOptional() {
        final HookRegistry registry = new DefaultHookRegistry();
        final PermissionRequestContext ctx = PermissionRequestContext.builder().origin(ToolCallOrigin.direct())
                .invokerType(InvokerType.MAIN_AGENT).invokerName("main").hookRegistry(registry).toolName("Read")
                .toolInput(ToolInput.of()).build();
        assertThat(ctx.getPrincipal()).isEmpty();
    }

    @Test
    void permissionDeniedContextRequiresDenyReason() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException()
                .isThrownBy(() -> PermissionDeniedContext.builder().invokerType(InvokerType.MAIN_AGENT)
                        .invokerName("main").hookRegistry(registry).toolName("Bash").toolInput(ToolInput.of()).build());
    }

    @Test
    void permissionDeniedContextExposesAllFields() {
        final HookRegistry registry = new DefaultHookRegistry();
        final ToolInput input = ToolInput.of(Map.of("command", "rm -rf /"));
        final PermissionDeniedContext ctx = PermissionDeniedContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("main").hookRegistry(registry).toolName("Bash").toolInput(input).denyReason("destructive")
                .build();

        assertThat(ctx.getToolName()).isEqualTo("Bash");
        assertThat(ctx.getToolInput()).isEqualTo(input);
        assertThat(ctx.getDenyReason()).isEqualTo("destructive");
        assertThat(ctx.getPrincipal()).isEmpty();
    }

    @Test
    void permissionRequestContextRequiresAndExposesTheOrigin() {
        assertThatNullPointerException().isThrownBy(() -> PermissionRequestContext.builder()
                .invokerType(InvokerType.MAIN_AGENT).invokerName("a").hookRegistry(new DefaultHookRegistry())
                .toolName("Bash").toolInput(ToolInput.of(Map.of())).build()).withMessageContaining("Origin");
        final PermissionRequestContext ctx = PermissionRequestContext.builder().origin(ToolCallOrigin.skill("s"))
                .invokerType(InvokerType.MAIN_AGENT).invokerName("a").hookRegistry(new DefaultHookRegistry())
                .toolName("Bash").toolInput(ToolInput.of(Map.of())).build();
        assertThat(ctx.getOrigin()).isEqualTo(ToolCallOrigin.skill("s"));
    }
}
