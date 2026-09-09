package at.aimon.core.llms.anthropic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
import at.aimon.core.llm.ReasoningTrace;
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
 * <strong>Ordering is this class's other job.</strong> A thinking block must be replayed in the position the model
 * emitted it — leading the turn, or immediately in front of the tool use it introduced — and {@link Message} cannot
 * express that order on its own, because its text lives in content blocks and its tool uses in a separate list. So
 * {@link AnthropicOutputBlocks} captures the anchor on the way in and
 * {@link #convertAssistantMessage(Message, String, AnthropicDivergenceReporter)} replays it on the way out. The two
 * are halves of one rule and are documented together on that class.
 *
 * <p>
 * Thread-safe and stateless.
 */
public class AnthropicMessageConverter {

    private static final Logger LOG = LoggerFactory.getLogger(AnthropicMessageConverter.class);

    private static final String ERROR_PREFIX = "Error: ";

    /**
     * The provider name the deprecated {@link #convertMessages(List)} overload passes down.
     *
     * <p>
     * A {@link ReasoningTrace}'s provider name can never be blank — the type rejects it — so this value matches
     * nothing, which is the point: that overload has no client to ask. It is belt and braces, since the overload also
     * strips the traces before delegating.
     */
    private static final String UNMATCHABLE_PROVIDER = "";

    /**
     * Converts aimon messages to Anthropic message params, replaying stored thinking blocks.
     *
     * @param messages
     *            The aimon messages (must not be null)
     * @param providerName
     *            This client's provider name, resolved once per request. A stored {@link ReasoningTrace} is replayed
     *            only when its own provider matches (must not be null)
     * @param reporter
     *            Where a dropped trace is reported (must not be null)
     * @return List of Anthropic message params
     * @throws NullPointerException
     *             if any argument is null
     */
    public List<MessageParam> convertMessages(List<Message> messages, String providerName,
            AnthropicDivergenceReporter reporter) {
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(providerName, "Provider name cannot be null");
        Objects.requireNonNull(reporter, "Reporter cannot be null");

        final List<MessageParam> messageParams = new ArrayList<>();
        for (Message message : messages) {
            messageParams.add(convertMessage(message, providerName, reporter));
        }
        return messageParams;
    }

    /**
     * Converts aimon messages to Anthropic message params <strong>without replaying thinking blocks</strong>.
     *
     * @param messages
     *            The aimon messages (must not be null)
     * @return List of Anthropic message params
     * @throws NullPointerException
     *             if messages is null
     * @deprecated This overload has no provider name to match a stored {@link ReasoningTrace} against, so it cannot
     *             tell one this client authored from one it must not send, and therefore replays none of them. The
     *             model re-derives its reasoning on every turn. Use
     *             {@link #convertMessages(List, String, AnthropicDivergenceReporter)}. Kept rather than removed
     *             because it is public API of a published module and the delegate costs three lines; whether it is
     *             eventually removed or stays is a decision for a later release.
     */
    @Deprecated(since = "0.2.5")
    public List<MessageParam> convertMessages(List<Message> messages) {
        Objects.requireNonNull(messages, "Messages cannot be null");

        // Strip the traces rather than pass an unmatchable provider name through: the result is then exactly the
        // pre-thinking shape, block for block, rather than the emit rule's shape with everything dropped.
        return convertMessages(withoutReasoningTraces(messages), UNMATCHABLE_PROVIDER, (signature, message, args) -> {
        });
    }

    /**
     * Converts a single aimon message to an Anthropic message param.
     *
     * @param message
     *            The aimon message
     * @param providerName
     *            This client's provider name
     * @param reporter
     *            Where a dropped trace is reported
     * @return Anthropic message param
     * @throws NullPointerException
     *             if message is null
     * @throws IllegalArgumentException
     *             if role is not supported
     */
    private MessageParam convertMessage(Message message, String providerName, AnthropicDivergenceReporter reporter) {
        Objects.requireNonNull(message, "Message cannot be null");

        if (message.getRole() == Role.USER) {
            return convertUserMessage(message);
        } else if (message.getRole() == Role.ASSISTANT) {
            return convertAssistantMessage(message, providerName, reporter);
        } else if (message.getRole() == Role.TOOL) {
            return convertToolResultMessage(message);
        } else {
            throw new IllegalArgumentException("Unsupported role: " + message.getRole());
        }
    }

    /**
     * Drops every stored reasoning trace, leaving each message otherwise untouched.
     *
     * <p>
     * Used by the deprecated overload above and by a client configured with
     * {@code AnthropicConfig.replayThinkingBlocks(false)}. A message that carries no traces is passed through by
     * identity rather than copied; the returned list itself is always a fresh one.
     *
     * @param messages
     *            The aimon messages (must not be null)
     * @return The same messages with their reasoning traces removed
     */
    public static List<Message> withoutReasoningTraces(List<Message> messages) {
        Objects.requireNonNull(messages, "Messages cannot be null");

        final List<Message> stripped = new ArrayList<>(messages.size());
        for (Message message : messages) {
            stripped.add(message.hasReasoningTraces() ? message.withReasoningTraces(List.of()) : message);
        }
        return stripped;
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
     * Converts an assistant message, applying the emit rule.
     *
     * <p>
     * The emit rule, the mirror of {@link AnthropicOutputBlocks}'s capture rule:
     *
     * <ol>
     * <li>every trace with no anchor, in stored order — these lead the message, which is what makes a turn begin with
     * a thinking block as extended mode requires;
     * <li>the text block, when there is text;
     * <li>for each tool use in order: its anchored traces in stored order, then the {@code tool_use} block.
     * </ol>
     *
     * <p>
     * The vendor requires every thinking block to come back <em>complete and unmodified, alongside the tool_use block
     * it accompanied</em>, and rejects a latest assistant message whose consecutive thinking blocks were rearranged,
     * edited or partially dropped. Walking the stored list in order is what makes reordering impossible here; keeping
     * the payload opaque is what makes editing impossible.
     *
     * @param message
     *            The assistant message
     * @param providerName
     *            This client's provider name
     * @param reporter
     *            Where a dropped trace is reported
     * @return Anthropic message param
     */
    private MessageParam convertAssistantMessage(Message message, String providerName,
            AnthropicDivergenceReporter reporter) {
        final List<ReasoningTrace> traces = message.getReasoningTraces();

        // The pre-thinking fast path, unchanged and still the one a thinking-off deployment takes on every turn: a
        // transcript with no traces reaches the wire exactly as it did before.
        if (!message.hasToolUses() && traces.isEmpty()) {
            return MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(message.getContent()).build();
        }

        final List<ContentBlockParam> contentBlocks = new ArrayList<>();

        // 1. every trace with no anchor, in stored order.
        for (ReasoningTrace trace : traces) {
            if (trace.getToolUseId().isEmpty()) {
                appendReasoning(trace, providerName, reporter, contentBlocks);
            }
        }

        // 2. the text block, when there is text. An assistant message that is nothing but thinking must not emit an
        // empty text block — the API rejects one.
        if (!message.getContent().isEmpty()) {
            contentBlocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(message.getContent()).build()));
        }

        // 3. for each tool use in order: its anchored traces (stored order), then the tool use block itself.
        for (ToolUse toolUse : message.getToolUses()) {
            for (ReasoningTrace trace : traces) {
                if (trace.getToolUseId().filter(id -> id.equals(toolUse.getId())).isPresent()) {
                    appendReasoning(trace, providerName, reporter, contentBlocks);
                }
            }
            contentBlocks.add(convertToolUseToBlockParam(toolUse));
        }

        // 4. anything left over. A trace anchored to a call this message does not carry is emitted by neither loop
        // above, and dropping it is still right for the wire — there is no block to put it in front of. Saying so is
        // what the other two drop reasons already do; a silent omission is the one shape an operator cannot diagnose
        // from the outside.
        reportOrphanedTraces(traces, message.getToolUses(), reporter);

        if (contentBlocks.isEmpty()) {
            // Every trace was dropped and there was neither text nor a tool use. An empty block list is not a legal
            // message, so fall back to the string form this message would have taken before.
            return MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(message.getContent()).build();
        }
        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(contentBlocks).build();
    }

    /**
     * Reports traces anchored to a tool use that is not in this message.
     *
     * <p>
     * Not reachable through in-tree code today — {@code MessageStripper} drops all of a message's traces while keeping
     * its tool uses, and {@code DefaultCompactionEngine} carries survivors whole — so this is the module's house style
     * applied to a branch only a caller assembling a {@link Message} by hand can reach, rather than a fix for an
     * observed fault.
     *
     * <p>
     * The author named is the <em>trace's</em>, not this client's. An orphan is reached by neither emit loop, so it
     * never passes through {@link #reportDroppedTrace} and this is the only thing ever said about it — which makes it
     * the one drop path whose wording can name the wrong provider.
     */
    private void reportOrphanedTraces(List<ReasoningTrace> traces, List<ToolUse> toolUses,
            AnthropicDivergenceReporter reporter) {
        if (traces.isEmpty()) {
            return;
        }
        final Set<String> present = new HashSet<>();
        for (ToolUse toolUse : toolUses) {
            present.add(toolUse.getId());
        }
        for (ReasoningTrace trace : traces) {
            trace.getToolUseId().filter(id -> !present.contains(id))
                    .ifPresent(id -> reporter.report("orphanedReasoningTrace@" + trace.getProviderName(),
                            "A stored {} reasoning trace is anchored to tool use {}, which this message does not "
                                    + "carry; it is being dropped and the model will re-derive that reasoning.",
                            trace.getProviderName(), id));
        }
    }

    private void appendReasoning(ReasoningTrace trace, String providerName, AnthropicDivergenceReporter reporter,
            List<ContentBlockParam> contentBlocks) {
        AnthropicReasoningTraces.toBlockParam(trace, providerName).ifPresentOrElse(contentBlocks::add,
                () -> reportDroppedTrace(trace, providerName, reporter));
    }

    private void reportDroppedTrace(ReasoningTrace trace, String providerName, AnthropicDivergenceReporter reporter) {
        if (!AnthropicReasoningTraces.isOurs(trace, providerName)) {
            reporter.report("foreignReasoningTrace@" + trace.getProviderName(),
                    "This conversation carries reasoning traces authored by {}, which {} cannot replay; they are "
                            + "being dropped and the model will re-derive that reasoning.",
                    trace.getProviderName(), providerName);
            return;
        }
        reporter.report("unparseableReasoningTrace@" + providerName,
                "A stored {} thinking block could not be parsed by this build and is being dropped; the model will "
                        + "re-derive that reasoning. This is what a transcript written by a newer build looks like.",
                providerName);
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
