package at.aimon.core.hook.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

class PostToolContextTest {

    private static PostToolContext newContext(ToolUseResult result) {
        final HookRegistry registry = new DefaultHookRegistry();
        final Environment env = Environment.createDefault();
        final ToolUse toolUse = ToolUse.of("id", "Bash", Map.of("command", "echo"));
        return PostToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(registry).environment(env).toolUse(toolUse).toolUseResult(result).iterationCount(1)
                .build();
    }

    @Test
    void originalAndCurrentOutputAreEqualOnFreshContext() {
        final ToolUseResult result = ToolUseResult.success("id", "ok");
        final PostToolContext ctx = newContext(result);

        assertThat(ctx.getOriginalToolUseResult()).isSameAs(result);
        assertThat(ctx.getCurrentToolUseResult()).isSameAs(result);
        assertThat(ctx.originalOutput().getContent()).isEqualTo("ok");
        assertThat(ctx.currentOutput().getContent()).isEqualTo("ok");
        assertThat(ctx.currentOutput().isSuccess()).isTrue();
    }

    @Test
    void withCurrentOutputMutatesOnlyCurrent() {
        final ToolUseResult result = ToolUseResult.success("id", "secret-output");
        final PostToolContext ctx = newContext(result);

        final ToolResult masked = ToolResult.success("[redacted]");
        final PostToolContext next = ctx.withCurrentOutput(masked);

        assertThat(next.originalOutput().getContent()).isEqualTo("secret-output");
        assertThat(next.currentOutput().getContent()).isEqualTo("[redacted]");
        assertThat(next.getOriginalToolUseResult()).isSameAs(result);
        assertThat(next.getCurrentToolUseResult().getToolUseId()).isEqualTo("id");
    }

    @Test
    void withCurrentOutputPreservesErrorFlag() {
        final ToolUseResult result = ToolUseResult.success("id", "ok");
        final PostToolContext ctx = newContext(result);

        final ToolResult err = ToolResult.error("boom");
        final PostToolContext next = ctx.withCurrentOutput(err);

        assertThat(next.currentOutput().isError()).isTrue();
        assertThat(next.getCurrentToolUseResult().isError()).isTrue();
        assertThat(next.getCurrentToolUseResult().getContent()).isEqualTo("boom");
    }
}
