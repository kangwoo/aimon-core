package at.aimon.core.agent.context;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An immutable unit of assembled runtime context destined for the conversation.
 *
 * <p>
 * A block pairs a {@link ContextBlockKind kind} (where it should be injected) with a stable {@code key} (used as the
 * {@code <system-reminder>} tag or the {@code SystemPromptPart} kind label) and a {@code body} (the text itself). The
 * {@code cacheable} flag hints whether the block is stable enough to sit in the prompt-cache-friendly prefix; dynamic
 * blocks belong at the tail.
 *
 * <p>
 * The {@code key} must match {@code [A-Za-z0-9._-]+} so it can be used verbatim as a
 * {@link at.aimon.core.agent.prompt.SystemReminderFormatter} reminder key without further escaping. The {@code body}
 * may be empty but never {@code null}.
 *
 * <p>
 * Instances are immutable and thread-safe.
 */
public final class ContextBlock {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private final ContextBlockKind kind;
    private final String key;
    private final String body;
    private final boolean cacheable;

    private ContextBlock(Builder builder) {
        this.kind = Objects.requireNonNull(builder.kind, "kind must not be null");
        this.key = Objects.requireNonNull(builder.key, "key must not be null");
        this.body = Objects.requireNonNull(builder.body, "body must not be null");
        this.cacheable = builder.cacheable;
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "key must match [A-Za-z0-9._-]+ (alphanumeric, '.', '_', '-'), got: " + key);
        }
    }

    /**
     * Creates a {@link ContextBlockKind#SYSTEM SYSTEM} block. Cacheable by default, since system-prompt context is
     * typically stable across a session.
     *
     * @param key
     *            the reminder/label key (must match {@code [A-Za-z0-9._-]+})
     * @param body
     *            the block text (may be empty, must not be null)
     * @return a new SYSTEM block
     */
    public static ContextBlock system(String key, String body) {
        return builder().kind(ContextBlockKind.SYSTEM).key(key).body(body).cacheable(true).build();
    }

    /**
     * Creates a {@link ContextBlockKind#USER_PREPEND USER_PREPEND} block. Not cacheable by default.
     *
     * @param key
     *            the reminder key (must match {@code [A-Za-z0-9._-]+})
     * @param body
     *            the block text (may be empty, must not be null)
     * @return a new USER_PREPEND block
     */
    public static ContextBlock userPrepend(String key, String body) {
        return builder().kind(ContextBlockKind.USER_PREPEND).key(key).body(body).cacheable(false).build();
    }

    /**
     * Creates a {@link ContextBlockKind#ATTACHMENT ATTACHMENT} block. Never cacheable.
     *
     * @param key
     *            the reminder key (must match {@code [A-Za-z0-9._-]+})
     * @param body
     *            the block text (may be empty, must not be null)
     * @return a new ATTACHMENT block
     */
    public static ContextBlock attachment(String key, String body) {
        return builder().kind(ContextBlockKind.ATTACHMENT).key(key).body(body).cacheable(false).build();
    }

    /**
     * @return the injection kind (never null)
     */
    public ContextBlockKind getKind() {
        return kind;
    }

    /**
     * @return the reminder/label key (never null, non-empty)
     */
    public String getKey() {
        return key;
    }

    /**
     * @return the block text (never null, may be empty)
     */
    public String getBody() {
        return body;
    }

    /**
     * @return {@code true} when the block is stable enough for the prompt-cache-friendly prefix
     */
    public boolean isCacheable() {
        return cacheable;
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ContextBlock} instances. */
    public static final class Builder {
        private ContextBlockKind kind;
        private String key;
        private String body;
        private boolean cacheable;

        private Builder() {
        }

        /**
         * @param kind
         *            the injection kind
         * @return this builder
         */
        public Builder kind(ContextBlockKind kind) {
            this.kind = kind;
            return this;
        }

        /**
         * @param key
         *            the reminder/label key
         * @return this builder
         */
        public Builder key(String key) {
            this.key = key;
            return this;
        }

        /**
         * @param body
         *            the block text
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * @param cacheable
         *            whether the block is prompt-cache friendly
         * @return this builder
         */
        public Builder cacheable(boolean cacheable) {
            this.cacheable = cacheable;
            return this;
        }

        /**
         * @return the built block
         * @throws NullPointerException
         *             if kind, key, or body is null
         * @throws IllegalArgumentException
         *             if the key is empty or violates {@code [A-Za-z0-9._-]+}
         */
        public ContextBlock build() {
            return new ContextBlock(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ContextBlock that = (ContextBlock) o;
        return cacheable == that.cacheable && kind == that.kind && key.equals(that.key) && body.equals(that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, key, body, cacheable);
    }

    @Override
    public String toString() {
        return "ContextBlock{kind=" + kind + ", key='" + key + '\'' + ", cacheable=" + cacheable + ", bodyChars="
                + body.length() + '}';
    }
}
