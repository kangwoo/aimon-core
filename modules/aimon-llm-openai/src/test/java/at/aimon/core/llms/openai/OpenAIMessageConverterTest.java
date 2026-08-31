package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;
import at.aimon.core.llms.openai.exception.MessageConversionException;

@DisplayName("OpenAIMessageConverter Tests")
class OpenAIMessageConverterTest {
    private OpenAIMessageConverter converter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        converter = new OpenAIMessageConverter();
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("Tool Schema Conversion")
    class ToolSchemaConversionTests {

        @Test
        @DisplayName("Should convert basic tool schema with required type field")
        void shouldConvertBasicToolSchema() throws Exception {
            // Given
            Map<String, Object> inputSchema = Map.of("type", "object", "properties",
                    Map.of("command", Map.of("type", "string", "description", "The bash command to execute")),
                    "required", List.of("command"));

            ToolDefinition bashTool = ToolDefinition.of("bash", "Execute a bash command", inputSchema);

            // When
            List<ChatCompletionTool> tools = converter.convertTools(List.of(bashTool));

            // Then
            assertThat(tools).hasSize(1);

            ChatCompletionFunctionTool functionTool = tools.get(0).function().get();
            assertThat(functionTool.function().name()).isEqualTo("bash");
            assertThat(functionTool.function().description().orElse("")).isEqualTo("Execute a bash command");

            Map<String, Object> parameters = extractParameters(functionTool);
            assertThat(parameters.get("type")).isEqualTo("object");
            assertThat(parameters).containsKey("properties");
            assertThat(parameters).containsKey("required");
        }

        @Test
        @DisplayName("Should convert tool with nested object properties")
        void shouldConvertNestedToolSchema() throws Exception {
            // Given
            Map<String, Object> inputSchema = Map.of("type", "object", "properties",
                    Map.of("config",
                            Map.of("type", "object", "properties",
                                    Map.of("timeout", Map.of("type", "number", "description", "Timeout in seconds"),
                                            "retry", Map.of("type", "boolean", "description", "Enable retry")),
                                    "required", List.of("timeout"))),
                    "required", List.of("config"));

            ToolDefinition tool = ToolDefinition.of("api_call", "Make an API call with configuration", inputSchema);

            // When
            List<ChatCompletionTool> tools = converter.convertTools(List.of(tool));

            // Then
            assertThat(tools).hasSize(1);
            Map<String, Object> parameters = extractParameters(tools.get(0).function().get());

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> configProp = (Map<String, Object>) properties.get("config");

            assertThat(configProp.get("type")).isEqualTo("object");
            assertThat(configProp).containsKey("properties");
        }

        @Test
        @DisplayName("Should convert tool with multiple properties and types")
        void shouldConvertComplexToolSchema() throws Exception {
            // Given
            Map<String, Object> inputSchema = Map
                    .of("type", "object", "properties",
                            Map.of("name", Map.of("type", "string", "description", "The name"), "count",
                                    Map.of("type", "integer", "description", "The count"), "enabled",
                                    Map.of("type", "boolean", "description", "Enable flag"), "tags", Map.of("type",
                                            "array", "items", Map.of("type", "string"), "description", "List of tags")),
                            "required", List.of("name", "count"));

            ToolDefinition tool = ToolDefinition.of("complex_tool", "A tool with multiple property types", inputSchema);

            // When
            List<ChatCompletionTool> tools = converter.convertTools(List.of(tool));

            // Then
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).function().get().function().name()).isEqualTo("complex_tool");

            Map<String, Object> parameters = extractParameters(tools.get(0).function().get());
            assertThat(parameters).containsKeys("type", "properties", "required");

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
            assertThat(properties).containsKeys("name", "count", "enabled", "tags");
        }

        @Test
        @DisplayName("Should convert multiple tools")
        void shouldConvertMultipleTools() {
            // Given
            Map<String, Object> bashSchema = Map.of("type", "object", "properties",
                    Map.of("command", Map.of("type", "string")), "required", List.of("command"));

            Map<String, Object> readSchema = Map.of("type", "object", "properties",
                    Map.of("path", Map.of("type", "string")), "required", List.of("path"));

            List<ToolDefinition> toolDefinitions = List.of(ToolDefinition.of("bash", "Execute bash", bashSchema),
                    ToolDefinition.of("read", "Read file", readSchema));

            // When
            List<ChatCompletionTool> tools = converter.convertTools(toolDefinitions);

            // Then
            assertThat(tools).hasSize(2);
            assertThat(tools.get(0).function().get().function().name()).isEqualTo("bash");
            assertThat(tools.get(1).function().get().function().name()).isEqualTo("read");
        }

        @Test
        @DisplayName("Should produce valid OpenAI function schema format")
        void shouldProduceValidOpenAIFormat() throws Exception {
            // Given
            Map<String, Object> inputSchema = Map
                    .of("type", "object", "properties",
                            Map.of("query", Map.of("type", "string", "description", "Search query"), "limit",
                                    Map.of("type", "integer", "description", "Maximum results")),
                            "required", List.of("query"));

            ToolDefinition tool = ToolDefinition.of("search", "Search for information", inputSchema);

            // When
            List<ChatCompletionTool> tools = converter.convertTools(List.of(tool));
            ChatCompletionFunctionTool functionTool = tools.get(0).function().get();

            // Then
            String toolJson = objectMapper.writeValueAsString(functionTool);
            Map<String, Object> toolMap = objectMapper.readValue(toolJson, new TypeReference<>() {
            });

            assertThat(toolMap).containsKey("function");

            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) toolMap.get("function");
            assertThat(function).containsKey("name").containsKey("description").containsKey("parameters");

            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
            assertThat(parameters).containsKey("type");
            assertThat(parameters.get("type")).isEqualTo("object");
            assertThat(parameters).containsKey("properties");
        }

        @Test
        @DisplayName("Should convert ScheduleTaskTool schema preserving all nested structures")
        void shouldConvertScheduleTaskToolSchema() throws Exception {
            // Given: Complex deeply nested schema
            Map<String, Object> scheduleSchema = Map
                    .ofEntries(Map.entry("type", "object"), Map.entry("additionalProperties", false),
                            Map.entry("properties", Map.of("type",
                                    Map.of("type", "string", "enum", List.of("cron"), "description", "Schedule type."),
                                    "cron_expression", Map.of("type", "string", "minLength", 1, "description",
                                            "Cron expression.", "examples", List.of("0 */5 * * *", "0 0 * * *")),
                                    "timezone",
                                    Map.of("type", "string", "description", "IANA timezone.", "examples",
                                            List.of("Asia/Seoul")))),
                            Map.entry("required", List.of("type", "cron_expression")));

            Map<String, Object> routineItemSchema = Map.ofEntries(Map.entry("type", "object"),
                    Map.entry("additionalProperties", false),
                    Map.entry("properties",
                            Map.of("tool", Map.of("type", "string", "minLength", 1, "description", "Tool name."),
                                    "tool_params",
                                    Map.of("type", "string", "description", "Tool parameters as JSON."))),
                    Map.entry("required", List.of("tool", "tool_params")));

            Map<String, Object> inputSchema = Map.ofEntries(Map.entry("type", "object"),
                    Map.entry("additionalProperties", false),
                    Map.entry("properties",
                            Map.of("name", Map.of("type", "string", "minLength", 1, "description", "Task name."),
                                    "schedule", scheduleSchema, "routine",
                                    Map.of("type", "array", "minItems", 1, "items", routineItemSchema))),
                    Map.entry("required", List.of("name", "schedule", "routine")));

            ToolDefinition tool = ToolDefinition.of("schedule_task", "Schedule a task", inputSchema);

            // When
            List<ChatCompletionTool> tools = converter.convertTools(List.of(tool));

            // Then
            assertThat(tools).hasSize(1);
            Map<String, Object> parameters = extractParameters(tools.get(0).function().get());

            assertThat(parameters.get("type")).isEqualTo("object");
            assertThat(parameters.get("additionalProperties")).isEqualTo(false);

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) parameters.get("properties");
            assertThat(props).containsKeys("name", "schedule", "routine");

            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) parameters.get("required");
            assertThat(required).containsExactlyInAnyOrder("name", "schedule", "routine");

            // Verify schedule nested object
            @SuppressWarnings("unchecked")
            Map<String, Object> scheduleProp = (Map<String, Object>) props.get("schedule");
            assertThat(scheduleProp.get("type")).isEqualTo("object");
            assertThat(scheduleProp.get("additionalProperties")).isEqualTo(false);

            // Verify routine array with nested items
            @SuppressWarnings("unchecked")
            Map<String, Object> routineProp = (Map<String, Object>) props.get("routine");
            assertThat(routineProp.get("type")).isEqualTo("array");
            assertThat(routineProp.get("minItems")).isEqualTo(1);
            assertThat(routineProp).containsKey("items");
        }
    }

    @Nested
    @DisplayName("User Message Conversion")
    class UserMessageConversionTests {

        @Test
        @DisplayName("Should convert text-only user message")
        void shouldConvertTextOnlyUserMessage() throws Exception {
            Message userMessage = Message.user("Hello!");

            List<ChatCompletionMessageParam> params = converter.convertMessages(List.of(userMessage));

            assertThat(params).hasSize(1);

            Map<String, Object> messageMap = serializeToMap(params.get(0));
            assertThat(messageMap.get("role")).isEqualTo("user");
            assertThat(messageMap.get("content")).isEqualTo("Hello!");
        }

        @Test
        @DisplayName("Should convert multimodal user message with image base64")
        void shouldConvertMultimodalUserMessageWithImageBase64() throws Exception {
            byte[] imageData = {(byte) 0x89, 0x50, 0x4E, 0x47};
            List<ContentBlock> blocks = List.of(TextContentBlock.of("What's in this image?"),
                    ImageContentBlock.ofBase64(imageData, "image/png"));

            Message message = Message.user(blocks);
            List<ChatCompletionMessageParam> params = converter.convertMessages(List.of(message));

            assertThat(params).hasSize(1);

            Map<String, Object> messageMap = serializeToMap(params.get(0));
            assertThat(messageMap.get("role")).isEqualTo("user");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contentParts = (List<Map<String, Object>>) messageMap.get("content");
            assertThat(contentParts).hasSize(2);
            assertThat(contentParts.get(0).get("type")).isEqualTo("text");
            assertThat(contentParts.get(0).get("text")).isEqualTo("What's in this image?");
            assertThat(contentParts.get(1).get("type")).isEqualTo("image_url");
        }

        @Test
        @DisplayName("Should convert multimodal user message with image URL")
        void shouldConvertMultimodalUserMessageWithImageUrl() throws Exception {
            List<ContentBlock> blocks = List.of(TextContentBlock.of("Describe this"),
                    ImageContentBlock.ofUrl("https://example.com/image.png", "image/png"));

            Message message = Message.user(blocks);
            List<ChatCompletionMessageParam> params = converter.convertMessages(List.of(message));

            assertThat(params).hasSize(1);

            Map<String, Object> messageMap = serializeToMap(params.get(0));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contentParts = (List<Map<String, Object>>) messageMap.get("content");
            assertThat(contentParts).hasSize(2);
            assertThat(contentParts.get(1).get("type")).isEqualTo("image_url");

            @SuppressWarnings("unchecked")
            Map<String, Object> imageUrl = (Map<String, Object>) contentParts.get(1).get("image_url");
            assertThat(imageUrl.get("url")).isEqualTo("https://example.com/image.png");
        }
    }

    @Nested
    @DisplayName("Assistant Message Conversion")
    class AssistantMessageConversionTests {

        @Test
        @DisplayName("Should convert assistant message with tool uses to OpenAI format")
        void shouldConvertAssistantMessageWithToolUses() throws Exception {
            List<ToolUse> toolUses = List.of(ToolUse.of("call_123", "get_weather", Map.of("location", "Boston")),
                    ToolUse.of("call_456", "get_time", Map.of("timezone", "EST")));

            Message assistantMessage = Message.assistant("Let me check the weather and time for you.", toolUses);

            List<ChatCompletionMessageParam> openaiMessages = converter.convertMessages(List.of(assistantMessage));

            assertThat(openaiMessages).hasSize(1);

            Map<String, Object> messageMap = serializeToMap(openaiMessages.get(0));
            assertThat(messageMap.get("role")).isEqualTo("assistant");
            assertThat(messageMap.get("content")).isEqualTo("Let me check the weather and time for you.");
            assertThat(messageMap).containsKey("tool_calls");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) messageMap.get("tool_calls");
            assertThat(toolCalls).hasSize(2);

            // Verify first tool call
            assertThat(toolCalls.get(0).get("id")).isEqualTo("call_123");
            assertThat(toolCalls.get(0).get("type")).isEqualTo("function");

            @SuppressWarnings("unchecked")
            Map<String, Object> firstFunction = (Map<String, Object>) toolCalls.get(0).get("function");
            assertThat(firstFunction.get("name")).isEqualTo("get_weather");

            String firstArgs = (String) firstFunction.get("arguments");
            Map<String, Object> firstArgsMap = objectMapper.readValue(firstArgs, new TypeReference<>() {
            });
            assertThat(firstArgsMap.get("location")).isEqualTo("Boston");
        }

        @Test
        @DisplayName("Should convert assistant message without tool uses normally")
        void shouldConvertAssistantMessageWithoutToolUses() throws Exception {
            Message assistantMessage = Message.assistant("Hello! How can I help you?");

            List<ChatCompletionMessageParam> openaiMessages = converter.convertMessages(List.of(assistantMessage));

            assertThat(openaiMessages).hasSize(1);

            Map<String, Object> messageMap = serializeToMap(openaiMessages.get(0));
            assertThat(messageMap.get("role")).isEqualTo("assistant");
            assertThat(messageMap.get("content")).isEqualTo("Hello! How can I help you?");

            Object toolCalls = messageMap.get("tool_calls");
            assertThat(toolCalls == null || (toolCalls instanceof List && ((List<?>) toolCalls).isEmpty()))
                    .as("tool_calls should be absent or empty").isTrue();
        }
    }

    @Nested
    @DisplayName("Tool Role Message Conversion")
    class ToolRoleMessageConversionTests {

        @Test
        @DisplayName("Should convert tool result message to OpenAI tool message")
        void shouldConvertToolResultMessage() throws Exception {
            List<ToolUseResult> toolResults = List
                    .of(ToolUseResult.success("call_123", "The weather in Boston is 72F"));

            Message toolMessage = Message.toolUseResults(toolResults);

            List<ChatCompletionMessageParam> openaiMessages = converter.convertMessages(List.of(toolMessage));

            assertThat(openaiMessages).hasSize(1);

            Map<String, Object> messageMap = serializeToMap(openaiMessages.get(0));
            assertThat(messageMap.get("role")).isEqualTo("tool");
            assertThat(messageMap.get("tool_call_id")).isEqualTo("call_123");
            assertThat(messageMap.get("content")).isEqualTo("The weather in Boston is 72F");
        }

        @Test
        @DisplayName("Should convert error tool result with 'Error:' prefix")
        void shouldConvertErrorToolResult() throws Exception {
            List<ToolUseResult> toolResults = List.of(ToolUseResult.error("call_456", "File not found: /tmp/missing"));

            Message toolMessage = Message.toolUseResults(toolResults);

            List<ChatCompletionMessageParam> openaiMessages = converter.convertMessages(List.of(toolMessage));

            assertThat(openaiMessages).hasSize(1);

            Map<String, Object> messageMap = serializeToMap(openaiMessages.get(0));
            assertThat(messageMap.get("role")).isEqualTo("tool");
            assertThat(messageMap.get("tool_call_id")).isEqualTo("call_456");
            assertThat((String) messageMap.get("content")).startsWith("Error:");
        }

        @Test
        @DisplayName("Should convert multiple tool results to multiple tool messages")
        void shouldConvertMultipleToolResults() throws Exception {
            List<ToolUseResult> toolResults = List.of(ToolUseResult.success("call_1", "Result 1"),
                    ToolUseResult.success("call_2", "Result 2"));

            Message toolMessage = Message.toolUseResults(toolResults);

            List<ChatCompletionMessageParam> openaiMessages = converter.convertMessages(List.of(toolMessage));

            assertThat(openaiMessages).hasSize(2);

            Map<String, Object> first = serializeToMap(openaiMessages.get(0));
            assertThat(first.get("tool_call_id")).isEqualTo("call_1");
            assertThat(first.get("content")).isEqualTo("Result 1");

            Map<String, Object> second = serializeToMap(openaiMessages.get(1));
            assertThat(second.get("tool_call_id")).isEqualTo("call_2");
            assertThat(second.get("content")).isEqualTo("Result 2");
        }
    }

    @Nested
    @DisplayName("Document Content Handling")
    class DocumentContentTests {

        @Test
        @DisplayName("Should convert text document to text content part")
        void shouldConvertTextDocumentToTextContentPart() throws Exception {
            byte[] textData = "Hello, world!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            List<ContentBlock> blocks = List.of(TextContentBlock.of("Summarize this file"),
                    DocumentContentBlock.of(textData, "text/plain", "notes.txt"));

            Message message = Message.user(blocks);
            List<ChatCompletionMessageParam> params = converter.convertMessages(List.of(message));

            assertThat(params).hasSize(1);

            Map<String, Object> messageMap = serializeToMap(params.get(0));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contentParts = (List<Map<String, Object>>) messageMap.get("content");
            assertThat(contentParts).hasSize(2);

            assertThat(contentParts.get(1).get("type")).isEqualTo("text");
            String text = (String) contentParts.get(1).get("text");
            assertThat(text).startsWith("[File: notes.txt (text/plain)]");
            assertThat(text).contains("Hello, world!");
        }

        @Test
        @DisplayName("Should convert text document without file name")
        void shouldConvertTextDocumentWithoutFileName() throws Exception {
            byte[] textData = "Hello, world!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            List<ContentBlock> blocks = List.of(DocumentContentBlock.of(textData, "text/plain"));

            Message message = Message.user(blocks);
            List<ChatCompletionMessageParam> params = converter.convertMessages(List.of(message));

            Map<String, Object> messageMap = serializeToMap(params.get(0));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contentParts = (List<Map<String, Object>>) messageMap.get("content");
            String text = (String) contentParts.get(0).get("text");
            assertThat(text).doesNotContain("[File:");
            assertThat(text).isEqualTo("Hello, world!");
        }

        @Test
        @DisplayName("Should throw for binary document content block")
        void shouldThrowForBinaryDocumentContentBlock() {
            byte[] pdfData = {0x25, 0x50, 0x44, 0x46};
            List<ContentBlock> blocks = List.of(TextContentBlock.of("Summarize this PDF"),
                    DocumentContentBlock.of(pdfData, "application/pdf"));

            Message message = Message.user(blocks);

            assertThatThrownBy(() -> converter.convertMessages(List.of(message)))
                    .isInstanceOf(MessageConversionException.class)
                    .hasMessageContaining("OpenAI does not support document content blocks");
        }

        @Test
        @DisplayName("Should throw for unknown content block type")
        void shouldThrowForUnknownContentBlockType() {
            ContentBlock unknownBlock = new ContentBlock() {
                @Override
                public String getType() {
                    return "unknown";
                }

                @Override
                public String asText() {
                    return "unknown";
                }
            };
            List<ContentBlock> blocks = List.of(TextContentBlock.of("Hello"), unknownBlock);
            Message message = Message.user(blocks);

            assertThatThrownBy(() -> converter.convertMessages(List.of(message)))
                    .isInstanceOf(MessageConversionException.class)
                    .hasMessageContaining("Unsupported content block type");
        }
    }

    @Nested
    @DisplayName("parseJsonToMap")
    class ParseJsonToMapTests {

        @Test
        @DisplayName("Should return empty map for null input")
        void shouldReturnEmptyMapForNull() {
            Map<String, Object> result = converter.parseJsonToMap(null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty map for empty string")
        void shouldReturnEmptyMapForEmptyString() {
            Map<String, Object> result = converter.parseJsonToMap("");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty map for empty JSON object")
        void shouldReturnEmptyMapForEmptyJsonObject() {
            Map<String, Object> result = converter.parseJsonToMap("{}");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should parse valid JSON to map")
        void shouldParseValidJson() {
            Map<String, Object> result = converter.parseJsonToMap("{\"key\":\"value\",\"count\":42}");

            assertThat(result).containsEntry("key", "value").containsEntry("count", 42);
        }

        @Test
        @DisplayName("Should return unmodifiable map")
        void shouldReturnUnmodifiableMap() {
            Map<String, Object> result = converter.parseJsonToMap("{\"key\":\"value\"}");

            assertThatThrownBy(() -> result.put("new", "entry")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Should throw MessageConversionException for malformed JSON")
        void shouldThrowForMalformedJson() {
            assertThatThrownBy(() -> converter.parseJsonToMap("{invalid json}"))
                    .isInstanceOf(MessageConversionException.class).hasMessageContaining("Failed to parse JSON");
        }

        @Test
        @DisplayName("Should handle nested JSON structures")
        void shouldHandleNestedJson() {
            Map<String, Object> result = converter.parseJsonToMap("{\"outer\":{\"inner\":\"value\"},\"list\":[1,2,3]}");

            assertThat(result).containsKey("outer").containsKey("list");

            @SuppressWarnings("unchecked")
            Map<String, Object> outer = (Map<String, Object>) result.get("outer");
            assertThat(outer).containsEntry("inner", "value");
        }
    }

    /**
     * Serializes an OpenAI message param to a Map for assertion.
     */
    private Map<String, Object> serializeToMap(Object obj) throws Exception {
        String json = objectMapper.writeValueAsString(obj);
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    /**
     * Extracts the parameters map from a function tool.
     */
    private Map<String, Object> extractParameters(ChatCompletionFunctionTool functionTool) throws Exception {
        String toolJson = objectMapper.writeValueAsString(functionTool);
        Map<String, Object> toolMap = objectMapper.readValue(toolJson, new TypeReference<>() {
        });

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolMap.get("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        return parameters;
    }
}
