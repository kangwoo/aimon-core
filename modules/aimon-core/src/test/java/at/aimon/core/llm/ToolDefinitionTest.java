package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolDefinition Tests")
class ToolDefinitionTest {

    @Test
    @DisplayName("Should create tools definition")
    void shouldCreateToolDefinition() {
        Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("command", Map.of("type", "string")));

        ToolDefinition tool = ToolDefinition.of("bash", "Execute bash command", schema);

        assertThat(tool.getName()).isEqualTo("bash");
        assertThat(tool.getDescription()).isEqualTo("Execute bash command");
        assertThat(tool.getInputSchema()).isEqualTo(schema);
    }

    @Test
    @DisplayName("Should create defensive copy of schema")
    void shouldCreateDefensiveCopy() {
        Map<String, Object> schema = new java.util.HashMap<>();
        schema.put("type", "object");

        ToolDefinition tool = ToolDefinition.of("test", "Test", schema);

        // Modify original - should not affect tools
        schema.put("type", "modified");

        assertThat(tool.getInputSchema().get("type")).isEqualTo("object");
    }

    @Test
    @DisplayName("Should return immutable schema")
    void shouldReturnImmutableSchema() {
        Map<String, Object> schema = Map.of("type", "object");
        ToolDefinition tool = ToolDefinition.of("test", "Test", schema);

        assertThatThrownBy(() -> tool.getInputSchema().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should reject null name")
    void shouldRejectNullName() {
        Map<String, Object> schema = Map.of("type", "object");

        assertThatThrownBy(() -> ToolDefinition.of(null, "Description", schema))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Name cannot be null");
    }

    @Test
    @DisplayName("Should reject null description")
    void shouldRejectNullDescription() {
        Map<String, Object> schema = Map.of("type", "object");

        assertThatThrownBy(() -> ToolDefinition.of("name", null, schema)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Description cannot be null");
    }

    @Test
    @DisplayName("Should reject null schema")
    void shouldRejectNullSchema() {
        assertThatThrownBy(() -> ToolDefinition.of("name", "Description", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Input schema cannot be null");
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCode() {
        Map<String, Object> schema = Map.of("type", "object");

        ToolDefinition tool1 = ToolDefinition.of("bash", "Execute", schema);
        ToolDefinition tool2 = ToolDefinition.of("bash", "Execute", schema);
        ToolDefinition tool3 = ToolDefinition.of("read", "Execute", schema);

        assertThat(tool1).isEqualTo(tool2);
        assertThat(tool1.hashCode()).isEqualTo(tool2.hashCode());

        assertThat(tool1).isNotEqualTo(tool3);
        assertThat(tool1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        Map<String, Object> schema = Map.of("type", "object");
        ToolDefinition tool = ToolDefinition.of("bash", "Execute bash", schema);

        String toString = tool.toString();

        assertThat(toString).contains("ToolDefinition");
        assertThat(toString).contains("bash");
        assertThat(toString).contains("Execute bash");
    }

    @Test
    @DisplayName("Should handle complex schema")
    void shouldHandleComplexSchema() {
        Map<String, Object> schema = Map
                .of("type", "object", "properties",
                        Map.of("command", Map.of("type", "string", "description", "The command to execute"), "timeout",
                                Map.of("type", "number", "description", "Timeout in seconds")),
                        "required", List.of("command"));

        ToolDefinition tool = ToolDefinition.of("bash", "Execute command", schema);

        assertThat(tool.getInputSchema()).containsKey("properties");
        assertThat(tool.getInputSchema()).containsKey("required");
    }

    @Test
    @DisplayName("Should default category to 'general' when using 3-arg of()")
    void shouldDefaultCategoryToGeneral() {
        Map<String, Object> schema = Map.of("type", "object");

        ToolDefinition tool = ToolDefinition.of("bash", "Execute", schema);

        assertThat(tool.getCategory()).isEqualTo(ToolDefinition.DEFAULT_CATEGORY);
        assertThat(tool.getCategory()).isEqualTo("general");
    }

    @Test
    @DisplayName("Should set category when provided via 4-arg of()")
    void shouldSetCategoryFromFactory() {
        Map<String, Object> schema = Map.of("type", "object");

        ToolDefinition tool = ToolDefinition.of("bash", "Execute", "execution", schema);

        assertThat(tool.getCategory()).isEqualTo("execution");
    }

    @Test
    @DisplayName("Should reject null category")
    void shouldRejectNullCategory() {
        Map<String, Object> schema = Map.of("type", "object");

        assertThatThrownBy(() -> ToolDefinition.of("name", "Description", null, schema))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Category cannot be null");
    }

    @Test
    @DisplayName("Should distinguish equality by category")
    void shouldDistinguishEqualityByCategory() {
        Map<String, Object> schema = Map.of("type", "object");

        ToolDefinition tool1 = ToolDefinition.of("bash", "Execute", "execution", schema);
        ToolDefinition tool2 = ToolDefinition.of("bash", "Execute", "execution", schema);
        ToolDefinition tool3 = ToolDefinition.of("bash", "Execute", "general", schema);

        assertThat(tool1).isEqualTo(tool2);
        assertThat(tool1).isNotEqualTo(tool3);
    }

    @Test
    @DisplayName("Should include category in toString")
    void shouldIncludeCategoryInToString() {
        Map<String, Object> schema = Map.of("type", "object");
        ToolDefinition tool = ToolDefinition.of("bash", "Execute", "execution", schema);

        assertThat(tool.toString()).contains("execution");
    }
}
