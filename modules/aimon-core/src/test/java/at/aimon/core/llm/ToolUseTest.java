package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolUse Tests")
class ToolUseTest {

    @Test
    @DisplayName("Should create tools use")
    void shouldCreateToolUse() {
        Map<String, Object> input = Map.of("command", "git status");

        ToolUse toolUse = ToolUse.of("tool_123", "bash", input);

        assertThat(toolUse.getId()).isEqualTo("tool_123");
        assertThat(toolUse.getName()).isEqualTo("bash");
        assertThat(toolUse.getInput()).isEqualTo(input);
    }

    @Test
    @DisplayName("Should create defensive copy of input")
    void shouldCreateDefensiveCopy() {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("command", "test");

        ToolUse toolUse = ToolUse.of("id", "bash", input);

        // Modify original - should not affect tools use
        input.put("command", "modified");

        assertThat(toolUse.getInput().get("command")).isEqualTo("test");
    }

    @Test
    @DisplayName("Should return immutable input")
    void shouldReturnImmutableInput() {
        Map<String, Object> input = Map.of("command", "test");
        ToolUse toolUse = ToolUse.of("id", "bash", input);

        assertThatThrownBy(() -> toolUse.getInput().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should reject null id")
    void shouldRejectNullId() {
        Map<String, Object> input = Map.of("command", "test");

        assertThatThrownBy(() -> ToolUse.of(null, "bash", input)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID cannot be null");
    }

    @Test
    @DisplayName("Should reject null name")
    void shouldRejectNullName() {
        Map<String, Object> input = Map.of("command", "test");

        assertThatThrownBy(() -> ToolUse.of("id", null, input)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Name cannot be null");
    }

    @Test
    @DisplayName("Should reject null input")
    void shouldRejectNullInput() {
        assertThatThrownBy(() -> ToolUse.of("id", "bash", null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Input cannot be null");
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCode() {
        Map<String, Object> input = Map.of("command", "test");

        ToolUse use1 = ToolUse.of("id1", "bash", input);
        ToolUse use2 = ToolUse.of("id1", "bash", input);
        ToolUse use3 = ToolUse.of("id2", "bash", input);

        assertThat(use1).isEqualTo(use2);
        assertThat(use1.hashCode()).isEqualTo(use2.hashCode());

        assertThat(use1).isNotEqualTo(use3);
        assertThat(use1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        Map<String, Object> input = Map.of("command", "git status");
        ToolUse toolUse = ToolUse.of("tool_123", "bash", input);

        String toString = toolUse.toString();

        assertThat(toString).contains("ToolUse");
        assertThat(toString).contains("tool_123");
        assertThat(toString).contains("bash");
    }

    @Test
    @DisplayName("Should handle empty input")
    void shouldHandleEmptyInput() {
        Map<String, Object> input = Map.of();

        ToolUse toolUse = ToolUse.of("id", "tools", input);

        assertThat(toolUse.getInput()).isEmpty();
    }

    @Test
    @DisplayName("A null-valued entry is dropped, not thrown on")
    void shouldDropNullValuedEntries() {
        // The canonical shape: the model fills an optional parameter it has no value for with null. This used to be
        // Map.copyOf, which threw here — and this constructor runs while converting the LLM response, i.e. outside
        // the tool-execution loop's catch, so one null field failed the entire turn and the model was told nothing.
        // Dropping the entry instead is what lets the turn continue and the tool see a plain "absent" parameter.
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("file_path", "/x");
        input.put("offset", null);

        ToolUse toolUse = ToolUse.of("id", "Read", input);

        assertThat(toolUse.getInput()).containsExactly(java.util.Map.entry("file_path", "/x"));
        assertThat(toolUse.getInput()).doesNotContainKey("offset");
    }

    @Test
    @DisplayName("An all-null input collapses to empty rather than failing")
    void shouldCollapseAnAllNullInputToEmpty() {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("a", null);
        input.put("b", null);

        // Pinned separately from the mixed case because the boundary is where a "filter unless empty" shortcut would
        // hide: an empty result is a legitimate outcome, not a signal that something went wrong.
        assertThat(ToolUse.of("id", "Noop", input).getInput()).isEmpty();
    }

    @Test
    @DisplayName("Input order is preserved")
    void shouldPreserveInputOrder() {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("zulu", 1);
        input.put("alpha", 2);
        input.put("mike", 3);

        // Map.copyOf gave no ordering guarantee, so a logged parameter list could reshuffle between runs for no
        // reason. The normalizing copy is insertion-ordered; this pins that it stays that way.
        assertThat(ToolUse.of("id", "bash", input).getInput().keySet()).containsExactly("zulu", "alpha", "mike");
    }

    @Test
    @DisplayName("Should handle complex input")
    void shouldHandleComplexInput() {
        Map<String, Object> input = Map.of("command", "test", "timeout", 30, "options", Map.of("verbose", true));

        ToolUse toolUse = ToolUse.of("id", "bash", input);

        assertThat(toolUse.getInput()).hasSize(3);
        assertThat(toolUse.getInput().get("command")).isEqualTo("test");
        assertThat(toolUse.getInput().get("timeout")).isEqualTo(30);
    }
}
