package at.aimon.core.agent.queue;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.SubmitOptions;

/**
 * Immutable envelope around a single piece of user (or sub-agent originated) input queued for mid-turn injection.
 *
 * <p>
 * Instances are safe to share across threads. Use {@link #builder()} to construct new values — the builder fills in
 * sensible defaults for the {@link #getUuid() uuid}, {@link #getPriority() priority} and {@link #getEnqueuedAt()
 * enqueuedAt} fields.
 *
 * <h2>Equality</h2>
 *
 * <p>
 * Equality and hashing are based solely on {@link #getUuid() uuid}. Two inputs with the same uuid are considered the
 * same logical queued message even if other fields differ, so callers can safely use {@link QueuedInput} instances in
 * {@link java.util.Set}s keyed by identity.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     QueuedInput input = QueuedInput.builder().inputText("read the readme").priority(QueuedInputPriority.NEXT)
 *             .agentRuntimeId(ctxId).metadata(Map.of("origin", "repl")).build();
 * }
 * </pre>
 */
public final class QueuedInput {

    private final UUID uuid;
    private final String inputText;
    private final QueuedInputPriority priority;
    private final AgentRuntimeId agentRuntimeId;
    private final Optional<String> sourceAgentId;
    private final Instant enqueuedAt;
    private final Map<String, String> metadata;
    private final SubmitOptions submitOptions;

    private QueuedInput(Builder builder) {
        this.uuid = Objects.requireNonNull(builder.uuid, "uuid cannot be null");
        this.inputText = Objects.requireNonNull(builder.inputText, "inputText cannot be null");
        if (this.inputText.isEmpty()) {
            throw new IllegalArgumentException("inputText cannot be empty");
        }
        this.priority = Objects.requireNonNull(builder.priority, "priority cannot be null");
        this.agentRuntimeId = Objects.requireNonNull(builder.agentRuntimeId, "agentRuntimeId cannot be null");
        this.sourceAgentId = Objects.requireNonNull(builder.sourceAgentId, "sourceAgentId cannot be null");
        this.enqueuedAt = Objects.requireNonNull(builder.enqueuedAt, "enqueuedAt cannot be null");
        final Map<String, String> copy = new HashMap<>(
                Objects.requireNonNull(builder.metadata, "metadata cannot be null"));
        this.metadata = Collections.unmodifiableMap(copy);
        this.submitOptions = Objects.requireNonNull(builder.submitOptions, "submitOptions cannot be null");
    }

    /**
     * Creates a new builder pre-populated with a random {@link #getUuid() uuid}, {@link QueuedInputPriority#NEXT} as
     * priority and the current wall-clock instant.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Stable identifier for this queued input.
     *
     * @return the uuid (never null)
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * The raw input text buffered for later injection.
     *
     * @return the input text (never null or empty)
     */
    public String getInputText() {
        return inputText;
    }

    /**
     * Priority tier governing when this input is consumed relative to others in the queue.
     *
     * @return the priority (never null)
     */
    public QueuedInputPriority getPriority() {
        return priority;
    }

    /**
     * Identifier of the agent runtime that should receive this input.
     *
     * <p>
     * Consumers typically filter the queue by this field to keep main-agent traffic isolated from sub-agent traffic.
     *
     * @return the agent runtime id (never null)
     */
    public AgentRuntimeId getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /**
     * Identifier of the source agent (for sub-agent to parent communication) if applicable.
     *
     * @return the source agent id, or empty if this input came from the REPL user
     */
    public Optional<String> getSourceAgentId() {
        return sourceAgentId;
    }

    /**
     * Wall-clock instant at which the input was enqueued. Useful for age-based filtering.
     *
     * @return the enqueue instant (never null)
     */
    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    /**
     * Free-form metadata attached by the producer. The returned map is unmodifiable and holds a defensive copy.
     *
     * <p>
     * Used for queue-operational tagging (idempotency keys, source origin) — not to be confused with
     * {@link #getSubmitOptions()}, which carries the per-turn executor metadata that is forwarded into the
     * {@code OrcaAgentExecutionRequest} when the input is eventually drained.
     *
     * @return the metadata (never null, may be empty)
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Per-turn {@link SubmitOptions} preserved on this queued input so that mid-turn drains keep the original
     * caller-supplied executor metadata (userInfo, system prompt variables, execution attributes, LLM call metadata,
     * user-context injection override).
     *
     * <p>
     * Defaults to {@link SubmitOptions#empty()} when the producer did not supply any per-turn metadata, which makes
     * the eventual {@code session.submitAsync(...)} call behave exactly like the pre-SubmitOptions path.
     *
     * @return the per-turn options (never null)
     */
    public SubmitOptions getSubmitOptions() {
        return submitOptions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        QueuedInput that = (QueuedInput) o;
        return uuid.equals(that.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        final int maxLen = 40;
        final String preview;
        if (inputText.length() > maxLen) {
            preview = inputText.substring(0, maxLen) + "…";
        } else {
            preview = inputText;
        }
        return "QueuedInput{" + "uuid=" + uuid + ", priority=" + priority + ", agentRuntimeId=" + agentRuntimeId
                + ", sourceAgentId=" + sourceAgentId.orElse(null) + ", enqueuedAt=" + enqueuedAt + ", inputText='"
                + preview + "'" + '}';
    }

    /** Builder for {@link QueuedInput}. */
    public static final class Builder {

        private UUID uuid = UUID.randomUUID();
        private String inputText;
        private QueuedInputPriority priority = QueuedInputPriority.NEXT;
        private AgentRuntimeId agentRuntimeId;
        private Optional<String> sourceAgentId = Optional.empty();
        private Instant enqueuedAt = Instant.now();
        private Map<String, String> metadata = Collections.emptyMap();
        private SubmitOptions submitOptions = SubmitOptions.empty();

        private Builder() {
        }

        /**
         * Overrides the auto-generated uuid.
         *
         * @param uuid
         *            the uuid (must not be null)
         * @return this builder
         */
        public Builder uuid(UUID uuid) {
            this.uuid = Objects.requireNonNull(uuid, "uuid cannot be null");
            return this;
        }

        /**
         * Sets the raw input text.
         *
         * @param inputText
         *            the text (must not be null or empty)
         * @return this builder
         */
        public Builder inputText(String inputText) {
            this.inputText = Objects.requireNonNull(inputText, "inputText cannot be null");
            return this;
        }

        /**
         * Sets the priority tier. Defaults to {@link QueuedInputPriority#NEXT} if not called.
         *
         * @param priority
         *            the priority (must not be null)
         * @return this builder
         */
        public Builder priority(QueuedInputPriority priority) {
            this.priority = Objects.requireNonNull(priority, "priority cannot be null");
            return this;
        }

        /**
         * Sets the target agent runtime id.
         *
         * @param agentRuntimeId
         *            the agent runtime id (must not be null)
         * @return this builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
            return this;
        }

        /**
         * Sets the optional source agent id. Pass {@code null} or do not call this method for REPL-originated inputs.
         *
         * @param sourceAgentId
         *            the source agent id, or {@code null} if this is a REPL user input
         * @return this builder
         */
        public Builder sourceAgentId(String sourceAgentId) {
            this.sourceAgentId = Optional.ofNullable(sourceAgentId);
            return this;
        }

        /**
         * Overrides the default enqueue instant ({@link Instant#now()} captured at builder creation time).
         *
         * @param enqueuedAt
         *            the instant (must not be null)
         * @return this builder
         */
        public Builder enqueuedAt(Instant enqueuedAt) {
            this.enqueuedAt = Objects.requireNonNull(enqueuedAt, "enqueuedAt cannot be null");
            return this;
        }

        /**
         * Sets the metadata map. The builder takes a defensive copy at {@link #build()} time, so mutating the map
         * afterwards does not affect the constructed {@link QueuedInput}.
         *
         * @param metadata
         *            the metadata (must not be null; may be empty)
         * @return this builder
         */
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
            return this;
        }

        /**
         * Sets the per-turn {@link SubmitOptions} to be re-applied when this input is eventually drained back into a
         * session submit. Defaults to {@link SubmitOptions#empty()} when not called.
         *
         * @param submitOptions
         *            the options (must not be null; pass {@link SubmitOptions#empty()} explicitly for "no override")
         * @return this builder
         */
        public Builder submitOptions(SubmitOptions submitOptions) {
            this.submitOptions = Objects.requireNonNull(submitOptions, "submitOptions cannot be null");
            return this;
        }

        /**
         * Builds the {@link QueuedInput}.
         *
         * @return a new {@link QueuedInput}
         * @throws NullPointerException
         *             if any required field is missing
         * @throws IllegalArgumentException
         *             if {@code inputText} is empty
         */
        public QueuedInput build() {
            return new QueuedInput(this);
        }
    }
}
