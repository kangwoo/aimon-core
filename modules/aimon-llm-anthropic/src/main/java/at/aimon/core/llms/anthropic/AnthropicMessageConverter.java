package at.aimon.core.llms.anthropic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.PlainTextSource;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.UrlImageSource;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;
import at.aimon.core.llms.anthropic.exception.MessageConversionException;
import at.aimon.core.llms.anthropic.exception.ToolConversionException;

/**
 * Converts between aimon Message format and Anthropic API format.
 *
 * <p>
 * Handles role conversion with Anthropic-specific differences:
 *
 * <ul>
 * <li>Role.USER → MessageParam with role=USER, string content or content block list (multimodal)
 * <li>Role.ASSISTANT (no tools) → MessageParam with role=ASSISTANT, string content
 * <li>Role.ASSISTANT (with tools) → MessageParam with role=ASSISTANT, content block list
 * <li>Role.TOOL → MessageParam with role=USER, ToolResultBlockParam content blocks
 * </ul>
 *
 * <p>
 * Key difference from OpenAI: Anthropic requires tool results to be sent as USER messages with tool_result content
 * blocks, not as separate tool role messages.
 *
 * <p>
 * Thread-safe and stateless.
 */
public class AnthropicMessageConverter {

    private static final Logger LOG = LoggerFactory.getLogger(AnthropicMessageConverter.class);

    private static final String ERROR_PREFIX = "Error: ";

    /**
     * Converts aimon messages to Anthropic message params.
     *
     * @param messages
     *            The aimon messages (must not be null)
     * @return List of Anthropic message params
     * @throws NullPointerException
     *             if messages is null
     */
    public List<MessageParam> convertMessages(List<Message> messages) {
        Objects.requireNonNull(messages, "Messages cannot be null");

        final List<MessageParam> messageParams = new ArrayList<>();
        for (Message message : messages) {
            messageParams.add(convertMessage(message));
        }
        return messageParams;
    }

    /**
     * Converts a single aimon message to an Anthropic message param.
     *
     * @param message
     *            The aimon message
     * @return Anthropic message param
     * @throws NullPointerException
     *             if message is null
     * @throws IllegalArgumentException
     *             if role is not supported
     */
    private MessageParam convertMessage(Message message) {
        Objects.requireNonNull(message, "Message cannot be null");

        if (message.getRole() == Role.USER) {
            return convertUserMessage(message);
        } else if (message.getRole() == Role.ASSISTANT) {
            return convertAssistantMessage(message);
        } else if (message.getRole() == Role.TOOL) {
            return convertToolResultMessage(message);
        } else {
            throw new IllegalArgumentException("Unsupported role: " + message.getRole());
        }
    }

    /**
     * Converts a user message, handling both text-only and multimodal cases.
     *
     * @param message
     *            The user message
     * @return Anthropic message param
     */
    private MessageParam convertUserMessage(Message message) {
        if (!message.hasNonTextContentBlocks()) {
            return MessageParam.builder().role(MessageParam.Role.USER).content(message.getContent()).build();
        }

        final List<ContentBlockParam> contentBlocks = new ArrayList<>();
        for (ContentBlock block : message.getContentBlocks()) {
            contentBlocks.add(toAnthropicBlock(block));
        }

        return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(contentBlocks).build();
    }

    /**
     * Converts an assistant message, handling both simple text and tool use cases.
     *
     * @param message
     *            The assistant message
     * @return Anthropic message param
     */
    private MessageParam convertAssistantMessage(Message message) {
        if (!message.hasToolUses()) {
            return MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(message.getContent()).build();
        }

        // Assistant message with tool uses: create content block list
        final List<ContentBlockParam> contentBlocks = new ArrayList<>();

        // Add text content if non-empty
        if (!message.getContent().isEmpty()) {
            contentBlocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(message.getContent()).build()));
        }

        // Add tool use blocks
        for (ToolUse toolUse : message.getToolUses()) {
            contentBlocks.add(convertToolUseToBlockParam(toolUse));
        }

        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(contentBlocks).build();
    }

    /**
     * Converts a tool result message to an Anthropic USER message with tool_result content blocks.
     *
     * <p>
     * Anthropic requires tool results to be sent as USER messages with ToolResultBlockParam content blocks.
     *
     * @param message
     *            The tool result message
     * @return Anthropic message param with role=USER
     */
    private MessageParam convertToolResultMessage(Message message) {
        final List<ContentBlockParam> contentBlocks = new ArrayList<>();

        for (ToolUseResult toolUseResult : message.getToolUseResults()) {
            final ToolResultBlockParam.Builder resultBuilder = ToolResultBlockParam.builder()
                    .toolUseId(toolUseResult.getToolUseId()).content(formatToolResult(toolUseResult));

            if (toolUseResult.isError()) {
                resultBuilder.isError(true);
            }

            contentBlocks.add(ContentBlockParam.ofToolResult(resultBuilder.build()));
        }

        return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(contentBlocks).build();
    }

    /**
     * Converts a ContentBlock to an Anthropic ContentBlockParam.
     *
     * @param block
     *            The content block
     * @return Anthropic content block param
     * @throws MessageConversionException
     *             if the block type is not supported
     */
    private ContentBlockParam toAnthropicBlock(ContentBlock block) {
        if (block instanceof TextContentBlock textBlock) {
            return ContentBlockParam.ofText(TextBlockParam.builder().text(textBlock.getText()).build());
        } else if (block instanceof ImageContentBlock imageBlock) {
            return convertImageBlock(imageBlock);
        } else if (block instanceof DocumentContentBlock documentBlock) {
            return convertDocumentBlock(documentBlock);
        } else {
            throw new MessageConversionException("Unsupported content block type: " + block.getClass().getName());
        }
    }

    /**
     * Converts an ImageContentBlock to an Anthropic ImageBlockParam.
     *
     * @param imageBlock
     *            The image content block
     * @return Anthropic content block param
     */
    private ContentBlockParam convertImageBlock(ImageContentBlock imageBlock) {
        if (imageBlock.getSource() == ImageContentBlock.Source.BASE64) {
            final Base64ImageSource source = Base64ImageSource.builder()
                    .mediaType(toAnthropicMediaType(imageBlock.getMimeType()))
                    .data(Base64.getEncoder().encodeToString(imageBlock.getData())).build();
            return ContentBlockParam
                    .ofImage(ImageBlockParam.builder().source(ImageBlockParam.Source.ofBase64(source)).build());
        } else {
            final UrlImageSource source = UrlImageSource.builder().url(imageBlock.getUrl()).build();
            return ContentBlockParam
                    .ofImage(ImageBlockParam.builder().source(ImageBlockParam.Source.ofUrl(source)).build());
        }
    }

    /**
     * Converts a DocumentContentBlock to an Anthropic DocumentBlockParam.
     *
     * <p>
     * Text-based documents use {@link PlainTextSource}, while binary documents (PDF) use {@link Base64PdfSource}.
     *
     * @param documentBlock
     *            The document content block
     * @return Anthropic content block param
     */
    private ContentBlockParam convertDocumentBlock(DocumentContentBlock documentBlock) {
        final DocumentBlockParam.Builder docBuilder;

        if (documentBlock.isTextBased()) {
            final PlainTextSource source = PlainTextSource.builder()
                    .data(new String(documentBlock.getData(), StandardCharsets.UTF_8))
                    .mediaType(JsonValue.from(documentBlock.getMimeType())).build();
            docBuilder = DocumentBlockParam.builder().source(DocumentBlockParam.Source.ofText(source));
        } else {
            final Base64PdfSource source = Base64PdfSource.builder()
                    .data(Base64.getEncoder().encodeToString(documentBlock.getData())).build();
            docBuilder = DocumentBlockParam.builder().source(DocumentBlockParam.Source.ofBase64(source));
        }

        if (documentBlock.getFileName() != null) {
            docBuilder.title(documentBlock.getFileName());
        }

        return ContentBlockParam.ofDocument(docBuilder.build());
    }

    /**
     * Converts a MIME type string to Anthropic's MediaType enum.
     *
     * @param mimeType
     *            The MIME type string
     * @return The Anthropic MediaType
     */
    private Base64ImageSource.MediaType toAnthropicMediaType(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/jpeg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> throw new MessageConversionException("Unsupported image media type: " + mimeType);
        };
    }

    /**
     * Converts a ToolUse to a ContentBlockParam for inclusion in assistant messages.
     *
     * @param toolUse
     *            The tool use
     * @return ContentBlockParam wrapping a ToolUseBlockParam
     */
    private ContentBlockParam convertToolUseToBlockParam(ToolUse toolUse) {
        try {
            final JsonValue inputJson = JsonValue.from(toolUse.getInput());

            final ToolUseBlockParam toolUseBlock = ToolUseBlockParam.builder().id(toolUse.getId())
                    .name(toolUse.getName()).input(inputJson).build();

            return ContentBlockParam.ofToolUse(toolUseBlock);
        } catch (Exception e) {
            LOG.error("Failed to convert tool use to block param: {}", toolUse.getName(), e);
            throw new MessageConversionException("Failed to convert tool use to block param: " + toolUse.getName(), e);
        }
    }

    /**
     * Converts aimon tool definitions to Anthropic tool unions.
     *
     * @param toolDefinitions
     *            The aimon tool definitions
     * @return List of Anthropic tool unions
     */
    public List<ToolUnion> convertTools(List<ToolDefinition> toolDefinitions) {
        Objects.requireNonNull(toolDefinitions, "Tool definitions cannot be null");

        final List<ToolUnion> tools = new ArrayList<>();
        for (ToolDefinition toolDef : toolDefinitions) {
            try {
                final Tool.InputSchema inputSchema = buildInputSchema(toolDef.getInputSchema());

                final Tool tool = Tool.builder().name(toolDef.getName()).description(toolDef.getDescription())
                        .inputSchema(inputSchema).build();

                tools.add(ToolUnion.ofTool(tool));
            } catch (Exception e) {
                LOG.error("Failed to convert tool definition: {}", toolDef.getName(), e);
                throw new ToolConversionException("Failed to convert tool definition: " + toolDef.getName(), e);
            }
        }
        return tools;
    }

    /**
     * Builds an Anthropic InputSchema from a JSON schema map.
     *
     * @param schemaMap
     *            The JSON schema map
     * @return Anthropic InputSchema
     */
    private Tool.InputSchema buildInputSchema(Map<String, Object> schemaMap) {
        final Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder();

        // Set properties if present
        Object properties = schemaMap.get("properties");
        if (properties instanceof Map) {
            schemaBuilder.properties(JsonValue.from(properties));
        }

        // Copy additional schema fields (required, additionalProperties, description, etc.)
        for (Map.Entry<String, Object> entry : schemaMap.entrySet()) {
            String key = entry.getKey();
            if (!"type".equals(key) && !"properties".equals(key)) {
                schemaBuilder.putAdditionalProperty(key, JsonValue.from(entry.getValue()));
            }
        }

        return schemaBuilder.build();
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
            return ERROR_PREFIX + toolUseResult.getContent();
        }
        return toolUseResult.getContent();
    }

}
