package at.aimon.core.agent.input;

/**
 * Represents user input to an agent.
 *
 * <p>
 * This interface supports various input types including text, images, audio, and multimodal combinations for future
 * extensibility.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Text input
 *     UserInput textInput = TextInput.of("What files are here?");
 *
 *     // Image input
 *     UserInput imageInput = ImageInput.of(imageData, "image/png");
 *
 *     // Multimodal input
 *     UserInput multimodal = MultimodalInput.of(TextInput.of("What's in this image?"),
 *             ImageInput.of(imageData, "image/png"));
 * }
 * </pre>
 */
public sealed interface UserInput permits TextInput, ImageInput, AudioInput, FileInput, MultimodalInput {

    /**
     * Gets the type of this input.
     *
     * @return The input type (never null)
     */
    InputType getType();

    /**
     * Gets a text representation of this input.
     *
     * <p>
     * For text inputs, this returns the actual text content. For binary inputs (image, audio), this returns a
     * descriptive placeholder (e.g., {@code "[Image: image/png, 1024 bytes]"}). For multimodal inputs, this returns the
     * concatenation of all contained inputs' text representations.
     *
     * @return The text representation (never null)
     */
    String asText();
}
