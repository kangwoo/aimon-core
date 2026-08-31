package at.aimon.core.llms.openai;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;
import at.aimon.core.llms.openai.exception.MessageConversionException;
import at.aimon.core.llms.openai.exception.ToolConversionException;

/**
 * Converts between aimon Message format and OpenAI API format.
 *
 * <p>
 * Handles role conversion, particularly:
 *
 * <ul>
 * <li>Role.USER → ChatCompletionUserMessageParam (text or multipart content)
 * <li>Role.ASSISTANT → ChatCompletionAssistantMessageParam
 * <li>Role.TOOL → ChatCompletionToolMessageParam (OpenAI tool result)
 * </ul>
 *
 * <p>
 * Thread-safe and stateless.
 *
 * <p>
 * Note: This implementation uses ChatCompletionTool for tool calling, which is the modern OpenAI API approach.
 */
public class OpenAIMessageConverter {
    private static final Logger log = LoggerFactory.getLogger(OpenAIMessageConverter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Converts aimon messages to OpenAI chat messages.
     *
     * @param messages
     *            The aimon messages (must not be null)
     * @return List of OpenAI chat messages
     * @throws NullPointerException
     *             if messages is null
     */
    public List<ChatCompletionMessageParam> convertMessages(List<Message> messages) {
        Objects.requireNonNull(messages, "Messages cannot be null");

        final List<ChatCompletionMessageParam> chatMessages = new ArrayList<>();
        for (Message message : messages) {
            chatMessages.addAll(convertMessage(message));
        }
        return chatMessages;
    }

    /**
     * Converts a single aimon message to OpenAI chat message(s).
     *
     * @param message
     *            The aimon message
     * @return List of OpenAI chat messages
     * @throws NullPointerException
     *             if message is null
     * @throws IllegalArgumentException
     *             if role is not supported
     */
    private List<ChatCompletionMessageParam> convertMessage(Message message) {
        Objects.requireNonNull(message, "Message cannot be null");

        final List<ChatCompletionMessageParam> result = new ArrayList<>();

        if (message.getRole() == Role.TOOL) {
            // Convert tool results to tool messages
            for (ToolUseResult toolUseResult : message.getToolUseResults()) {
                final ChatCompletionToolMessageParam toolMessage = ChatCompletionToolMessageParam.builder()
                        .toolCallId(toolUseResult.getToolUseId()).content(formatToolResult(toolUseResult)).build();
                result.add(ChatCompletionMessageParam.ofTool(toolMessage));
            }
        } else if (message.getRole() == Role.USER) {
            result.add(convertUserMessage(message));
        } else if (message.getRole() == Role.ASSISTANT) {
            // Convert ASSISTANT message
            final ChatCompletionAssistantMessageParam.Builder assistantBuilder = ChatCompletionAssistantMessageParam
                    .builder().content(message.getContent());

            // Add tool calls if present
            if (message.hasToolUses()) {
                final List<JsonValue> toolCalls = convertToolUsesToToolCalls(message.getToolUses());
                assistantBuilder.putAdditionalProperty("tool_calls", JsonValue.from(toolCalls));
            }

            result.add(ChatCompletionMessageParam.ofAssistant(assistantBuilder.build()));
        } else {
            throw new IllegalArgumentException("Unsupported role: " + message.getRole());
        }

        return result;
    }

    /**
     * Converts a user message, handling both text-only and multimodal cases.
     *
     * @param message
     *            The user message
     * @return OpenAI user message param
     */
    private ChatCompletionMessageParam convertUserMessage(Message message) {
        if (!message.hasNonTextContentBlocks()) {
            final ChatCompletionUserMessageParam userMessage = ChatCompletionUserMessageParam.builder()
                    .content(message.getContent()).build();
            return ChatCompletionMessageParam.ofUser(userMessage);
        }

        final List<ChatCompletionContentPart> parts = new ArrayList<>();
        for (ContentBlock block : message.getContentBlocks()) {
            parts.add(toOpenAIPart(block));
        }

        final ChatCompletionUserMessageParam userMessage = ChatCompletionUserMessageParam.builder()
                .contentOfArrayOfContentParts(parts).build();
        return ChatCompletionMessageParam.ofUser(userMessage);
    }

    /**
     * Converts a ContentBlock to an OpenAI ChatCompletionContentPart.
     *
     * @param block
     *            The content block
     * @return OpenAI content part
     * @throws MessageConversionException
     *             if the block type is not supported
     */
    private ChatCompletionContentPart toOpenAIPart(ContentBlock block) {
        if (block instanceof TextContentBlock textBlock) {
            return ChatCompletionContentPart
                    .ofText(ChatCompletionContentPartText.builder().text(textBlock.getText()).build());
        } else if (block instanceof ImageContentBlock imageBlock) {
            return convertImageBlock(imageBlock);
        } else if (block instanceof DocumentContentBlock documentBlock) {
            if (documentBlock.isTextBased()) {
                final StringBuilder sb = new StringBuilder();
                if (documentBlock.getFileName() != null) {
                    sb.append("[File: ").append(documentBlock.getFileName()).append(" (")
                            .append(documentBlock.getMimeType()).append(")]\n");
                }
                sb.append(new String(documentBlock.getData(), StandardCharsets.UTF_8));
                return ChatCompletionContentPart
                        .ofText(ChatCompletionContentPartText.builder().text(sb.toString()).build());
            }
            throw new MessageConversionException("OpenAI does not support document content blocks natively. "
                    + "Consider extracting text from the document before sending.");
        } else {
            throw new MessageConversionException("Unsupported content block type: " + block.getClass().getName());
        }
    }

    /**
     * Converts an ImageContentBlock to an OpenAI image content part.
     *
     * @param imageBlock
     *            The image content block
     * @return OpenAI image content part
     */
    private ChatCompletionContentPart convertImageBlock(ImageContentBlock imageBlock) {
        final String imageUrl;
        if (imageBlock.getSource() == ImageContentBlock.Source.BASE64) {
            imageUrl = "data:" + imageBlock.getMimeType() + ";base64,"
                    + Base64.getEncoder().encodeToString(imageBlock.getData());
        } else {
            imageUrl = imageBlock.getUrl();
        }

        final ChatCompletionContentPartImage imagePart = ChatCompletionContentPartImage.builder()
                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(imageUrl).build()).build();
        return ChatCompletionContentPart.ofImageUrl(imagePart);
    }

    /**
     * Converts ToolUse objects to OpenAI tool_calls format.
     *
     * @param toolUses
     *            The list of tool uses
     * @return List of tool calls in OpenAI format
     */
    private List<JsonValue> convertToolUsesToToolCalls(List<ToolUse> toolUses) {
        final List<JsonValue> toolCalls = new ArrayList<>();

        for (ToolUse toolUse : toolUses) {
            try {
                // Convert tool use input to JSON string
                final String argumentsJson = OBJECT_MAPPER.writeValueAsString(toolUse.getInput());

                // Create tool call structure
                final Map<String, Object> toolCall = new HashMap<>();
                toolCall.put("id", toolUse.getId());
                toolCall.put("type", "function");

                final Map<String, Object> function = new HashMap<>();
                function.put("name", toolUse.getName());
                function.put("arguments", argumentsJson);
                toolCall.put("function", function);

                toolCalls.add(JsonValue.from(toolCall));
            } catch (JsonProcessingException e) {
                log.error("Failed to convert tool use to JSON: {}", toolUse.getName(), e);
                throw new MessageConversionException("Failed to convert tool use to JSON: " + toolUse.getName(), e);
            }
        }

        return toolCalls;
    }

    /**
     * Converts aimon tool definitions to OpenAI chat completion tools.
     *
     * @param toolDefinitions
     *            The aimon tool definitions
     * @return List of OpenAI chat tools
     */
    public List<ChatCompletionTool> convertTools(List<ToolDefinition> toolDefinitions) {
        Objects.requireNonNull(toolDefinitions, "Tool definitions cannot be null");

        final List<ChatCompletionTool> tools = new ArrayList<>();
        for (ToolDefinition tool : toolDefinitions) {
            try {
                // Convert tool definition to OpenAI function tool format

                // Then parse it back to ensure proper structure
                final Map<String, Object> schemaMap = tool.getInputSchema();

                // Now add each top-level property (type, properties, required, etc.) to FunctionParameters
                final FunctionParameters.Builder paramsBuilder = FunctionParameters.builder();
                for (Map.Entry<String, Object> entry : schemaMap.entrySet()) {
                    // Directly pass the object value to JsonValue
                    paramsBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
                }

                final FunctionDefinition functionDef = FunctionDefinition.builder().name(tool.getName())
                        .description(tool.getDescription()).parameters(paramsBuilder.build()).build();

                final ChatCompletionFunctionTool functionTool = ChatCompletionFunctionTool.builder()
                        .function(functionDef).build();

                // ChatCompletionTool is a union type, use ofFunction() method
                final ChatCompletionTool chatTool = ChatCompletionTool.ofFunction(functionTool);

                tools.add(chatTool);
            } catch (Exception e) {
                log.error("Failed to convert tool definition: {}", tool.getName(), e);
                throw new ToolConversionException("Failed to convert tool definition: " + tool.getName(), e);
            }
        }
        return tools;
    }

    /**
     * Formats tool result content.
     *
     * @param toolUseResult
     *            The tool result
     * @return Formatted content string
     */
    private static String formatToolResult(ToolUseResult toolUseResult) {
        if (toolUseResult.isError()) {
            return String.format("Error: %s", toolUseResult.getContent());
        }
        return toolUseResult.getContent();
    }

    /**
     * Parses JSON string to Map.
     *
     * <p>
     * Converts OpenAI's JSON string to a Map for tool arguments.
     *
     * @param jsonString
     *            The JSON string
     * @return Map representation
     */
    public Map<String, Object> parseJsonToMap(String jsonString) {
        if (jsonString == null || jsonString.isEmpty() || "{}".equals(jsonString)) {
            return Collections.emptyMap();
        }

        try {
            final TypeReference<Map<String, Object>> typeRef = new TypeReference<>() {
            };
            final Map<String, Object> map = OBJECT_MAPPER.readValue(jsonString, typeRef);
            return map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON: {}", jsonString, e);
            throw new MessageConversionException("Failed to parse JSON: " + jsonString, e);
        }
    }
}
