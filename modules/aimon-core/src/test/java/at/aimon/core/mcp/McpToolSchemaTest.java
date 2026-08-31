package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpToolSchemaTest {

    @Test
    @DisplayName("of() builds schema with name, description and inputSchema")
    void buildsCorrectly() {
        Map<String, Object> input = Map.of("type", "object", "properties",
                Map.of("owner", Map.of("type", "string"), "repo", Map.of("type", "string")), "required",
                List.of("owner", "repo"));

        McpToolSchema schema = McpToolSchema.of("create_issue", "Create a GitHub issue", input);

        assertThat(schema.getName()).isEqualTo("create_issue");
        assertThat(schema.getDescription()).isEqualTo("Create a GitHub issue");
        assertThat(schema.getInputSchema()).isEqualTo(input);
    }

    @Test
    @DisplayName("Top-level inputSchema map is immutable copy")
    void inputSchemaIsImmutableCopy() {
        McpToolSchema schema = McpToolSchema.of("a", "b", Map.of("type", "object"));

        assertThat(schema.getInputSchema()).isUnmodifiable();
    }

    @Test
    @DisplayName("Null name rejected")
    void nullNameRejected() {
        assertThatNullPointerException().isThrownBy(() -> McpToolSchema.of(null, "desc", Map.of()));
    }

    @Test
    @DisplayName("Null description rejected")
    void nullDescriptionRejected() {
        assertThatNullPointerException().isThrownBy(() -> McpToolSchema.of("name", null, Map.of()));
    }

    @Test
    @DisplayName("Null inputSchema rejected")
    void nullInputSchemaRejected() {
        assertThatNullPointerException().isThrownBy(() -> McpToolSchema.of("name", "desc", null));
    }

    @Test
    @DisplayName("The three-argument of() means the server sent no annotations, not that it sent none we read")
    void threeArgOfCarriesEmptyAnnotations() {
        McpToolSchema schema = McpToolSchema.of("a", "b", Map.of("type", "object"));

        assertThat(schema.getAnnotations()).isEqualTo(McpToolAnnotations.empty());
        assertThat(schema.getAnnotations().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("of() carries the server's annotations through unchanged")
    void annotationsRoundTrip() {
        McpToolAnnotations annotations = McpToolAnnotations.builder().readOnlyHint(true).build();

        McpToolSchema schema = McpToolSchema.of("list_repos", "List repositories", Map.of("type", "object"),
                annotations);

        assertThat(schema.getAnnotations()).isSameAs(annotations);
    }

    @Test
    @DisplayName("Null annotations rejected — absence is McpToolAnnotations.empty(), not null")
    void nullAnnotationsRejected() {
        assertThatNullPointerException().isThrownBy(() -> McpToolSchema.of("name", "desc", Map.of(), null));
    }
}
