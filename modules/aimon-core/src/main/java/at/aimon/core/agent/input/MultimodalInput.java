package at.aimon.core.agent.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents multimodal user input combining multiple input types.
 *
 * <p>
 * This input type allows combining text, images, audio, and other input types for advanced multimodal AI interactions.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     UserInput input = MultimodalInput.of(TextInput.of("What's in this image?"),
 *             ImageInput.of(imageData, "image/png"));
 *
 *     // Or using builder
 *     UserInput input = MultimodalInput.builder().add(TextInput.of("Describe this"))
 *             .add(ImageInput.of(image1, "image/png")).add(ImageInput.of(image2, "image/png")).build();
 * }
 * </pre>
 */
public final class MultimodalInput implements UserInput {
    private final List<UserInput> inputs;

    /**
     * Creates a new MultimodalInput.
     *
     * @param inputs
     *            The list of inputs (must not be null or empty)
     * @throws NullPointerException
     *             if inputs is null
     * @throws IllegalArgumentException
     *             if inputs is empty
     */
    private MultimodalInput(List<UserInput> inputs) {
        Objects.requireNonNull(inputs, "Inputs cannot be null");
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Inputs cannot be empty");
        }
        this.inputs = List.copyOf(inputs);
    }

    /**
     * Creates a new MultimodalInput with the given inputs.
     *
     * @param inputs
     *            The inputs (must not be null or empty)
     * @return A new MultimodalInput
     * @throws NullPointerException
     *             if inputs is null
     * @throws IllegalArgumentException
     *             if inputs is empty
     */
    public static MultimodalInput of(UserInput... inputs) {
        return new MultimodalInput(List.of(inputs));
    }

    /**
     * Creates a new MultimodalInput with the given inputs.
     *
     * @param inputs
     *            The list of inputs (must not be null or empty)
     * @return A new MultimodalInput
     * @throws NullPointerException
     *             if inputs is null
     * @throws IllegalArgumentException
     *             if inputs is empty
     */
    public static MultimodalInput of(List<UserInput> inputs) {
        return new MultimodalInput(inputs);
    }

    /**
     * Creates a new builder.
     *
     * @return A new MultimodalInput.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public InputType getType() {
        return InputType.MULTIMODAL;
    }

    @Override
    public String asText() {
        return inputs.stream().map(UserInput::asText).collect(Collectors.joining(" "));
    }

    /**
     * Gets all inputs.
     *
     * @return An immutable list of inputs (never null or empty)
     */
    public List<UserInput> getInputs() {
        return inputs;
    }

    /**
     * Gets inputs of a specific type.
     *
     * @param type
     *            The input type to filter
     * @return A list of inputs of the specified type
     */
    public List<UserInput> getInputsOfType(InputType type) {
        return inputs.stream().filter(input -> type == input.getType()).toList();
    }

    /**
     * Checks if this multimodal input contains a specific type.
     *
     * @param type
     *            The input type to check
     * @return true if contains the type, false otherwise
     */
    public boolean hasType(InputType type) {
        return inputs.stream().anyMatch(input -> type == input.getType());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MultimodalInput that = (MultimodalInput) o;
        return inputs.equals(that.inputs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inputs);
    }

    @Override
    public String toString() {
        return "MultimodalInput{" + "inputs=" + inputs.size() + ", types="
                + inputs.stream().map(input -> input.getType().name()).collect(Collectors.joining(", ")) + '}';
    }

    /** Builder for MultimodalInput. */
    public static final class Builder {
        private final List<UserInput> inputs = new ArrayList<>();

        private Builder() {
        }

        /**
         * Adds an input.
         *
         * @param input
         *            The input to add (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if input is null
         */
        public Builder add(UserInput input) {
            this.inputs.add(Objects.requireNonNull(input, "Input cannot be null"));
            return this;
        }

        /**
         * Adds multiple inputs.
         *
         * @param inputs
         *            The inputs to add
         * @return This builder
         */
        public Builder addAll(UserInput... inputs) {
            for (UserInput input : inputs) {
                add(input);
            }
            return this;
        }

        /**
         * Adds multiple inputs.
         *
         * @param inputs
         *            The inputs to add
         * @return This builder
         */
        public Builder addAll(List<UserInput> inputs) {
            for (UserInput input : inputs) {
                add(input);
            }
            return this;
        }

        /**
         * Builds the MultimodalInput.
         *
         * @return A new MultimodalInput
         * @throws IllegalArgumentException
         *             if no inputs were added
         */
        public MultimodalInput build() {
            return new MultimodalInput(inputs);
        }
    }
}
