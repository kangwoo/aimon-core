package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionTool;

import at.aimon.core.llm.ToolDefinition;

/**
 * Validates that converted tool schemas produce valid JSON matching OpenAI's expected format.
 */
@DisplayName("Tool Schema JSON Validation")
class PrintToolSchemaTest {

    private final OpenAIMessageConverter converter = new OpenAIMessageConverter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Should produce valid JSON with function name, description, and parameters")
    void shouldProduceValidJsonStructure() throws Exception {
        // Given
        Map<String, Object> bashSchema = Map.of("type", "object", "properties",
                Map.of("command", Map.of("type", "string", "description", "The bash command to execute")), "required",
                List.of("command"));

        ToolDefinition bashTool = ToolDefinition.of("bash", "Execute a bash command", bashSchema);

        // When
        List<ChatCompletionTool> tools = converter.convertTools(List.of(bashTool));
        ChatCompletionFunctionTool functionTool = tools.get(0).function().get();

        // Then: Validate JSON structure
        String json = mapper.writeValueAsString(functionTool);
        Map<String, Object> toolMap = mapper.readValue(json, new TypeReference<>() {
        });

        assertThat(toolMap).containsKey("function");

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolMap.get("function");

        assertThat(function.get("name")).isEqualTo("bash");
        assertThat(function.get("description")).isEqualTo("Execute a bash command");
        assertThat(function).containsKey("parameters");

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        assertThat(parameters.get("type")).isEqualTo("object");
        assertThat(parameters).containsKey("properties");
        assertThat(parameters).containsKey("required");
    }
}
