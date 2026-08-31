package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUnion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;
import at.aimon.core.llms.anthropic.exception.MessageConversionException;

@DisplayName("AnthropicMessageConverter - Message and Tool Conversion Tests")
class AnthropicMessageConverterTest {

    private AnthropicMessageConverter converter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        converter = new AnthropicMessageConverter();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Should convert user message to Anthropic format")
    void shouldConvertUserMessage() throws Exception {
        // Given: A user message
        Message userMessage = Message.user("Hello, Claude!");

        // When: Converting to Anthropic format
        List<MessageParam> params = converter.convertMessages(List.of(userMessage));

        // Then: Should produce a USER role message
        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        assertThat(messageMap.get("role")).isEqualTo("user");
        assertThat(messageMap.get("content")).isEqualTo("Hello, Claude!");
    }

    @Test
    @DisplayName("Should convert simple assistant message to Anthropic format")
    void shouldConvertSimpleAssistantMessage() throws Exception {
        // Given: A simple assistant message without tool uses
        Message assistantMessage = Message.assistant("Hello! How can I help you?");

        // When: Converting to Anthropic format
        List<MessageParam> params = converter.convertMessages(List.of(assistantMessage));

        // Then: Should produce an ASSISTANT role message with string content
        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        assertThat(messageMap.get("role")).isEqualTo("assistant");
        assertThat(messageMap.get("content")).isEqualTo("Hello! How can I help you?");
    }

    @Test
    @DisplayName("Should convert assistant message with tool uses to Anthropic format")
    void shouldConvertAssistantMessageWithToolUses() throws Exception {
        // Given: An assistant message with tool uses
        List<ToolUse> toolUses = List.of(ToolUse.of("call_123", "get_weather", Map.of("location", "Boston")));

        Message assistantMessage = Message.assistant("Let me check the weather.", toolUses);

        // When: Converting to Anthropic format
        List<MessageParam> params = converter.convertMessages(List.of(assistantMessage));

        // Then: Should produce an ASSISTANT role message with content blocks
        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        assertThat(messageMap.get("role")).isEqualTo("assistant");

        // Content should be a list of content blocks
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        assertThat(contentBlocks).isNotNull();
        assertThat(contentBlocks.size()).isGreaterThanOrEqualTo(1);

        // Should contain at least a tool_use block
        boolean hasToolUse = contentBlocks.stream().anyMatch(block -> "tool_use".equals(block.get("type")));
        assertThat(hasToolUse).isTrue();
    }

    @Test
    @DisplayName("Should convert tool results to USER message with tool_result blocks")
    void shouldConvertToolResultsToUserMessage() throws Exception {
        // Given: Tool results
        List<ToolUseResult> results = List.of(ToolUseResult.success("call_123", "Sunny, 25°C"));

        Message toolMessage = Message.toolUseResults(results);

        // When: Converting to Anthropic format
        List<MessageParam> params = converter.convertMessages(List.of(toolMessage));

        // Then: Should produce a USER role message (Anthropic-specific)
        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        // Anthropic requires tool results as USER messages
        assertThat(messageMap.get("role")).isEqualTo("user");

        // Content should be a list with tool_result blocks
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        assertThat(contentBlocks).hasSize(1);
        assertThat(contentBlocks.get(0).get("type")).isEqualTo("tool_result");
        assertThat(contentBlocks.get(0).get("tool_use_id")).isEqualTo("call_123");
    }

    @Test
    @DisplayName("Should set isError flag for error tool results")
    void shouldSetIsErrorFlagForErrorResults() throws Exception {
        // Given: Error tool result
        List<ToolUseResult> results = List.of(ToolUseResult.error("call_456", "Permission denied"));

        Message toolMessage = Message.toolUseResults(results);

        // When: Converting to Anthropic format
        List<MessageParam> params = converter.convertMessages(List.of(toolMessage));

        // Then: Should include isError flag
        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        assertThat(contentBlocks).hasSize(1);
        assertThat(contentBlocks.get(0).get("is_error")).isEqualTo(true);
    }

    @Test
    @DisplayName("Should convert basic tool schema")
    void shouldConvertBasicToolSchema() {
        // Given: A simple bash tool definition
        Map<String, Object> inputSchema = Map.of("type", "object", "properties",
                Map.of("command", Map.of("type", "string", "description", "The bash command to execute")), "required",
                List.of("command"));

        ToolDefinition bashTool = ToolDefinition.of("bash", "Execute a bash command", inputSchema);

        // When: Converting to Anthropic format
        List<ToolUnion> tools = converter.convertTools(List.of(bashTool));

        // Then: Should have one tool
        assertThat(tools).hasSize(1);
    }

    @Test
    @DisplayName("Should convert multiple tools")
    void shouldConvertMultipleTools() {
        // Given: Multiple tool definitions
        Map<String, Object> bashSchema = Map.of("type", "object", "properties",
                Map.of("command", Map.of("type", "string")), "required", List.of("command"));

        Map<String, Object> readSchema = Map.of("type", "object", "properties",
                Map.of("path", Map.of("type", "string")), "required", List.of("path"));

        List<ToolDefinition> toolDefinitions = List.of(ToolDefinition.of("bash", "Execute bash", bashSchema),
                ToolDefinition.of("read", "Read file", readSchema));

        // When: Converting to Anthropic format
        List<ToolUnion> tools = converter.convertTools(toolDefinitions);

        // Then: Should convert all tools
        assertThat(tools).hasSize(2);
    }

    @Test
    @DisplayName("Should convert complex tool schema with nested properties")
    void shouldConvertComplexToolSchema() {
        // Given: A tool with various property types
        Map<String, Object> inputSchema = Map.of("type", "object", "properties",
                Map.of("name", Map.of("type", "string", "description", "The name"), "count",
                        Map.of("type", "integer", "description", "The count"), "enabled",
                        Map.of("type", "boolean", "description", "Enable flag")),
                "required", List.of("name", "count"));

        ToolDefinition tool = ToolDefinition.of("complex_tool", "A tool with multiple property types", inputSchema);

        // When: Converting to Anthropic format
        List<ToolUnion> tools = converter.convertTools(List.of(tool));

        // Then: Should convert successfully
        assertThat(tools).hasSize(1);
    }

    @Test
    @DisplayName("Should convert conversation with mixed message types")
    void shouldConvertMixedConversation() throws Exception {
        // Given: A full session flow
        Message userMsg = Message.user("What is the weather?");
        Message assistantMsg = Message.assistant("Let me check.",
                List.of(ToolUse.of("call_1", "get_weather", Map.of("location", "NYC"))));
        Message toolResult = Message.toolUseResults(List.of(ToolUseResult.success("call_1", "Sunny, 20°C")));

        // When: Converting entire session
        List<MessageParam> params = converter.convertMessages(List.of(userMsg, assistantMsg, toolResult));

        // Then: Should produce three messages with correct roles
        assertThat(params).hasSize(3);

        // First: user
        String json0 = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> map0 = objectMapper.readValue(json0, new TypeReference<>() {
        });
        assertThat(map0.get("role")).isEqualTo("user");

        // Second: assistant
        String json1 = objectMapper.writeValueAsString(params.get(1));
        Map<String, Object> map1 = objectMapper.readValue(json1, new TypeReference<>() {
        });
        assertThat(map1.get("role")).isEqualTo("assistant");

        // Third: tool result as user
        String json2 = objectMapper.writeValueAsString(params.get(2));
        Map<String, Object> map2 = objectMapper.readValue(json2, new TypeReference<>() {
        });
        assertThat(map2.get("role")).isEqualTo("user");
    }

    @Test
    @DisplayName("Should handle multiple tool results in single message")
    void shouldHandleMultipleToolResults() throws Exception {
        // Given: Multiple tool results
        List<ToolUseResult> results = List.of(ToolUseResult.success("call_1", "Result 1"),
                ToolUseResult.error("call_2", "Error occurred"));

        Message toolMessage = Message.toolUseResults(results);

        // When: Converting to Anthropic format
        List<MessageParam> params = converter.convertMessages(List.of(toolMessage));

        // Then: Should produce one USER message with two tool_result blocks
        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        assertThat(contentBlocks).hasSize(2);

        // First result: success
        assertThat(contentBlocks.get(0).get("type")).isEqualTo("tool_result");
        assertThat(contentBlocks.get(0).get("tool_use_id")).isEqualTo("call_1");

        // Second result: error
        assertThat(contentBlocks.get(1).get("type")).isEqualTo("tool_result");
        assertThat(contentBlocks.get(1).get("tool_use_id")).isEqualTo("call_2");
        assertThat(contentBlocks.get(1).get("is_error")).isEqualTo(true);
    }

    @Test
    @DisplayName("Should convert text-only user message via existing path")
    void shouldConvertTextOnlyUserMessageViaExistingPath() throws Exception {
        Message userMessage = Message.user("Hello, Claude!");

        List<MessageParam> params = converter.convertMessages(List.of(userMessage));

        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        assertThat(messageMap.get("role")).isEqualTo("user");
        assertThat(messageMap.get("content")).isEqualTo("Hello, Claude!");
    }

    @Test
    @DisplayName("Should convert multimodal user message with image base64")
    void shouldConvertMultimodalUserMessageWithImageBase64() throws Exception {
        byte[] imageData = {(byte) 0x89, 0x50, 0x4E, 0x47};
        List<ContentBlock> blocks = List.of(TextContentBlock.of("What's in this image?"),
                ImageContentBlock.ofBase64(imageData, "image/png"));

        Message message = Message.user(blocks);
        List<MessageParam> params = converter.convertMessages(List.of(message));

        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        assertThat(messageMap.get("role")).isEqualTo("user");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        assertThat(contentBlocks).hasSize(2);

        assertThat(contentBlocks.get(0).get("type")).isEqualTo("text");
        assertThat(contentBlocks.get(0).get("text")).isEqualTo("What's in this image?");

        assertThat(contentBlocks.get(1).get("type")).isEqualTo("image");
    }

    @Test
    @DisplayName("Should convert multimodal user message with image URL")
    void shouldConvertMultimodalUserMessageWithImageUrl() throws Exception {
        List<ContentBlock> blocks = List.of(TextContentBlock.of("Describe this"),
                ImageContentBlock.ofUrl("https://example.com/image.png", "image/png"));

        Message message = Message.user(blocks);
        List<MessageParam> params = converter.convertMessages(List.of(message));

        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        assertThat(messageMap.get("role")).isEqualTo("user");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        assertThat(contentBlocks).hasSize(2);
        assertThat(contentBlocks.get(1).get("type")).isEqualTo("image");
    }

    @Test
    @DisplayName("Should convert multimodal user message with document")
    void shouldConvertMultimodalUserMessageWithDocument() throws Exception {
        byte[] pdfData = {0x25, 0x50, 0x44, 0x46};
        List<ContentBlock> blocks = List.of(TextContentBlock.of("Summarize this PDF"),
                DocumentContentBlock.of(pdfData, "application/pdf"));

        Message message = Message.user(blocks);
        List<MessageParam> params = converter.convertMessages(List.of(message));

        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        assertThat(contentBlocks).hasSize(2);
        assertThat(contentBlocks.get(1).get("type")).isEqualTo("document");
    }

    @Test
    @DisplayName("Should convert text document using PlainTextSource")
    void shouldConvertTextDocumentUsingPlainTextSource() throws Exception {
        byte[] textData = "Hello, world!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<ContentBlock> blocks = List.of(TextContentBlock.of("Summarize this file"),
                DocumentContentBlock.of(textData, "text/plain", "notes.txt"));

        Message message = Message.user(blocks);
        List<MessageParam> params = converter.convertMessages(List.of(message));

        assertThat(params).hasSize(1);

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        assertThat(contentBlocks).hasSize(2);

        // Second block should be a document
        Map<String, Object> docBlock = contentBlocks.get(1);
        assertThat(docBlock.get("type")).isEqualTo("document");
        assertThat(docBlock.get("title")).isEqualTo("notes.txt");

        // Source should be plain_text type with correct mediaType
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) docBlock.get("source");
        assertThat(source.get("type")).isEqualTo("text");
        assertThat(source.get("data")).isEqualTo("Hello, world!");
        assertThat(source.get("media_type")).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("Should set correct mediaType for text/markdown document")
    void shouldSetCorrectMediaTypeForMarkdownDocument() throws Exception {
        byte[] mdData = "# Title".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<ContentBlock> blocks = List.of(DocumentContentBlock.of(mdData, "text/markdown", "README.md"));

        Message message = Message.user(blocks);
        List<MessageParam> params = converter.convertMessages(List.of(message));

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) contentBlocks.get(0).get("source");
        assertThat(source.get("media_type")).isEqualTo("text/markdown");
    }

    @Test
    @DisplayName("Should set title for PDF document with file name")
    void shouldSetTitleForPdfDocumentWithFileName() throws Exception {
        byte[] pdfData = {0x25, 0x50, 0x44, 0x46};
        List<ContentBlock> blocks = List.of(DocumentContentBlock.of(pdfData, "application/pdf", "report.pdf"));

        Message message = Message.user(blocks);
        List<MessageParam> params = converter.convertMessages(List.of(message));

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        assertThat(contentBlocks.get(0).get("title")).isEqualTo("report.pdf");
    }

    @Test
    @DisplayName("Should convert text document without file name")
    void shouldConvertTextDocumentWithoutFileName() throws Exception {
        byte[] textData = "content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<ContentBlock> blocks = List.of(DocumentContentBlock.of(textData, "text/plain"));

        Message message = Message.user(blocks);
        List<MessageParam> params = converter.convertMessages(List.of(message));

        String json = objectMapper.writeValueAsString(params.get(0));
        Map<String, Object> messageMap = objectMapper.readValue(json, new TypeReference<>() {
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) messageMap.get("content");
        Map<String, Object> docBlock = contentBlocks.get(0);
        assertThat(docBlock.get("type")).isEqualTo("document");
        // title should not be present when fileName is null
        assertThat(docBlock.containsKey("title")).isFalse();
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
                .isInstanceOf(MessageConversionException.class).hasMessageContaining("Unsupported content block type");
    }
}
