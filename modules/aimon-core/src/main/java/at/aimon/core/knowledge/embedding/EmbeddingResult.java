package at.aimon.core.knowledge.embedding;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable result of an embedding operation.
 *
 * <p>
 * Contains the embedding vector and token usage information. The vector is defensively copied on construction and
 * access to ensure immutability.
 *
 * <pre>{@code
 * EmbeddingResult result = EmbeddingResult.builder()
 *         .vector(new float[]{0.1f, 0.2f, 0.3f})
 *         .tokenCount(5)
 *         .build();
 * }</pre>
 *
 * @see EmbeddingClient
 */
public final class EmbeddingResult {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final float[] vector;
    private final int tokenCount;

    private EmbeddingResult(Builder builder) {
        Objects.requireNonNull(builder.vector, "vector must not be null");
        if (builder.vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        if (builder.tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must be >= 0, got: " + builder.tokenCount);
        }
        this.vector = Arrays.copyOf(builder.vector, builder.vector.length);
        this.tokenCount = builder.tokenCount;
    }

    /**
     * Returns a copy of the embedding vector.
     *
     * @return a defensive copy of the vector (never null or empty)
     */
    public float[] getVector() {
        return Arrays.copyOf(vector, vector.length);
    }

    /**
     * Returns the number of dimensions in the embedding vector.
     *
     * @return the vector length (> 0)
     */
    public int getDimensions() {
        return vector.length;
    }

    /**
     * Returns the number of tokens consumed to generate this embedding.
     *
     * @return the token count (>= 0)
     */
    public int getTokenCount() {
        return tokenCount;
    }

    @Override
    public String toString() {
        return "EmbeddingResult{dimensions=" + vector.length + ", tokenCount=" + tokenCount + '}';
    }

    /**
     * Builder for {@link EmbeddingResult}.
     */
    public static final class Builder {
        private float[] vector;
        private int tokenCount;

        private Builder() {
        }

        /**
         * Sets the embedding vector.
         *
         * @param vector
         *            the embedding vector (must not be null or empty)
         * @return this builder
         */
        public Builder vector(float[] vector) {
            this.vector = vector;
            return this;
        }

        /**
         * Sets the token count consumed for this embedding.
         *
         * @param tokenCount
         *            the token count (must be >= 0)
         * @return this builder
         */
        public Builder tokenCount(int tokenCount) {
            this.tokenCount = tokenCount;
            return this;
        }

        /**
         * Builds the embedding result.
         *
         * @return a new {@link EmbeddingResult} instance
         * @throws NullPointerException
         *             if vector is null
         * @throws IllegalArgumentException
         *             if vector is empty or tokenCount is negative
         */
        public EmbeddingResult build() {
            return new EmbeddingResult(this);
        }
    }
}
