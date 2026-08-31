package at.aimon.core.hook.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.ToolUse;

class PreToolContextTest {

    private static PreToolContext newContext(ToolUse toolUse) {
        final HookRegistry registry = new DefaultHookRegistry();
        final Environment env = Environment.createDefault();
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .environment(env).toolUse(toolUse).iterationCount(1).build();
    }

    @Test
    void originalAndCurrentInputAreEqualOnFreshContext() {
        final ToolUse toolUse = ToolUse.of("id", "Bash", Map.of("command", "echo hi"));
        final PreToolContext ctx = newContext(toolUse);

        assertThat(ctx.getOriginalToolUse()).isSameAs(toolUse);
        assertThat(ctx.getCurrentToolUse()).isSameAs(toolUse);
        assertThat(ctx.originalInput().toMap()).isEqualTo(Map.of("command", "echo hi"));
        assertThat(ctx.currentInput().toMap()).isEqualTo(Map.of("command", "echo hi"));
    }

    @Test
    void withCurrentInputMutatesOnlyCurrent() {
        final ToolUse toolUse = ToolUse.of("id", "Bash", Map.of("command", "secret-pass"));
        final PreToolContext ctx = newContext(toolUse);

        final ToolInput redacted = ToolInput.of(Map.of("command", "***"));
        final PreToolContext next = ctx.withCurrentInput(redacted);

        assertThat(next.originalInput().toMap()).isEqualTo(Map.of("command", "secret-pass"));
        assertThat(next.currentInput().toMap()).isEqualTo(Map.of("command", "***"));
        assertThat(next.getCurrentToolUse().getName()).isEqualTo("Bash");
        assertThat(next.getCurrentToolUse().getId()).isEqualTo("id");
        // The rewritten input has to reach the ToolUse itself, not only currentInput(): hooks that read the whole
        // tool_use would otherwise still see the unredacted command.
        assertThat(next.getCurrentToolUse().getInput()).isEqualTo(Map.of("command", "***"));
    }
}
