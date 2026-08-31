package at.aimon.core.agent.compact;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.MessageArtifact;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

/**
 * Strips non-text content (images, documents) from messages before they are forwarded to the summary LLM call, and
 * optionally redacts known secret/PII patterns from the surviving text.
 *
 * <p>
 * Image and document blocks are replaced with text placeholders (e.g. {@code [image]}, {@code [document]}) so the
 * summary model receives only text content. Tool uses, role, and artifacts are preserved structurally; tool result
 * content and text content blocks pass through the configured {@link SensitivePatternRedactor} (default
 * {@link SensitivePatternRedactor#none()}, no redaction) — design §9.3 mitigation for secret/PII leakage in
 * summary prompts.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class MessageStripper {

    public static final String IMAGE_PLACEHOLDER = "[image]";
    public static final String DOCUMENT_PLACEHOLDER = "[document]";

    private final SensitivePatternRedactor redactor;

    /**
     * Creates a stripper with no secret/PII redaction (image/document placeholders only).
     */
    public MessageStripper() {
        this(SensitivePatternRedactor.none());
    }

    /**
     * Creates a stripper that, in addition to image/document placeholder substitution, applies the supplied
     * {@link SensitivePatternRedactor} to all surviving text (TextContentBlock content, assistant text, and tool result
     * content).
     *
     * @param redactor
     *            the redactor (must not be null; pass {@link SensitivePatternRedactor#none()} to disable)
     * @throws NullPointerException
     *             if {@code redactor} is null
     */
    public MessageStripper(SensitivePatternRedactor redactor) {
        this.redactor = Objects.requireNonNull(redactor, "redactor cannot be null");
    }

    /**
     * Returns a new list of messages with all non-text content blocks replaced by short text placeholders, and the
     * configured redactor applied to all surviving text fields.
     *
     * @param messages
     *            the source messages (must not be null)
     * @return a new list with stripped messages (never null)
     */
    public List<Message> stripNonTextBlocks(List<Message> messages) {
        Objects.requireNonNull(messages, "messages cannot be null");
        final List<Message> result = new ArrayList<>(messages.size());
        for (Message original : messages) {
            result.add(stripMessage(original));
        }
        return result;
    }

    private Message stripMessage(Message original) {
        // Tool messages have no content blocks but still carry tool_result text that may need redaction.
        if (!original.hasNonTextContentBlocks() && !needsTextRewrite(original)) {
            return original;
        }
        final List<ContentBlock> stripped = new ArrayList<>(original.getContentBlocks().size());
        for (ContentBlock block : original.getContentBlocks()) {
            stripped.add(stripBlock(block));
        }
        // Reconstruct via the public factory methods to keep role-specific invariants.
        return rebuild(original, stripped);
    }

    private boolean needsTextRewrite(Message message) {
        return !redactor.isNoop();
    }

    private ContentBlock stripBlock(ContentBlock block) {
        if (block instanceof TextContentBlock) {
            return redactor.isNoop() ? block : TextContentBlock.of(redactor.redact(block.asText()));
        }
        if (block instanceof ImageContentBlock) {
            return TextContentBlock.of(IMAGE_PLACEHOLDER);
        }
        if (block instanceof DocumentContentBlock) {
            return TextContentBlock.of(DOCUMENT_PLACEHOLDER);
        }
        // Unknown block — preserve textual representation, redacted if a redactor is configured
        final String text = block.asText();
        return TextContentBlock.of(redactor.isNoop() ? text : redactor.redact(text));
    }

    private Message rebuild(Message original, List<ContentBlock> strippedBlocks) {
        return switch (original.getRole()) {
            case USER -> Message.user(strippedBlocks);
            case ASSISTANT -> {
                final String text = joinText(strippedBlocks);
                if (original.hasToolUses()) {
                    final List<MessageArtifact> artifacts = original.getArtifacts();
                    yield artifacts.isEmpty()
                            ? Message.assistant(text, original.getToolUses())
                            : Message.assistant(text, original.getToolUses(), artifacts);
                }
                yield Message.assistant(text);
            }
            case TOOL -> original.hasToolResults()
                    ? Message.toolUseResults(stripToolResults(original.getToolUseResults()))
                    : original;
            default -> original;
        };
    }

    private List<ToolUseResult> stripToolResults(List<ToolUseResult> source) {
        if (redactor.isNoop()) {
            return source;
        }
        final List<ToolUseResult> redacted = new ArrayList<>(source.size());
        for (ToolUseResult result : source) {
            final String redactedContent = redactor.redact(result.getContent());
            if (redactedContent.equals(result.getContent())) {
                redacted.add(result);
            } else if (result.isError()) {
                redacted.add(ToolUseResult.error(result.getToolUseId(), redactedContent));
            } else {
                redacted.add(ToolUseResult.success(result.getToolUseId(), redactedContent));
            }
        }
        return redacted;
    }

    private String joinText(List<ContentBlock> blocks) {
        final StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            sb.append(block.asText());
        }
        return sb.toString();
    }
}
