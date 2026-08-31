package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The axis has no ordering to test — that is the point of it being a {@code *Behavior} rather than a {@code *Level},
 * and
 * the absence of a {@code permits}-style method is what this pins. What is left is the default, which every existing
 * tool inherits without being edited.
 */
@DisplayName("DestructiveBehavior Tests")
class DestructiveBehaviorTest {

    /** A tool that overrides nothing beyond execute(), i.e. the state every existing tool is in. */
    private Tool undeclaredTool() {
        return new AbstractTool("Undeclared", "declares nothing", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }
        };
    }

    private Tool additiveTool() {
        return new AbstractTool("Additive", "writes, but only adds", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }

            @Override
            public DestructiveBehavior getDestructiveBehavior() {
                return DestructiveBehavior.NON_DESTRUCTIVE;
            }
        };
    }

    @Test
    @DisplayName("an unaudited tool defaults to DESTRUCTIVE, so nothing is exempted by being unexamined")
    void defaultIsDestructive() {
        assertThat(undeclaredTool().getDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
    }

    @Test
    @DisplayName("a tool opts out by declaring, and the two axes stay independent")
    void declarationIsIndependentOfTheLevel() {
        assertThat(additiveTool().getDestructiveBehavior()).isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);
        assertThat(additiveTool().getSideEffectLevel()).as("disclaiming destruction does not make a tool read-only")
                .isEqualTo(SideEffectLevel.MUTATING);
    }

    @Test
    @DisplayName("the axis has exactly two values, settled against a third 'recoverable' rung")
    void twoValues() {
        assertThat(DestructiveBehavior.values()).containsExactly(DestructiveBehavior.NON_DESTRUCTIVE,
                DestructiveBehavior.DESTRUCTIVE);
    }
}
