package at.aimon.core.skill.hook.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("McpToolAction")
class McpToolActionTest {

    @Test
    @DisplayName("builds with required fields and default timeout")
    void buildsWithDefaults() {
        final McpToolAction action = McpToolAction.builder().serverName("github").toolName("create_issue").build();

        assertThat(action.getServerName()).isEqualTo("github");
        assertThat(action.getToolName()).isEqualTo("create_issue");
        assertThat(action.getArgsTemplate()).isEmpty();
        assertThat(action.getTimeout()).isEqualTo(McpToolAction.DEFAULT_TIMEOUT);
    }

    @Test
    @DisplayName("captures args template and custom timeout")
    void capturesArgsAndTimeout() {
        final McpToolAction action = McpToolAction.builder().serverName("slack").toolName("post_message")
                .argsTemplate(Map.of("channel", "#alerts", "text", "${tool_input.message}"))
                .timeout(Duration.ofSeconds(2)).build();

        assertThat(action.getArgsTemplate()).containsEntry("channel", "#alerts");
        assertThat(action.getArgsTemplate()).containsEntry("text", "${tool_input.message}");
        assertThat(action.getTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("rejects blank serverName / toolName")
    void rejectsBlankIdentifiers() {
        assertThatThrownBy(() -> McpToolAction.builder().serverName(" ").toolName("x").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpToolAction.builder().serverName("x").toolName(" ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
