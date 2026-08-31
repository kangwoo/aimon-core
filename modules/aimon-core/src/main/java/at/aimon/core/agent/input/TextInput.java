package at.aimon.core.agent.input;

import java.util.Objects;

/**
 * Represents text-based user input.
 *
 * <p>
 * This is the most common form of user input for conversational agents.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     UserInput input = TextInput.of("What files are in the current directory?");
 *     String text = input.asText(); // "What files are in the current directory?"
 * }
 * </pre>
 */
public final class TextInput implements UserInput {
    private final String text;

    /**
     * Creates a new TextInput.
     *
     * @param text
     *            The text content (must not be null)
     * @throws NullPointerException
     *             if text is null
     */
    private TextInput(String text) {
        this.text = Objects.requireNonNull(text, "Text cannot be null");
    }

    /**
     * Creates a new TextInput with the given text.
     *
     * @param text
     *            The text content (must not be null)
     * @return A new TextInput
     * @throws NullPointerException
     *             if text is null
     */
    public static TextInput of(String text) {
        return new TextInput(text);
    }

    @Override
    public InputType getType() {
        return InputType.TEXT;
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
        TextInput textInput = (TextInput) o;
        return text.equals(textInput.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        return "TextInput{" + "text='" + (text.length() > 50 ? text.substring(0, 50) + "..." : text) + '\'' + '}';
    }
}
