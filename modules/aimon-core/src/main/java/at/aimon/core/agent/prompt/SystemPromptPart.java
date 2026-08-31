package at.aimon.core.agent.prompt;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable fragment of a structured system prompt.
 *
 * <p>
 * A {@code SystemPromptPart} carries one chunk of system-prompt text together with metadata that lets downstream
 * components decide how to serialise or cache the chunk. The metadata comprises:
 *
 * <ul>
 * <li>{@link #getContent() content} — the non-empty text contributed to the final system prompt
 * <li>{@link #getStaticness() staticness} — how often the content is expected to change
 * <li>{@link #getKind() kind} — a short identifier (e.g., {@code "agent-instructions"}, {@code "user-context"},
 * {@code "tool-registry"}) used for diagnostics and adapter routing
 * <li>{@link #getCacheHintKey() cacheHintKey} — an optional provider-agnostic label that LLM adapters may translate
 * into provider-specific cache markers
 * </ul>
 *
 * <p>
 * Instances are immutable and thread-safe. Construct them via {@link #builder()}.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     SystemPromptPart instructions = SystemPromptPart.builder().content("You are a helpful assistant...")
 *             .staticness(Staticness.STATIC).kind("agent-instructions").cacheHintKey("agent:v1").build();
 * }
 * </pre>
 *
 * @see Staticness
 * @see SystemPromptParts
 */
public final class SystemPromptPart {

    private final String content;
    private final Staticness staticness;
    private final String kind;
    private final String cacheHintKey;

    private SystemPromptPart(Builder builder) {
        this.content = Objects.requireNonNull(builder.content, "Content cannot be null");
        if (this.content.isEmpty()) {
            throw new IllegalArgumentException("Content cannot be empty");
        }
        this.staticness = Objects.requireNonNull(builder.staticness, "Staticness cannot be null");
        this.kind = Objects.requireNonNull(builder.kind, "Kind cannot be null");
        if (this.kind.isEmpty()) {
            throw new IllegalArgumentException("Kind cannot be empty");
        }
        // cacheHintKey is nullable by design; surfaced as Optional in the accessor.
        this.cacheHintKey = builder.cacheHintKey;
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the text content that this part contributes to the final system prompt.
     *
     * @return the non-null, non-empty content
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the staticness classification of this part.
     *
     * @return the non-null {@link Staticness}
     */
    public Staticness getStaticness() {
        return staticness;
    }

    /**
     * Returns the {@code kind} identifier for this part.
     *
     * <p>
     * Kinds are short, conventional labels (for example, {@code "agent-instructions"}, {@code "user-context"},
     * {@code "tool-registry"}) used for diagnostics and for routing decisions in provider adapters.
     *
     * @return the non-null, non-empty kind
     */
    public String getKind() {
        return kind;
    }

    /**
     * Returns the provider-agnostic cache hint key, if any.
     *
     * <p>
     * When present, LLM provider adapters may translate this value into provider-specific cache markers
     * (e.g., Anthropic {@code cache_control.id} or an OpenAI prompt-caching boundary). When empty, adapters must
     * not attach any cache marker on behalf of this part.
     *
     * @return an {@link Optional} holding the cache hint key; never {@code null}
     */
    public Optional<String> getCacheHintKey() {
        return Optional.ofNullable(cacheHintKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SystemPromptPart that = (SystemPromptPart) o;
        return content.equals(that.content) && staticness == that.staticness && kind.equals(that.kind)
                && Objects.equals(cacheHintKey, that.cacheHintKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, staticness, kind, cacheHintKey);
    }

    @Override
    public String toString() {
        String contentPreview = content.length() > 50 ? content.substring(0, 50) + "..." : content;
        String cacheHintKeyRendered = cacheHintKey == null ? "<none>" : "'" + cacheHintKey + "'";
        return "SystemPromptPart{kind='" + kind + "', staticness=" + staticness + ", cacheHintKey="
                + cacheHintKeyRendered + ", content='" + contentPreview + "'}";
    }

    /** Builder for {@link SystemPromptPart}. */
    public static final class Builder {
        private String content;
        private Staticness staticness;
        private String kind;
        private String cacheHintKey;

        private Builder() {
        }

        /**
         * Sets the text content for this part.
         *
         * @param content
         *            the non-null, non-empty content
         * @return this builder
         * @throws NullPointerException
         *             if {@code content} is {@code null}
         */
        public Builder content(String content) {
            this.content = Objects.requireNonNull(content, "Content cannot be null");
            return this;
        }

        /**
         * Sets the staticness classification.
         *
         * @param staticness
         *            the non-null {@link Staticness}
         * @return this builder
         * @throws NullPointerException
         *             if {@code staticness} is {@code null}
         */
        public Builder staticness(Staticness staticness) {
            this.staticness = Objects.requireNonNull(staticness, "Staticness cannot be null");
            return this;
        }

        /**
         * Sets the short {@code kind} identifier.
         *
         * @param kind
         *            the non-null, non-empty kind
         * @return this builder
         * @throws NullPointerException
         *             if {@code kind} is {@code null}
         */
        public Builder kind(String kind) {
            this.kind = Objects.requireNonNull(kind, "Kind cannot be null");
            return this;
        }

        /**
         * Sets the optional provider-agnostic cache hint key.
         *
         * <p>
         * Passing {@code null} clears any previously set value.
         *
         * @param cacheHintKey
         *            the cache hint key, or {@code null} to leave unset
         * @return this builder
         */
        public Builder cacheHintKey(String cacheHintKey) {
            this.cacheHintKey = cacheHintKey;
            return this;
        }

        /**
         * Builds a new {@link SystemPromptPart}.
         *
         * @return the built part
         * @throws NullPointerException
         *             if {@code content}, {@code staticness}, or {@code kind} is {@code null}
         * @throws IllegalArgumentException
         *             if {@code content} or {@code kind} is empty
         */
        public SystemPromptPart build() {
            return new SystemPromptPart(this);
        }
    }
}
