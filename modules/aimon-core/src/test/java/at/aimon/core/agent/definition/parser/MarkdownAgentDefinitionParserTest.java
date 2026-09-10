package at.aimon.core.agent.definition.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.definition.AgentDefinition;
import at.aimon.core.agent.definition.exception.AgentDefinitionParseException;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.ReasoningEffort;

@DisplayName("MarkdownAgentDefinitionParser Tests")
class MarkdownAgentDefinitionParserTest {

    private final MarkdownAgentDefinitionParser parser = new MarkdownAgentDefinitionParser();

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("tags parsing")
    class TagsParsing {

        @Test
        @DisplayName("Should parse YAML list form and preserve insertion order")
        void shouldParseListForm() {
            final String content = """
                    ---
                    name: test
                    tags:
                      - coding
                      - java
                      - backend
                    ---
                    body""";

            final AgentDefinition definition = parser.parse(stream(content));

            assertThat(definition.getTags()).containsExactly("coding", "java", "backend");
        }

        @Test
        @DisplayName("Should parse YAML inline list form")
        void shouldParseInlineListForm() {
            final String content = """
                    ---
                    name: test
                    tags: [a, b, c]
                    ---
                    body""";

            final AgentDefinition definition = parser.parse(stream(content));

            assertThat(definition.getTags()).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("Should return empty set when tags key is missing")
        void shouldReturnEmptyWhenMissing() {
            final String content = """
                    ---
                    name: test
                    ---
                    body""";

            final AgentDefinition definition = parser.parse(stream(content));

            assertThat(definition.getTags()).isEmpty();
        }

        @Test
        @DisplayName("Should return empty set when tags is an empty list")
        void shouldReturnEmptyWhenListEmpty() {
            final String content = """
                    ---
                    name: test
                    tags: []
                    ---
                    body""";

            final AgentDefinition definition = parser.parse(stream(content));

            assertThat(definition.getTags()).isEmpty();
        }

        @Test
        @DisplayName("Should trim whitespace and drop blank elements")
        void shouldTrimAndDropBlank() {
            final String content = """
                    ---
                    name: test
                    tags:
                      - "  coding  "
                      - ""
                      - java
                    ---
                    body""";

            final AgentDefinition definition = parser.parse(stream(content));

            assertThat(definition.getTags()).containsExactly("coding", "java");
        }

        @Test
        @DisplayName("Should deduplicate while preserving first occurrence")
        void shouldDeduplicate() {
            final String content = """
                    ---
                    name: test
                    tags:
                      - java
                      - coding
                      - java
                    ---
                    body""";

            final AgentDefinition definition = parser.parse(stream(content));

            assertThat(definition.getTags()).containsExactly("java", "coding");
        }

        @Test
        @DisplayName("Should throw when tags is a scalar instead of list")
        void shouldThrowOnScalar() {
            final String content = """
                    ---
                    name: test
                    tags: coding
                    ---
                    body""";

            assertThatThrownBy(() -> parser.parse(stream(content))).isInstanceOf(AgentDefinitionParseException.class)
                    .hasMessageContaining("tags");
        }
    }

    @Nested
    @DisplayName("model.reasoningEffort")
    class ReasoningEffortParsing {

        @Test
        @DisplayName("Should bind the rung onto the neutral enum")
        void shouldBindTheRung() {
            final AgentDefinition definition = parser.parse(stream(definitionWithEffort("high")));

            assertThat(definition.getModel().getReasoningEffort()).contains(ReasoningEffort.HIGH);
        }

        @Test
        @DisplayName("Should accept any casing, as the yaml surface does for the same enum")
        void shouldFoldCase() {
            for (String written : new String[]{"high", "HIGH", "High", " high "}) {
                assertThat(parser.parse(stream(definitionWithEffort(written))).getModel().getReasoningEffort())
                        .as("written as %s", written).contains(ReasoningEffort.HIGH);
            }
        }

        @Test
        @DisplayName("Should throw naming the key and every accepted spelling when the rung is unknown")
        void shouldThrowOnAnUnknownRung() {
            // Loudly, unlike the extractInt / extractDouble neighbours, which substitute their default in silence.
            // A rung nobody recognises would otherwise reach the provider as "no effort configured" and the
            // operator would read that absence as their setting being honoured.
            assertThatThrownBy(() -> parser.parse(stream(definitionWithEffort("mediumish"))))
                    .isInstanceOf(AgentDefinitionParseException.class).hasMessageContaining("model.reasoningEffort")
                    .hasMessageContaining("mediumish").hasMessageContaining("none").hasMessageContaining("minimal")
                    .hasMessageContaining("low").hasMessageContaining("medium").hasMessageContaining("high");
        }

        @Test
        @DisplayName("A definition without the key produces the model it produced before the key existed")
        void anAbsentKeyChangesNothing() {
            final String content = """
                    ---
                    name: test
                    model:
                      name: gpt-5.1
                    ---
                    body""";

            assertThat(parser.parse(stream(content)).getModel()).isEqualTo(LlmModel.builder().name("gpt-5.1").build());
        }

        private String definitionWithEffort(String written) {
            return """
                    ---
                    name: test
                    model:
                      name: gpt-5.1
                      reasoningEffort: "%s"
                    ---
                    body""".formatted(written);
        }
    }
}
