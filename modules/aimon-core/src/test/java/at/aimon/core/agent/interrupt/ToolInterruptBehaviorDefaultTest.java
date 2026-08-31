package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.ToolDefinition;

@DisplayName("Tool.getInterruptBehavior default Tests")
class ToolInterruptBehaviorDefaultTest {

    @Test
    @DisplayName("default Tool implementation declares NON_INTERRUPTIBLE")
    void defaultBehaviorIsNonInterruptible() {
        Tool tool = new NoopTool();

        assertThat(tool.getInterruptBehavior()).isEqualTo(InterruptBehavior.NON_INTERRUPTIBLE);
    }

    @Test
    @DisplayName("overriding tools surface their declared behaviour")
    void overriddenBehaviourIsReflected() {
        Tool tool = new NoopTool() {
            @Override
            public InterruptBehavior getInterruptBehavior() {
                return InterruptBehavior.COOPERATIVE;
            }
        };

        assertThat(tool.getInterruptBehavior()).isEqualTo(InterruptBehavior.COOPERATIVE);
    }

    private static class NoopTool implements Tool {

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of("Noop", "Does nothing.",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("noop");
        }
    }
}
