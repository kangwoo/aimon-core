package at.aimon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;

@DisplayName("ForkAccess Tests")
class ForkAccessTest {

    @Test
    @DisplayName("an absent depth is 0, and a child is one deeper")
    void depth() {
        assertThat(ForkAccess.depthOf(ToolContext.empty())).isZero();
        assertThat(ForkAccess.childDepthOf(ToolContext.empty())).isEqualTo(1);
        final ToolContext fork = ToolContext.builder().put(ToolContextKeys.FORK_DEPTH, 2).build();
        assertThat(ForkAccess.depthOf(fork)).isEqualTo(2);
        assertThat(ForkAccess.childDepthOf(fork)).isEqualTo(3);
    }

    @Test
    @DisplayName("task id is read only when the attribute holds a string")
    void taskId() {
        assertThat(ForkAccess.taskIdOf((Map<String, Object>) null)).isEmpty();
        assertThat(ForkAccess.taskIdOf(Map.of())).isEmpty();
        assertThat(ForkAccess.taskIdOf(Map.of(ForkAccess.TASK_ID_ATTRIBUTE, 42))).isEmpty();
        assertThat(ForkAccess.taskIdOf(Map.of(ForkAccess.TASK_ID_ATTRIBUTE, "abc"))).contains("abc");

        assertThat(ForkAccess.taskIdOf(ToolContext.empty())).isEmpty();
        final ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY, Map.of(ForkAccess.TASK_ID_ATTRIBUTE, "xyz")).build();
        assertThat(ForkAccess.taskIdOf(context)).contains("xyz");
    }
}
