package at.aimon.core.agent.compact;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;

/**
 * Input parameters for {@link CompactionEngine#compact}.
 *
 * <p>
 * Immutable value object built via {@link Builder}.
 */
public final class CompactionRequest {

    private final TranscriptBuffer transcriptBuffer;
    private final CompactionTrigger trigger;
    private final LlmModel model;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final String customInstructions;
    private final boolean forced;
    private final LlmCallMetadata callMetadata;
    private final CompactionRange compactRange;
    private final ExecutionId executionId;

    private CompactionRequest(Builder builder) {
        this.transcriptBuffer = Objects.requireNonNull(builder.transcriptBuffer, "TranscriptBuffer cannot be null");
        this.trigger = Objects.requireNonNull(builder.trigger, "Trigger cannot be null");
        this.model = Objects.requireNonNull(builder.model, "Model cannot be null");
        this.hookRegistry = Objects.requireNonNull(builder.hookRegistry, "HookRegistry cannot be null");
        this.environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        this.customInstructions = builder.customInstructions;
        this.forced = builder.forced;
        this.callMetadata = builder.callMetadata;
        this.compactRange = builder.compactRange;
        this.executionId = builder.executionId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public TranscriptBuffer getTranscriptBuffer() {
        return transcriptBuffer;
    }

    public CompactionTrigger getTrigger() {
        return trigger;
    }

    public LlmModel getModel() {
        return model;
    }

    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public Optional<String> getCustomInstructions() {
        return Optional.ofNullable(customInstructions);
    }

    /**
     * Whether the caller (e.g. {@link CompactionGuard}) marked this request as forced because the blocking limit was
     * exceeded. Engines may ignore this hint, but it is forwarded to hooks for visibility.
     */
    public boolean isForced() {
        return forced;
    }

    /**
     * Returns optional caller-supplied {@link LlmCallMetadata} that the engine should attach to its summary LLM call.
     *
     * <p>
     * The engine still owns its own framework defaults (component, feature, traceId); when both are present they are
     * merged via {@link LlmCallMetadata#withDefaults(LlmCallMetadata)} so caller-supplied attribution (typically
     * {@code principal} for a user-driven {@code /compact}) wins on overlap and engine defaults fill the rest.
     */
    public Optional<LlmCallMetadata> getCallMetadata() {
        return Optional.ofNullable(callMetadata);
    }

    /**
     * Returns the optional sub-range of messages to summarize (design §4.3, partial compaction).
     *
     * <p>
     * When absent, the engine summarizes the entire conversation (current behavior). When present, only the in-range
     * messages are folded into the summary marker pair and the surrounding prefix/tail are preserved verbatim. The
     * engine validates the range against the actual conversation length and against tool_use/tool_result coherency
     * at the cut points, returning {@link CompactionResult#failure} on violation.
     */
    public Optional<CompactionRange> getCompactRange() {
        return Optional.ofNullable(compactRange);
    }

    /**
     * Returns the run identity to attribute this compaction to when the run has no session of its own &mdash; a
     * subagent fork, for instance.
     *
     * <p>
     * This is the identity channel the engine needs because it cannot get one from
     * {@link TranscriptBuffer#getSessionId()}: that field is typed on
     * {@link at.aimon.core.agent.session.SessionId} and a session-less run still has to label its buffer with
     * something, so what it holds may be a wrapped {@link ExecutionId} rather than a real session. Exporting that
     * label as a session id is the defect this field removes; the identity has to arrive here explicitly rather than
     * be inferred from the label's shape, which would couple the engine to whatever prefix the fork happens to use.
     *
     * @return the run's execution id, or empty for a compaction that happens inside a genuine session &mdash; in
     *         which case {@link #getTranscriptBuffer()}'s session id is the honest identity
     */
    public Optional<ExecutionId> getExecutionId() {
        return Optional.ofNullable(executionId);
    }

    /** Builder for {@link CompactionRequest}. */
    public static final class Builder {
        private TranscriptBuffer transcriptBuffer;
        private CompactionTrigger trigger;
        private LlmModel model;
        private HookRegistry hookRegistry;
        private Environment environment;
        private String customInstructions;
        private boolean forced;
        private LlmCallMetadata callMetadata;
        private CompactionRange compactRange;
        private ExecutionId executionId;

        private Builder() {
        }

        public Builder transcriptBuffer(TranscriptBuffer transcriptBuffer) {
            this.transcriptBuffer = transcriptBuffer;
            return this;
        }

        public Builder trigger(CompactionTrigger trigger) {
            this.trigger = trigger;
            return this;
        }

        public Builder model(LlmModel model) {
            this.model = model;
            return this;
        }

        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        public Builder customInstructions(String customInstructions) {
            this.customInstructions = customInstructions;
            return this;
        }

        public Builder forced(boolean forced) {
            this.forced = forced;
            return this;
        }

        /**
         * Optional caller-supplied {@link LlmCallMetadata}. Merged with the engine's framework defaults via
         * {@link LlmCallMetadata#withDefaults(LlmCallMetadata)} so caller fields (e.g. {@code principal}) take
         * precedence
         * while engine fields ({@code component}, {@code feature}, {@code traceId}) fill any gaps.
         *
         * @param callMetadata
         *            caller-supplied metadata, or {@code null} to defer entirely to engine defaults
         */
        public Builder callMetadata(LlmCallMetadata callMetadata) {
            this.callMetadata = callMetadata;
            return this;
        }

        /**
         * Restricts compaction to a sub-range of the conversation messages (Partial Compaction). When omitted or
         * {@code null}, the engine summarizes the entire conversation.
         *
         * @param compactRange
         *            the sub-range to summarize, or {@code null} for full compaction
         */
        public Builder compactRange(CompactionRange compactRange) {
            this.compactRange = compactRange;
            return this;
        }

        /**
         * Declares that this compaction belongs to a run with no session, and names that run.
         *
         * <p>
         * Leave unset for a compaction inside a genuine session; the engine then identifies the compaction by the
         * transcript buffer's session id, as it always has.
         *
         * @param executionId
         *            the run's execution id, or {@code null} for a session-backed compaction
         * @return this builder
         */
        public Builder executionId(ExecutionId executionId) {
            this.executionId = executionId;
            return this;
        }

        public CompactionRequest build() {
            return new CompactionRequest(this);
        }
    }
}
