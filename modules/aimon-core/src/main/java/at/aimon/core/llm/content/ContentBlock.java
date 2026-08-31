package at.aimon.core.llm.content;

/**
 * Represents a content block within an LLM message.
 *
 * <p>
 * Content blocks are the building blocks of multimodal messages, supporting text, images, and documents. Each block has
 * a type identifier and can produce a text representation.
 *
 * <p>
 * Implementations must be immutable and thread-safe.
 *
 * @see TextContentBlock
 * @see ImageContentBlock
 * @see DocumentContentBlock
 */
public interface ContentBlock {

    /**
     * Gets the type identifier of this content block.
     *
     * @return The type (e.g., "text", "image", "document")
     */
    String getType();

    /**
     * Gets a text representation of this content block.
     *
     * <p>
     * For text blocks, this returns the actual text content. For other types, this returns a descriptive placeholder.
     *
     * @return The text representation (never null)
     */
    String asText();
}
