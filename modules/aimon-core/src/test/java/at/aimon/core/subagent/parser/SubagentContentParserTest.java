package at.aimon.core.subagent.parser;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SubagentContentParserTest {

    @Test
    void parse_ValidYamlFrontmatter_ParsesCorrectly() {
        // Arrange
        String content = """
                ---
                description: Expert code reviewer
                when-to-use: When you need code review or quality analysis
                allowed-tools: Read, Grep, Glob
                model: sonnet
                max-iterations: 50
                ---

                You are an expert code reviewer with deep knowledge of software engineering principles.
                """;
        SubagentContentParser parser = new SubagentContentParser();

        // Act
        SubagentContentResult result = parser.parse(content);

        // Assert
        assertThat(result.getDescription()).isEqualTo("Expert code reviewer");
        assertThat(result.getWhenToUse()).isEqualTo("When you need code review or quality analysis");
        assertThat(result.getTools()).containsExactly("Read", "Grep", "Glob");
        assertThat(result.getModel()).isEqualTo("sonnet");
        assertThat(result.getMaxIterations()).isEqualTo(50);
        assertThat(result.getSystemPrompt()).contains("You are an expert code reviewer");
    }

    @Test
    void parse_NoFrontmatter_TreatsEntireContentAsSystemPrompt() {
        // Arrange
        String content = "You are a helpful assistant.";
        SubagentContentParser parser = new SubagentContentParser();

        // Act
        SubagentContentResult result = parser.parse(content);

        // Assert
        assertThat(result.getDescription()).isNull();
        assertThat(result.getWhenToUse()).isNull();
        assertThat(result.getTools()).isEmpty();
        assertThat(result.getModel()).isNull();
        assertThat(result.getMaxIterations()).isNull();
        assertThat(result.getSystemPrompt()).isEqualTo("You are a helpful assistant.");
    }

    @Test
    void parse_NoMaxIterations_ReturnsNull() {
        // Arrange
        String content = """
                ---
                description: Test
                allowed-tools: Read
                ---

                System prompt here.
                """;
        SubagentContentParser parser = new SubagentContentParser();

        // Act
        SubagentContentResult result = parser.parse(content);

        // Assert — absent max-iterations yields null so the metadata default applies downstream
        assertThat(result.getMaxIterations()).isNull();
    }

    @Test
    void parse_NonIntegerMaxIterations_ThrowsSubagentParseException() {
        // Arrange
        String content = """
                ---
                description: Test
                max-iterations: fifty
                ---

                System prompt here.
                """;
        SubagentContentParser parser = new SubagentContentParser();

        // Act & Assert
        assertThatThrownBy(() -> parser.parse(content)).isInstanceOf(SubagentParseException.class)
                .hasMessageContaining("max-iterations");
    }

    @Test
    void parse_NonPositiveMaxIterations_ThrowsSubagentParseException() {
        // Arrange
        String content = """
                ---
                description: Test
                max-iterations: 0
                ---

                System prompt here.
                """;
        SubagentContentParser parser = new SubagentContentParser();

        // Act & Assert
        assertThatThrownBy(() -> parser.parse(content)).isInstanceOf(SubagentParseException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void parse_ToolsAsList_ParsesCorrectly() {
        // Arrange
        String content = """
                ---
                description: Test
                allowed-tools:
                  - Read
                  - Grep
                  - Bash
                model: haiku
                ---

                System prompt here.
                """;
        SubagentContentParser parser = new SubagentContentParser();

        // Act
        SubagentContentResult result = parser.parse(content);

        // Assert
        assertThat(result.getTools()).containsExactly("Read", "Grep", "Bash");
    }

    @Test
    void parse_EmptyFrontmatter_TreatsAsNoFrontmatter() {
        // Arrange
        String content = """
                ---
                ---
                System prompt here.
                """;
        SubagentContentParser parser = new SubagentContentParser();

        // Act
        SubagentContentResult result = parser.parse(content);

        // Assert
        // When frontmatter is empty, the parser treats it as no frontmatter
        // and returns the entire content as system prompt
        assertThat(result.getSystemPrompt()).contains("---");
    }

    @Test
    void parse_NullContent_ThrowsNullPointerException() {
        // Arrange
        SubagentContentParser parser = new SubagentContentParser();

        // Act & Assert
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Content cannot be null");
    }

    @Test
    void parse_InvalidYaml_ThrowsSubagentParseException() {
        // Arrange
        String content = """
                ---
                invalid: yaml: content: [
                ---

                System prompt.
                """;
        SubagentContentParser parser = new SubagentContentParser();

        // Act & Assert
        assertThatThrownBy(() -> parser.parse(content)).isInstanceOf(SubagentParseException.class)
                .hasMessageContaining("Failed to parse YAML frontmatter");
    }
}
