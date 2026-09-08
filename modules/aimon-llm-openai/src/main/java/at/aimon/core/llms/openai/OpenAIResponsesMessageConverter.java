package at.aimon.core.llms.openai;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.Tool;

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
import at.aimon.core.llms.openai.exception.MessageConversionException;
import at.aimon.core.llms.openai.exception.ToolConversionException;

/**
 * Converts between aimon message types and the Responses API's item vocabulary.
 *
 * <p>
 * <strong>The rule is parity with {@link OpenAIMessageConverter}, case for case, including every throw.</strong>
 * Switching endpoints must never change what a message <em>means</em>, so this class is judged against what the Chat
 * converter already does rather than against the Responses API in isolation: the same {@code "Error: "} prefix on a
 * failed tool result, the same {@code data:} URL for a base64 image, the same {@code [File: …]} header (and the same
 * absence of it when the document has no file name), the same {@code MessageConversionException} for a non-text
 * document and for an unknown block type, the same {@code IllegalArgumentException} for an unsupported role, and the
 * same per-tool {@link ToolConversionException} wrapping with the same message.
 *
 * <p>
 * {@link OpenAIMessageConverter} is deliberately <em>not</em> opened or reused: it returns Chat Completions types
 * throughout, so a second conversion has to exist, and keeping the Chat one untouched is what makes "Chat Completions
 * is unchanged" structural rather than a promise.
 *
 * <p>
 * <strong>Ordering is this class's other job.</strong> A reasoning item must be replayed in front of the tool call it
 * produced, and {@link Message} cannot express that on its own — its text lives in content blocks and its calls live
 * in a separate list. So the capture rule ({@link #convertOutput}) anchors each reasoning item to the first tool call
 * that follows it in the provider's output order, and the emit rule ({@link #convertMessages}) reverses it: every
 * unanchored trace, then the assistant text, then each tool call preceded by its own anchored traces.
 *
 * <p>
 * Thread-safe and stateless.
 */
final class OpenAIResponsesMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponsesMessageConverter.class);

    /**
     * Serialises a replayed tool call's arguments.
     *
     * <p>
     * A plain mapper is correct here and is what the Chat converter uses for the same value: a {@link ToolUse} input
     * is an ordinary {@code Map}, not an SDK model, so the SDK mapper would emit identical bytes. The rule that
     * reasoning payloads must go through {@code ObjectMappers.jsonMapper()} is about SDK models and does not extend
     * to this — see {@link OpenAiReasoningTraces}.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Converts aimon messages to Responses input items.
     *
     * @param messages
     *            the messages (must not be null)
     * @param providerName
     *            this client's provider name, used to drop traces authored elsewhere (must not be null)
     * @param reporter
     *            where a dropped trace is reported (must not be null)
     * @return the input items, in the order the API must receive them
     * @throws MessageConversionException
     *             for an unsupported content block, exactly as the Chat converter throws
     * @throws IllegalArgumentException
     *             for an unsupported role, exactly as the Chat converter throws
     */
    List<ResponseInputItem> convertMessages(List<Message> messages, String providerName,
            OpenAIDivergenceReporter reporter) {
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(providerName, "providerName cannot be null");
        Objects.requireNonNull(reporter, "reporter cannot be null");

        final List<ResponseInputItem> items = new ArrayList<>();
        for (Message message : messages) {
            convertMessage(message, providerName, reporter, items);
        }
        return items;
    }

    private void convertMessage(Message message, String providerName, OpenAIDivergenceReporter reporter,
            List<ResponseInputItem> out) {
        Objects.requireNonNull(message, "Message cannot be null");

        if (message.getRole() == Role.TOOL) {
            // One function_call_output per result, same order, same formatting (including the "Error: " prefix) as
            // the Chat converter's one tool message per result.
            for (ToolUseResult result : message.getToolUseResults()) {
                out.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                        .callId(result.getToolUseId()).output(formatToolResult(result)).build()));
            }
        } else if (message.getRole() == Role.USER) {
            out.add(convertUserMessage(message));
        } else if (message.getRole() == Role.ASSISTANT) {
            convertAssistantMessage(message, providerName, reporter, out);
        } else {
            throw new IllegalArgumentException("Unsupported role: " + message.getRole());
        }
    }

    /**
     * Emits one user message.
     *
     * <p>
     * The multimodal carrier is used for the text-only case too, so there is no second shape to keep in sync — unlike
     * the Chat converter, whose text-only fast path exists only because {@code content(String)} is cheaper there.
     */
    private ResponseInputItem convertUserMessage(Message message) {
        final List<ResponseInputContent> parts = new ArrayList<>();
        for (ContentBlock block : message.getContentBlocks()) {
            parts.add(toResponseInputContent(block));
        }
        return ResponseInputItem.ofMessage(
                ResponseInputItem.Message.builder().content(parts).role(ResponseInputItem.Message.Role.USER).build());
    }

    /**
     * Emits one assistant turn, applying the emit rule of the class javadoc.
     */
    private void convertAssistantMessage(Message message, String providerName, OpenAIDivergenceReporter reporter,
            List<ResponseInputItem> out) {
        final List<ReasoningTrace> traces = message.getReasoningTraces();

        // 1. every trace with no anchor, in stored order.
        for (ReasoningTrace trace : traces) {
            if (trace.getToolUseId().isEmpty()) {
                appendReasoning(trace, providerName, reporter, out);
            }
        }

        // 2. the assistant text item, when there is text. Text-only on the content side, matching the Chat
        // converter's content(message.getContent()) -- non-text assistant blocks are replayed on neither endpoint.
        //
        // EasyInputMessage rather than the user carrier: ResponseInputItem.Message.Role declares only USER / SYSTEM /
        // DEVELOPER, so it cannot express this role at all. The remaining candidate, ofResponseOutputMessage, requires
        // a server-assigned id and status, and inventing an item id is exactly what this converter refuses to do.
        final String text = message.getContent();
        if (!text.isEmpty()) {
            out.add(ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder().content(text).role(EasyInputMessage.Role.ASSISTANT).build()));
        }

        // 3. for each tool use in order: its anchored traces (stored order), then the tool call itself.
        for (ToolUse toolUse : message.getToolUses()) {
            for (ReasoningTrace trace : traces) {
                if (trace.getToolUseId().filter(id -> id.equals(toolUse.getId())).isPresent()) {
                    appendReasoning(trace, providerName, reporter, out);
                }
            }
            out.add(ResponseInputItem.ofFunctionCall(toFunctionCall(toolUse)));
        }
    }

    private void appendReasoning(ReasoningTrace trace, String providerName, OpenAIDivergenceReporter reporter,
            List<ResponseInputItem> out) {
        OpenAiReasoningTraces.toItem(trace, providerName).ifPresentOrElse(
                item -> out.add(ResponseInputItem.ofReasoning(item)),
                () -> reportDroppedTrace(trace, providerName, reporter));
    }

    private void reportDroppedTrace(ReasoningTrace trace, String providerName, OpenAIDivergenceReporter reporter) {
        if (!OpenAiReasoningTraces.isOurs(trace, providerName)) {
            reporter.report("foreignReasoningTrace@" + trace.getProviderName(),
                    "This conversation carries reasoning traces authored by {}, which {} cannot replay; they are "
                            + "being dropped and the model will re-derive that reasoning.",
                    trace.getProviderName(), providerName);
            return;
        }
        reporter.report("unparseableReasoningTrace@" + providerName,
                "A stored {} reasoning trace could not be parsed by this build and is being dropped; the model will "
                        + "re-derive that reasoning. This is what a transcript written by a newer build looks like.",
                providerName);
    }

    /**
     * Builds the {@code function_call} item for a replayed tool call.
     *
     * <p>
     * {@link ToolUse#getId()} holds the {@code call_id}, because that is the id a {@code function_call_output} must
     * match. The item's own {@code id} ({@code fc_…}) is optional in the SDK and is <strong>deliberately not
     * preserved</strong> across a replay: it is server-assigned, and inventing one would be worse than omitting it.
     */
    private ResponseFunctionToolCall toFunctionCall(ToolUse toolUse) {
        try {
            return ResponseFunctionToolCall.builder().callId(toolUse.getId()).name(toolUse.getName())
                    .arguments(OBJECT_MAPPER.writeValueAsString(toolUse.getInput())).build();
        } catch (JsonProcessingException e) {
            log.error("Failed to convert tool use to JSON: {}", toolUse.getName(), e);
            throw new MessageConversionException("Failed to convert tool use to JSON: " + toolUse.getName(), e);
        }
    }

    /**
     * Converts one content block, mirroring {@link OpenAIMessageConverter}'s {@code toOpenAIPart} case for case.
     */
    private ResponseInputContent toResponseInputContent(ContentBlock block) {
        if (block instanceof TextContentBlock textBlock) {
            return ofInputText(textBlock.getText());
        } else if (block instanceof ImageContentBlock imageBlock) {
            final String imageUrl;
            if (imageBlock.getSource() == ImageContentBlock.Source.BASE64) {
                imageUrl = "data:" + imageBlock.getMimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(imageBlock.getData());
            } else {
                imageUrl = imageBlock.getUrl();
            }
            // detail is a required field on this builder, and AUTO is what the Chat path effectively sends by
            // omitting the field -- parity, not decoration.
            return ResponseInputContent.ofInputImage(
                    ResponseInputImage.builder().imageUrl(imageUrl).detail(ResponseInputImage.Detail.AUTO).build());
        } else if (block instanceof DocumentContentBlock documentBlock) {
            if (documentBlock.isTextBased()) {
                final StringBuilder sb = new StringBuilder();
                if (documentBlock.getFileName() != null) {
                    sb.append("[File: ").append(documentBlock.getFileName()).append(" (")
                            .append(documentBlock.getMimeType()).append(")]\n");
                }
                sb.append(new String(documentBlock.getData(), StandardCharsets.UTF_8));
                return ofInputText(sb.toString());
            }
            // The Responses API does have a native input_file slot, and it is deliberately unused: taking it would
            // make the same Message mean different things on the two endpoints, and would claim a capability no live
            // call has verified. Parity includes the throw.
            throw new MessageConversionException("OpenAI does not support document content blocks natively. "
                    + "Consider extracting text from the document before sending.");
        } else {
            throw new MessageConversionException("Unsupported content block type: " + block.getClass().getName());
        }
    }

    private static ResponseInputContent ofInputText(String text) {
        return ResponseInputContent.ofInputText(ResponseInputText.builder().text(text).build());
    }

    /**
     * Converts aimon tool definitions to Responses tools.
     *
     * <p>
     * The name, description and parameters are copied exactly as {@link OpenAIMessageConverter#convertTools} copies
     * them — key by key into a {@code @JsonValue} map, never by re-serialising the schema.
     *
     * <p>
     * <strong>{@code strict} is set to {@code false}, and that is the parity choice rather than the safe-looking
     * one.</strong> The field is required here and is never set on Chat, where the server's default — non-strict —
     * applies. Strict mode accepts only a subset of JSON Schema (it requires {@code additionalProperties: false} on
     * every object and every property listed in {@code required}), and this repo scopes that rule to
     * {@code at.aimon.core.tools} and explicitly exempts MCP schemas, because an MCP tool advertises the server's
     * schema and not ours. Writing {@code true} would therefore turn tools that work today into server-side
     * rejections on the endpoint switch. Omitting the setter is not an option at all: {@code build()} throws.
     *
     * @param toolDefinitions
     *            the tool definitions (must not be null)
     * @return the Responses tools
     * @throws ToolConversionException
     *             per tool, with the same message the Chat converter produces
     */
    List<Tool> convertTools(List<ToolDefinition> toolDefinitions) {
        Objects.requireNonNull(toolDefinitions, "Tool definitions cannot be null");

        final List<Tool> tools = new ArrayList<>();
        for (ToolDefinition tool : toolDefinitions) {
            try {
                final FunctionTool.Parameters.Builder paramsBuilder = FunctionTool.Parameters.builder();
                for (Map.Entry<String, Object> entry : tool.getInputSchema().entrySet()) {
                    paramsBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
                }

                final FunctionTool functionTool = FunctionTool.builder().name(tool.getName())
                        .description(tool.getDescription()).parameters(paramsBuilder.build()).strict(false).build();

                tools.add(Tool.ofFunction(functionTool));
            } catch (Exception e) {
                log.error("Failed to convert tool definition: {}", tool.getName(), e);
                throw new ToolConversionException("Failed to convert tool definition: " + tool.getName(), e);
            }
        }
        return tools;
    }

    /**
     * Walks a response's output items in order and splits them into text, tool uses and reasoning traces.
     *
     * <p>
     * Takes the item list rather than a whole {@code Response} so that one routine serves both paths: the blocking
     * path passes {@code response.output()}, and the streaming path passes what it accumulated from
     * {@code response.output_item.done} — which the SDK names as the source whose {@code encrypted_content} is
     * complete under {@code store: false}, the mode this client uses.
     *
     * <p>
     * The capture rule is the mirror of the emit rule: each reasoning item anchors to the <em>first tool call that
     * follows it</em>, or to nothing when none does. So {@code [r1, call_1, r2, call_2]} round-trips exactly, and both
     * {@code [r1, msg]} and {@code [r1, r2, call_1]} degenerate correctly.
     *
     * @param items
     *            the output items in the provider's own order (must not be null)
     * @param providerName
     *            this client's provider name (must not be null)
     * @return the split output
     */
    Output convertOutput(List<ResponseOutputItem> items, String providerName) {
        Objects.requireNonNull(items, "items cannot be null");
        Objects.requireNonNull(providerName, "providerName cannot be null");

        final StringBuilder text = new StringBuilder();
        final List<ToolUse> toolUses = new ArrayList<>();
        final List<ReasoningTrace> traces = new ArrayList<>();
        final List<ResponseReasoningItem> pending = new ArrayList<>();
        boolean sawReasoning = false;
        boolean sawEncryptedContent = false;

        for (ResponseOutputItem item : items) {
            if (item.isReasoning()) {
                final ResponseReasoningItem reasoning = item.asReasoning();
                sawReasoning = true;
                sawEncryptedContent = sawEncryptedContent || reasoning.encryptedContent().isPresent();
                pending.add(reasoning);
            } else if (item.isFunctionCall()) {
                final ResponseFunctionToolCall call = item.asFunctionCall();
                // Identity fields, read normally: a function_call missing its call_id or name is a provider fault,
                // and the throw lands inside the client's try where every other failure is classified.
                final String callId = call.callId();
                for (ResponseReasoningItem reasoning : pending) {
                    traces.add(OpenAiReasoningTraces.toTrace(reasoning, providerName, callId));
                }
                pending.clear();
                toolUses.add(ToolUse.of(callId, call.name(), parseArguments(call.arguments())));
            } else if (item.isMessage()) {
                appendMessageText(item.asMessage(), text);
            }
        }
        for (ResponseReasoningItem reasoning : pending) {
            traces.add(OpenAiReasoningTraces.toTrace(reasoning, providerName, null));
        }

        return Output.builder().text(text.toString()).toolUses(toolUses).reasoningTraces(traces)
                .reasoningItemsSeen(sawReasoning).encryptedContentSeen(sawEncryptedContent).build();
    }

    private static void appendMessageText(ResponseOutputMessage message, StringBuilder text) {
        for (ResponseOutputMessage.Content content : message.content()) {
            if (content.isOutputText()) {
                text.append(content.asOutputText().text());
            }
        }
    }

    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return Map.of();
        }
        try {
            final Map<String, Object> parsed = OBJECT_MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<HashMap<String, Object>>() {
                    });
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool call arguments; treating them as empty: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Formats tool result content, including the {@code "Error: "} prefix the Chat converter applies.
     *
     * <p>
     * Duplicated from {@link OpenAIMessageConverter} rather than shared, because that class is deliberately not
     * opened. Writing {@code result.getContent()} straight through here would quietly change what a failed tool call
     * looks like to the model on the new endpoint.
     */
    private static String formatToolResult(ToolUseResult toolUseResult) {
        if (toolUseResult.isError()) {
            return String.format("Error: %s", toolUseResult.getContent());
        }
        return toolUseResult.getContent();
    }

    /** The split of one response's output items. */
    static final class Output {

        private final String text;
        private final List<ToolUse> toolUses;
        private final List<ReasoningTrace> reasoningTraces;
        private final boolean reasoningItemsSeen;
        private final boolean encryptedContentSeen;

        private Output(Builder builder) {
            this.text = Objects.requireNonNullElse(builder.text, "");
            this.toolUses = List.copyOf(Objects.requireNonNullElse(builder.toolUses, List.<ToolUse>of()));
            this.reasoningTraces = List
                    .copyOf(Objects.requireNonNullElse(builder.reasoningTraces, List.<ReasoningTrace>of()));
            this.reasoningItemsSeen = builder.reasoningItemsSeen;
            this.encryptedContentSeen = builder.encryptedContentSeen;
        }

        static Builder builder() {
            return new Builder();
        }

        String getText() {
            return text;
        }

        List<ToolUse> getToolUses() {
            return toolUses;
        }

        List<ReasoningTrace> getReasoningTraces() {
            return reasoningTraces;
        }

        boolean hasToolCalls() {
            return !toolUses.isEmpty();
        }

        /**
         * @return whether the output carried at least one reasoning item, none of which had {@code encrypted_content}
         *         — the shape in which this whole feature silently does nothing, and therefore the one worth warning
         *         about
         */
        boolean reasoningWithoutEncryptedContent() {
            return reasoningItemsSeen && !encryptedContentSeen;
        }

        /** Builder for {@link Output}. */
        static final class Builder {
            private String text;
            private List<ToolUse> toolUses;
            private List<ReasoningTrace> reasoningTraces;
            private boolean reasoningItemsSeen;
            private boolean encryptedContentSeen;

            private Builder() {
            }

            Builder text(String text) {
                this.text = text;
                return this;
            }

            Builder toolUses(List<ToolUse> toolUses) {
                this.toolUses = toolUses;
                return this;
            }

            Builder reasoningTraces(List<ReasoningTrace> reasoningTraces) {
                this.reasoningTraces = reasoningTraces;
                return this;
            }

            Builder reasoningItemsSeen(boolean reasoningItemsSeen) {
                this.reasoningItemsSeen = reasoningItemsSeen;
                return this;
            }

            Builder encryptedContentSeen(boolean encryptedContentSeen) {
                this.encryptedContentSeen = encryptedContentSeen;
                return this;
            }

            Output build() {
                return new Output(this);
            }
        }
    }
}
