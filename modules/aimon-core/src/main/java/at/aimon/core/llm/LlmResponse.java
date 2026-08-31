package at.aimon.core.llm;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a response from an LLM.
 *
 * <p>
 * A response can contain text content, tool uses, or both. The LLM may:
 *
 * <ul>
 * <li>Return only text (normal conversation)
 * <li>Return only tool uses (when it wants to call functions)
 * <li>Return both text and tool uses (explaining what it's doing)
 * </ul>
 *
 * <p>
 * Immutable value object with defensive copying for collections.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     LlmResponse response = LlmResponse.of("I'll check the git status for you.",
 *             List.of(ToolUse.of("tool_1", "bash", Map.of("command", "git status"))));
 *
 *     if (response.hasToolUses()) {
 *         for (ToolUse toolUse : response.getToolUses()) {
 *             // Execute the tool
 *         }
 *     }
 * }
 * </pre>
 */
public final class LlmResponse {
    /**
     * Creates a new LlmResponse.
     *
     * @param textContent
     *            The text content (can be null or empty)
     * @param toolUses
     *            The tool uses (must not be null, can be empty)
     * @param tokenUsage
     *            The token usage (can be null, defaults to empty)
     * @return A new LlmResponse
     * @throws NullPointerException
     *             if toolUses is null
     */
    public static LlmResponse of(String textContent, List<ToolUse> toolUses, TokenUsage tokenUsage) {
        return new LlmResponse(textContent, toolUses, tokenUsage, StopReason.UNKNOWN);
    }

    /**
     * Creates a new LlmResponse carrying the provider-neutral stop reason.
     *
     * <p>
     * Provider modules use this overload so {@code aimon-core} can detect a truncated turn ({@link
     * StopReason#MAX_TOKENS}) without knowing any provider's raw vocabulary. The other {@code of(...)} / {@link
     * #text(String)} / {@link #tools(List)} factories default the reason to {@link StopReason#UNKNOWN}, so existing
     * callers are unaffected.
     *
     * @param textContent
     *            The text content (can be null or empty)
     * @param toolUses
     *            The tool uses (must not be null, can be empty)
     * @param tokenUsage
     *            The token usage (can be null, defaults to empty)
     * @param stopReason
     *            The provider-neutral stop reason (must not be null; pass {@link StopReason#UNKNOWN} when absent)
     * @return A new LlmResponse
     * @throws NullPointerException
     *             if toolUses or stopReason is null
     */
    public static LlmResponse of(String textContent, List<ToolUse> toolUses, TokenUsage tokenUsage,
            StopReason stopReason) {
        return new LlmResponse(textContent, toolUses, tokenUsage, stopReason);
    }

    /**
     * Creates a new LlmResponse without token usage.
     *
     * @param textContent
     *            The text content (can be null or empty)
     * @param toolUses
     *            The tool uses (must not be null, can be empty)
     * @return A new LlmResponse
     * @throws NullPointerException
     *             if toolUses is null
     */
    public static LlmResponse of(String textContent, List<ToolUse> toolUses) {
        return new LlmResponse(textContent, toolUses, TokenUsage.empty(), StopReason.UNKNOWN);
    }

    /**
     * Creates a text-only response.
     *
     * @param textContent
     *            The text content (must not be null)
     * @return A new LlmResponse with no tool uses
     * @throws NullPointerException
     *             if textContent is null
     */
    public static LlmResponse text(String textContent) {
        Objects.requireNonNull(textContent, "Text content cannot be null");
        return new LlmResponse(textContent, List.of(), TokenUsage.empty(), StopReason.UNKNOWN);
    }

    /**
     * Creates a tool-only response.
     *
     * @param toolUses
     *            The tool uses (must not be null, must not be empty)
     * @return A new LlmResponse with no text content
     * @throws NullPointerException
     *             if toolUses is null
     * @throws IllegalArgumentException
     *             if toolUses is empty
     */
    public static LlmResponse tools(List<ToolUse> toolUses) {
        Objects.requireNonNull(toolUses, "Tool uses cannot be null");
        if (toolUses.isEmpty()) {
            throw new IllegalArgumentException("Tool uses cannot be empty");
        }
        return new LlmResponse("", toolUses, TokenUsage.empty(), StopReason.UNKNOWN);
    }

    private final String textContent;
    private final List<ToolUse> toolUses;
    private final TokenUsage tokenUsage;
    private final StopReason stopReason;

    /**
     * Creates a new LlmResponse.
     *
     * @param textContent
     *            The text content (can be null or empty)
     * @param toolUses
     *            The tool uses (must not be null, can be empty)
     * @param tokenUsage
     *            The token usage (can be null, defaults to empty)
     * @param stopReason
     *            The provider-neutral stop reason (must not be null; {@link StopReason#UNKNOWN} when absent)
     * @throws NullPointerException
     *             if toolUses or stopReason is null
     */
    private LlmResponse(String textContent, List<ToolUse> toolUses, TokenUsage tokenUsage, StopReason stopReason) {
        this.textContent = textContent == null ? "" : textContent;
        this.toolUses = List.copyOf(Objects.requireNonNull(toolUses, "Tool uses cannot be null"));
        this.tokenUsage = Objects.requireNonNullElse(tokenUsage, TokenUsage.empty());
        this.stopReason = Objects.requireNonNull(stopReason, "Stop reason cannot be null");
    }

    /**
     * Gets the text content.
     *
     * @return The text content (never null, may be empty)
     */
    public String getTextContent() {
        return textContent;
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
     * Checks if this response has text content.
     *
     * @return true if text content is not empty, false otherwise
     */
    public boolean hasTextContent() {
        return !textContent.isEmpty();
    }

    /**
     * Checks if this response has tool uses.
     *
     * @return true if there are tool uses, false otherwise
     */
    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    /**
     * Gets the token usage.
     *
     * @return The token usage (never null, may be empty)
     */
    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    /**
     * Checks if this response has token usage information.
     *
     * @return true if token usage is not empty, false otherwise
     */
    public boolean hasTokenUsage() {
        return !tokenUsage.equals(TokenUsage.empty());
    }

    /**
     * Gets the provider-neutral reason the LLM stopped generating.
     *
     * <p>
     * Returns {@link Optional#empty()} when the reason is {@link StopReason#UNKNOWN} (either the provider did not
     * report
     * one, or the response was built via a legacy factory that predates stop-reason capture). A present value can be
     * inspected via {@link StopReason#isTruncated()} to detect a {@code max_tokens} cut-off.
     *
     * @return the stop reason, or empty when unknown
     */
    public Optional<StopReason> getStopReason() {
        return stopReason == StopReason.UNKNOWN ? Optional.empty() : Optional.of(stopReason);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmResponse that = (LlmResponse) o;
        return textContent.equals(that.textContent) && toolUses.equals(that.toolUses)
                && tokenUsage.equals(that.tokenUsage) && stopReason == that.stopReason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(textContent, toolUses, tokenUsage, stopReason);
    }

    @Override
    public String toString() {
        return "LlmResponse{" + "textContent='" + textContent + "', " + "toolUses=" + toolUses.size() + " tool(s), "
                + "tokens=" + tokenUsage.getTotalTokens() + ", stopReason=" + stopReason + '}';
    }
}
