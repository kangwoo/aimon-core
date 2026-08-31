package at.aimon.core.llm.content;

import java.util.Objects;

/**
 * A content block containing text.
 *
 * <p>
 * This is the most common content block type, representing plain text content in a message.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     ContentBlock block = TextContentBlock.of("Hello, world!");
 *     String text = block.asText(); // "Hello, world!"
 * }
 * </pre>
 */
public final class TextContentBlock implements ContentBlock {
    private final String text;

    private TextContentBlock(String text) {
        this.text = Objects.requireNonNull(text, "Text cannot be null");
    }

    /**
     * Creates a new TextContentBlock with the given text.
     *
     * @param text
     *            The text content (must not be null)
     * @return A new TextContentBlock
     * @throws NullPointerException
     *             if text is null
     */
    public static TextContentBlock of(String text) {
        return new TextContentBlock(text);
    }

    @Override
    public String getType() {
        return "text";
    }

    @Override
    public String asText() {
        return text;
    }

    /**
     * Gets the text content.
     *
     * @return The text content (never null)
     */
    public String getText() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TextContentBlock that = (TextContentBlock) o;
        return text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        String preview = text.length() > 50 ? text.substring(0, 50) + "..." : text;
        return "TextContentBlock{text='" + preview + "'}";
    }
}
