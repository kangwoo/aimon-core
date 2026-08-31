package at.aimon.core.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

/**
 * Represents a message in a conversation with an LLM.
 *
 * <p>
 * Immutable value object containing the message role (USER, ASSISTANT, or TOOL), content blocks, and optionally tool
 * uses or tool results.
 *
 * <p>
 * Messages can contain:
 *
 * <ul>
 * <li>Text only (standard conversation)
 * <li>Multiple content blocks including text, images, and documents (multimodal)
 * <li>Text + tool uses (assistant requesting tool execution)
 * <li>Tool results (tool role for execution results)
 * </ul>
 *
 * <p>
 * Thread-safe and immutable.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Simple text message
 *     Message userMessage = Message.user("What is the weather?");
 *
 *     // Multimodal message with content blocks
 *     Message multimodal = Message.user(List.of(TextContentBlock.of("What's in this image?"),
 *             ImageContentBlock.ofBase64(imageData, "image/png")));
 *
 *     // Assistant message with tool use
 *     Message assistantMessage = Message.assistant("I'll check the weather.",
 *             List.of(ToolUse.of("tool_1", "bash", Map.of("command", "weather"))));
 *
 *     // Tool message with tool results
 *     Message toolResultMessage = Message.toolUseResults(List.of(ToolUseResult.success("tool_1", "Sunny, 25°C")));
 * }
 * </pre>
 */
public final class Message {

    private static final int CONTENT_PREVIEW_MAX_LENGTH = 50;

    private final Role role;
    private final List<ContentBlock> contentBlocks;
    private final String textContent;
    private final boolean hasNonTextContent;
    private final List<ToolUse> toolUses;
    private final List<ToolUseResult> toolUseResults;
    private final List<MessageArtifact> artifacts;

    /**
     * Creates a new Message.
     *
     * @param role
     *            The message role (must not be null)
     * @param contentBlocks
     *            The content blocks (must not be null, elements must not be null)
     * @param toolUses
     *            The tool uses (can be null or empty)
     * @param toolUseResults
     *            The tool results (can be null or empty)
     * @throws NullPointerException
     *             if role or contentBlocks is null, or if any element is null
     */
    private Message(Role role, List<ContentBlock> contentBlocks, List<ToolUse> toolUses,
            List<ToolUseResult> toolUseResults) {
        this(role, contentBlocks, toolUses, toolUseResults, null);
    }

    /**
     * Creates a new Message with artifacts.
     *
     * @param role
     *            The message role (must not be null)
     * @param contentBlocks
     *            The content blocks (must not be null, elements must not be null)
     * @param toolUses
     *            The tool uses (can be null or empty)
     * @param toolUseResults
     *            The tool results (can be null or empty)
     * @param artifacts
     *            The file artifacts produced by tool execution (can be null or empty)
     * @throws NullPointerException
     *             if role or contentBlocks is null, or if any element is null
     */
    private Message(Role role, List<ContentBlock> contentBlocks, List<ToolUse> toolUses,
            List<ToolUseResult> toolUseResults, List<MessageArtifact> artifacts) {
        this.role = Objects.requireNonNull(role, "Role cannot be null");
        this.contentBlocks = List.copyOf(Objects.requireNonNull(contentBlocks, "Content blocks cannot be null"));
        this.toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
        this.toolUseResults = toolUseResults == null ? List.of() : List.copyOf(toolUseResults);
        this.artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);

        // Pre-compute derived text content and non-text flag from immutable content blocks
        this.textContent = this.contentBlocks.stream().filter(block -> block instanceof TextContentBlock)
                .map(ContentBlock::asText).collect(Collectors.joining());
        this.hasNonTextContent = this.contentBlocks.stream().anyMatch(block -> !(block instanceof TextContentBlock));
    }

    /**
     * Creates a user message with text content only.
     *
     * @param content
     *            The message content (must not be null)
     * @return A new user message
     * @throws NullPointerException
     *             if content is null
     */
    public static Message user(String content) {
        Objects.requireNonNull(content, "Content cannot be null");
        return new Message(Role.USER, List.of(TextContentBlock.of(content)), null, null);
    }

    /**
     * Creates a user message with multiple content blocks.
     *
     * @param contentBlocks
     *            The content blocks (must not be null or empty)
     * @return A new user message
     * @throws NullPointerException
     *             if contentBlocks is null or contains null elements
     * @throws IllegalArgumentException
     *             if contentBlocks is empty
     */
    public static Message user(List<ContentBlock> contentBlocks) {
        Objects.requireNonNull(contentBlocks, "Content blocks cannot be null");
        if (contentBlocks.isEmpty()) {
            throw new IllegalArgumentException("Content blocks cannot be empty");
        }
        validateNoNullElements(contentBlocks, "Content blocks must not contain null elements");
        return new Message(Role.USER, contentBlocks, null, null);
    }

    /**
     * Creates a user message with text and attachment content blocks.
     *
     * <p>
     * If the text is empty, only the attachment blocks are included.
     *
     * @param text
     *            The text content (must not be null, can be empty)
     * @param attachments
     *            The attachment content blocks (must not be null)
     * @return A new user message
     * @throws NullPointerException
     *             if text or attachments is null, or if attachments contains null elements
     * @throws IllegalArgumentException
     *             if both text is empty and attachments is empty
     */
    public static Message userWithAttachments(String text, List<ContentBlock> attachments) {
        Objects.requireNonNull(text, "Text cannot be null");
        Objects.requireNonNull(attachments, "Attachments cannot be null");
        validateNoNullElements(attachments, "Attachments must not contain null elements");

        final List<ContentBlock> blocks = new ArrayList<>();
        if (!text.isEmpty()) {
            blocks.add(TextContentBlock.of(text));
        }
        blocks.addAll(attachments);

        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Message must have at least one content block");
        }
        return new Message(Role.USER, blocks, null, null);
    }

    /**
     * Creates an assistant message with text content only.
     *
     * @param content
     *            The message content (must not be null)
     * @return A new assistant message
     * @throws NullPointerException
     *             if content is null
     */
    public static Message assistant(String content) {
        Objects.requireNonNull(content, "Content cannot be null");
        return new Message(Role.ASSISTANT, List.of(TextContentBlock.of(content)), null, null);
    }

    /**
     * Creates an assistant message with text content and tool uses.
     *
     * @param content
     *            The message content (must not be null)
     * @param toolUses
     *            The tool uses (must not be null)
     * @return A new assistant message with tool uses
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static Message assistant(String content, List<ToolUse> toolUses) {
        Objects.requireNonNull(content, "Content cannot be null");
        Objects.requireNonNull(toolUses, "Tool uses cannot be null");
        return new Message(Role.ASSISTANT, List.of(TextContentBlock.of(content)), toolUses, null);
    }

    /**
     * Creates an assistant message with text content, tool uses, and file artifacts.
     *
     * @param content
     *            The message content (must not be null)
     * @param toolUses
     *            The tool uses (must not be null)
     * @param artifacts
     *            The file artifacts produced by tool execution (must not be null)
     * @return A new assistant message with tool uses and artifacts
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static Message assistant(String content, List<ToolUse> toolUses, List<MessageArtifact> artifacts) {
        Objects.requireNonNull(content, "Content cannot be null");
        Objects.requireNonNull(toolUses, "Tool uses cannot be null");
        Objects.requireNonNull(artifacts, "Artifacts cannot be null");
        return new Message(Role.ASSISTANT, List.of(TextContentBlock.of(content)), toolUses, null, artifacts);
    }

    /**
     * Returns a new Message with the specified artifacts attached.
     *
     * <p>
     * Creates an immutable copy of this message with the given artifacts. This is useful for attaching artifact
     * metadata
     * after tool execution completes.
     *
     * @param artifacts
     *            The file artifacts to attach (must not be null)
     * @return A new Message with the same content, role, tool uses, and tool results, plus the given artifacts
     * @throws NullPointerException
     *             if artifacts is null
     */
    public Message withArtifacts(List<MessageArtifact> artifacts) {
        Objects.requireNonNull(artifacts, "Artifacts cannot be null");
        return new Message(this.role, this.contentBlocks, this.toolUses, this.toolUseResults, artifacts);
    }

    /**
     * Returns a copy of this message with {@code transform} applied to every text fragment it
     * carries: the text of each {@link TextContentBlock}, the content of each {@link ToolUseResult},
     * and every {@link String} value inside each {@link ToolUse} input map (recursively through
     * nested maps and lists). Non-text content blocks (images, documents), the role, tool-use
     * ids/names, and artifacts are preserved unchanged.
     *
     * <p>
     * This is the single entry point for whole-message text rewriting (e.g. secret/PII redaction)
     * so that no role — including {@code TOOL} results, which carry text outside the content blocks
     * — is silently skipped. Tool-use argument values are covered as well because in an IT-ops
     * automation agent an assistant tool call routinely embeds credentials in its arguments (e.g.
     * {@code mysql -pSECRET}, {@code curl -u user:pass}, {@code --token=...}); leaving them out
     * would let secrets bypass the redaction gate.
     *
     * @param transform
     *            applied to each text fragment (must not be null; must not return null)
     * @return a new Message with all text fragments transformed
     * @throws NullPointerException
     *             if {@code transform} is null or returns null for some fragment
     */
    public Message mapText(UnaryOperator<String> transform) {
        Objects.requireNonNull(transform, "transform cannot be null");
        final List<ContentBlock> newBlocks = new ArrayList<>(contentBlocks.size());
        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextContentBlock textBlock) {
                newBlocks.add(TextContentBlock
                        .of(Objects.requireNonNull(transform.apply(textBlock.getText()), "transform returned null")));
            } else {
                newBlocks.add(block);
            }
        }
        final List<ToolUse> newToolUses = new ArrayList<>(toolUses.size());
        for (ToolUse toolUse : toolUses) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> newInput = (Map<String, Object>) mapValue(toolUse.getInput(), transform);
            newToolUses.add(ToolUse.of(toolUse.getId(), toolUse.getName(), newInput));
        }
        final List<ToolUseResult> newResults = new ArrayList<>(toolUseResults.size());
        for (ToolUseResult result : toolUseResults) {
            String transformed = Objects.requireNonNull(transform.apply(result.getContent()),
                    "transform returned null");
            ToolUseResult rebuilt = result.isError()
                    ? ToolUseResult.error(result.getToolUseId(), transformed)
                    : ToolUseResult.success(result.getToolUseId(), transformed);
            newResults.add(rebuilt.withRenderPayload(result.getRenderPayload()));
        }
        return new Message(this.role, newBlocks, newToolUses, newResults, this.artifacts);
    }

    /**
     * Recursively applies {@code transform} to every {@link String} value reachable from {@code value}, descending
     * into {@link Map} values and {@link List} elements. Non-string scalars (numbers, booleans, null) are returned
     * unchanged. Used by {@link #mapText(UnaryOperator)} to redact tool-use argument values.
     */
    private static Object mapValue(Object value, UnaryOperator<String> transform) {
        if (value instanceof String s) {
            return Objects.requireNonNull(transform.apply(s), "transform returned null");
        }
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), mapValue(entry.getValue(), transform));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            final List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(mapValue(item, transform));
            }
            return out;
        }
        return value;
    }

    /**
     * Creates a tool message containing tool results.
     *
     * <p>
     * Tool results use the TOOL role internally. LlmClient implementations are responsible for converting this to the
     * appropriate provider-specific format:
     *
     * <ul>
     * <li>Anthropic API: Converts to USER message with tool_result content
     * <li>OpenAI API: Uses native "tool" role
     * </ul>
     *
     * @param toolResults
     *            The tool results (must not be null and not empty)
     * @return A new tool message with tool results
     * @throws NullPointerException
     *             if toolResults is null
     * @throws IllegalArgumentException
     *             if toolResults is empty
     */
    public static Message toolUseResults(List<ToolUseResult> toolResults) {
        Objects.requireNonNull(toolResults, "Tool results cannot be null");
        if (toolResults.isEmpty()) {
            throw new IllegalArgumentException("Tool results cannot be empty");
        }
        return new Message(Role.TOOL, List.of(), null, toolResults);
    }

    /**
     * Reconstructs a message from its fully decomposed parts.
     *
     * <p>
     * This is the reconstruction seam for persistence and codecs (e.g. rehydrating a subagent transcript from a
     * serialized {@code SessionSnapshot}). Unlike the role-specific factories ({@link #user(List)},
     * {@link #assistant(String, List, List)}, {@link #toolUseResults(List)}), it accepts every field independently so
     * that <em>any</em> message shape a live conversation can produce round-trips losslessly — an {@code ASSISTANT}
     * turn carrying multiple or non-text content blocks together with tool uses and artifacts, a {@code TOOL} turn
     * carrying results with empty content blocks, and so on. The derived {@code textContent}/{@code hasNonTextContent}
     * fields are recomputed from the given content blocks exactly as for a freshly constructed message. Prefer the
     * role-specific factories in ordinary code; reach for this only when rebuilding a message from its stored form.
     *
     * @param role
     *            the message role (must not be null)
     * @param contentBlocks
     *            the content blocks (must not be null, elements must not be null; may be empty for a {@code TOOL}
     *            message)
     * @param toolUses
     *            the tool uses (may be null or empty)
     * @param toolUseResults
     *            the tool results (may be null or empty)
     * @param artifacts
     *            the file artifacts (may be null or empty)
     * @return a reconstructed message equal to the original it was serialized from
     * @throws NullPointerException
     *             if role or contentBlocks is null, or if any content block element is null
     */
    public static Message restore(Role role, List<ContentBlock> contentBlocks, List<ToolUse> toolUses,
            List<ToolUseResult> toolUseResults, List<MessageArtifact> artifacts) {
        Objects.requireNonNull(contentBlocks, "Content blocks cannot be null");
        validateNoNullElements(contentBlocks, "Content blocks must not contain null elements");
        return new Message(role, contentBlocks, toolUses, toolUseResults, artifacts);
    }

    /**
     * Gets the message role.
     *
     * @return The role (never null)
     */
    public Role getRole() {
        return role;
    }

    /**
     * Gets the text content of this message.
     *
     * <p>
     * Derived from text content blocks by joining their text representations. For backward compatibility, this returns
     * the concatenated text of all {@link TextContentBlock} instances.
     *
     * @return The text content (never null, may be empty)
     */
    public String getContent() {
        return textContent;
    }

    /**
     * Gets the content blocks of this message.
     *
     * @return An immutable list of content blocks (never null, may be empty)
     */
    public List<ContentBlock> getContentBlocks() {
        return contentBlocks;
    }

    /**
     * Checks if this message has content blocks.
     *
     * @return true if there are content blocks, false otherwise
     */
    public boolean hasContentBlocks() {
        return !contentBlocks.isEmpty();
    }

    /**
     * Checks if this message has non-text content blocks (e.g., images, documents).
     *
     * @return true if there are non-text content blocks, false otherwise
     */
    public boolean hasNonTextContentBlocks() {
        return hasNonTextContent;
    }

    /**
     * Gets the tool uses.
     *
     * @return An immutable list of tool uses (never null, may be empty)
     */
    public List<ToolUse> getToolUses() {
        return toolUses;
    }

    /**
     * Gets the tool results.
     *
     * @return An immutable list of tool results (never null, may be empty)
     */
    public List<ToolUseResult> getToolUseResults() {
        return toolUseResults;
    }

    /**
     * Checks if this message has tool uses.
     *
     * @return true if there are tool uses, false otherwise
     */
    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    /**
     * Checks if this message has tool results.
     *
     * @return true if there are tool results, false otherwise
     */
    public boolean hasToolResults() {
        return !toolUseResults.isEmpty();
    }

    /**
     * Gets the file artifacts produced by tool execution.
     *
     * <p>
     * Artifacts are metadata references to files generated during agent execution. Each artifact's
     * {@link MessageArtifact#getToolUseId()} links it to the specific tool use that created it.
     *
     * @return An immutable list of file artifacts (never null, may be empty)
     */
    public List<MessageArtifact> getArtifacts() {
        return artifacts;
    }

    /**
     * Checks if this message has file artifacts.
     *
     * @return true if there are file artifacts, false otherwise
     */
    public boolean hasArtifacts() {
        return !artifacts.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Message message = (Message) o;
        return role == message.role && contentBlocks.equals(message.contentBlocks) && toolUses.equals(message.toolUses)
                && toolUseResults.equals(message.toolUseResults) && artifacts.equals(message.artifacts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, contentBlocks, toolUses, toolUseResults, artifacts);
    }

    @Override
    public String toString() {
        final String preview = textContent.length() <= CONTENT_PREVIEW_MAX_LENGTH
                ? textContent
                : textContent.substring(0, CONTENT_PREVIEW_MAX_LENGTH) + "...";
        return "Message{role=" + role + ", content='" + preview + "'" + ", contentBlocks=" + contentBlocks.size()
                + ", toolUses=" + toolUses.size() + ", toolUseResults=" + toolUseResults.size() + ", artifacts="
                + artifacts.size() + '}';
    }

    private static <T> void validateNoNullElements(List<T> list, String message) {
        for (T element : list) {
            Objects.requireNonNull(element, message);
        }
    }
}
