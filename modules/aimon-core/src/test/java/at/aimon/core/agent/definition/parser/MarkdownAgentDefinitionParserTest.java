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
            // Loudly, as all four keys in this block now are (#74). The extra thing this one carries is the list of
            // accepted spellings: a rung nobody recognises would otherwise reach the provider as "no effort
            // configured" and the operator would read that absence as their setting being honoured.
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

    @Nested
    @DisplayName("numeric and text frontmatter")
    class NumericFrontmatter {

        private String withModelKey(String written) {
            return """
                    ---
                    name: test
                    model:
                      %s
                    ---
                    body""".formatted(written);
        }

        @Test
        @DisplayName("Should reject a temperature that is not a number rather than substituting 1.0")
        void anUnparseableTemperatureIsAnError() {
            // Issue #74's reproduction. `temperature: hot` bound 1.0 and the agent ran on a sampling parameter its
            // author did not choose, beside a key that had been throwing on the same class of input since #61.
            for (String written : new String[]{"hot", "\"\"", "1,5", "true"}) {
                assertThatThrownBy(() -> parser.parse(stream(withModelKey("temperature: " + written))))
                        .as("temperature written as `%s`", written).isInstanceOf(AgentDefinitionParseException.class)
                        .hasMessageContaining("model.temperature").hasMessageContaining("Expected a number");
            }
        }

        @Test
        @DisplayName("Should reject a topP that is not a number rather than substituting 1.0")
        void anUnparseableTopPIsAnError() {
            assertThatThrownBy(() -> parser.parse(stream(withModelKey("topP: high"))))
                    .isInstanceOf(AgentDefinitionParseException.class).hasMessageContaining("model.topP")
                    .hasMessageContaining("high");
        }

        @Test
        @DisplayName("Should reject a maxTokens that is not a number rather than substituting 4096")
        void anUnparseableMaxTokensIsAnError() {
            assertThatThrownBy(() -> parser.parse(stream(withModelKey("maxTokens: many"))))
                    .isInstanceOf(AgentDefinitionParseException.class).hasMessageContaining("model.maxTokens")
                    .hasMessageContaining("many");
        }

        @Test
        @DisplayName("Should reject a maxIterations that is not a number rather than running unbounded")
        void anUnparseableMaxIterationsIsAnError() {
            // The fourth call site, which the issue's count of three omits: the default here is Integer.MAX_VALUE,
            // so `maxIterations: fifty` did not merely pick a number the author did not choose -- it removed the
            // ceiling from the ReAct loop.
            final String content = """
                    ---
                    name: test
                    maxIterations: fifty
                    ---
                    body""";

            assertThatThrownBy(() -> parser.parse(stream(content))).isInstanceOf(AgentDefinitionParseException.class)
                    .hasMessageContaining("maxIterations").hasMessageContaining("fifty");
        }

        @Test
        @DisplayName("Should reject a whole number too large for its target rather than narrowing it")
        void anOutOfRangeWholeNumberIsAnError() {
            final String content = """
                    ---
                    name: test
                    maxIterations: 9999999999
                    ---
                    body""";

            // Silently became 1410065407 through intValue().
            assertThatThrownBy(() -> parser.parse(stream(content))).isInstanceOf(AgentDefinitionParseException.class)
                    .hasMessageContaining("maxIterations").hasMessageContaining("9999999999")
                    .hasMessageContaining("32-bit");
        }

        @Test
        @DisplayName("Should reject a real fraction on a whole-number key rather than truncating it")
        void aFractionalWholeNumberIsAnError() {
            // Silently became 4096 through intValue().
            assertThatThrownBy(() -> parser.parse(stream(withModelKey("maxTokens: 4096.5"))))
                    .isInstanceOf(AgentDefinitionParseException.class).hasMessageContaining("model.maxTokens")
                    .hasMessageContaining("4096.5");
        }

        @Test
        @DisplayName("Should reject a model name written with nothing after it rather than substituting gpt5.1")
        void aPresentButEmptyModelNameIsAnError() {
            assertThatThrownBy(() -> parser.parse(stream(withModelKey("name:"))))
                    .isInstanceOf(AgentDefinitionParseException.class).hasMessageContaining("model.name")
                    .hasMessageContaining("no value");
        }

        @Test
        @DisplayName("Should reject a version written with nothing after it rather than substituting 1.0.0")
        void aPresentButEmptyVersionIsAnError() {
            final String content = """
                    ---
                    name: test
                    version:
                    ---
                    body""";

            assertThatThrownBy(() -> parser.parse(stream(content))).isInstanceOf(AgentDefinitionParseException.class)
                    .hasMessageContaining("version").hasMessageContaining("no value");
        }

        @Test
        @DisplayName("Should keep taking the documented default when a key is simply absent")
        void anAbsentKeyStillTakesItsDefault() {
            final String content = """
                    ---
                    name: test
                    ---
                    body""";

            final AgentDefinition definition = parser.parse(stream(content));

            assertThat(definition.getMaxIterations()).isEqualTo(Integer.MAX_VALUE);
            assertThat(definition.getVersion().toString()).isEqualTo("1.0.0");
            assertThat(definition.getModel()).isEqualTo(LlmModel.builder().build());
        }

        @Test
        @DisplayName("Should keep binding the numbers that already bound")
        void wellFormedNumbersAreUnaffected() {
            final String content = """
                    ---
                    name: test
                    maxIterations: 50
                    model:
                      temperature: 0.7
                      topP: 0.9
                      maxTokens: 4096
                    ---
                    body""";

            final AgentDefinition definition = parser.parse(stream(content));

            assertThat(definition.getMaxIterations()).isEqualTo(50);
            assertThat(definition.getModel().getTemperature()).contains(0.7);
            assertThat(definition.getModel().getTopP()).contains(0.9);
            assertThat(definition.getModel().getMaxTokens()).contains(4096);
        }

        @Test
        @DisplayName("Should keep accepting the numeric forms snakeyaml already widened")
        void widenedNumericFormsStillBind() {
            // A whole-valued Double on a whole-number key is accepted, which is the answer ToolInputBinder gives on
            // the tool surface for the same question; an int written where a double is wanted still widens; and
            // `1e-3` arrives from snakeyaml as a Double already, which is why no String coercion is needed.
            final String content = """
                    ---
                    name: test
                    model:
                      maxTokens: 4096.0
                      temperature: 1
                      topP: 1e-3
                    ---
                    body""";

            final AgentDefinition definition = parser.parse(stream(content));

            assertThat(definition.getModel().getMaxTokens()).contains(4096);
            assertThat(definition.getModel().getTemperature()).contains(1.0);
            assertThat(definition.getModel().getTopP()).contains(0.001);
        }
    }
}
