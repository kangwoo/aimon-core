package at.aimon.core.agent.context;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;

/**
 * Input to every {@link ContextEngine} call: the transcript the view is computed from and what the engine needs to
 * decide how to shrink it.
 *
 * <p>
 * {@link #getHookRegistry()} and {@link #getEnvironment()} are optional because not every loop has them &mdash; the
 * skill loop runs on a scratch buffer with neither. An engine that compacts fires PreCompact / PostCompact hooks and so
 * needs both; {@link DefaultContextEngine} refuses a request without them, while {@link ContextEngine#passthrough()}
 * never reads them.
 *
 * <p>
 * Immutable value object built via {@link Builder}.
 */
public final class ContextRequest {

    private final TranscriptBuffer transcriptBuffer;
    private final String systemPrompt;
    private final LlmModel model;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final ContextCaller caller;
    private final boolean budgetForced;
    private final LlmCallMetadata callMetadata;

    private ContextRequest(Builder builder) {
        this.transcriptBuffer = Objects.requireNonNull(builder.transcriptBuffer, "transcriptBuffer cannot be null");
        this.systemPrompt = builder.systemPrompt != null ? builder.systemPrompt : transcriptBuffer.getSystemPrompt();
        this.model = Objects.requireNonNull(builder.model, "model cannot be null");
        this.hookRegistry = builder.hookRegistry;
        this.environment = builder.environment;
        this.caller = builder.caller != null ? builder.caller : ContextCaller.session();
        this.budgetForced = builder.budgetForced;
        this.callMetadata = builder.callMetadata;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The transcript the view is computed from. */
    public TranscriptBuffer getTranscriptBuffer() {
        return transcriptBuffer;
    }

    /**
     * The system prompt sent with the view. Engines count it in every size comparison, because the provider does.
     * Defaults to the transcript buffer's system prompt.
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** The model the next call goes to &mdash; drives threshold resolution. */
    public LlmModel getModel() {
        return model;
    }

    public Optional<HookRegistry> getHookRegistry() {
        return Optional.ofNullable(hookRegistry);
    }

    public Optional<Environment> getEnvironment() {
        return Optional.ofNullable(environment);
    }

    /** Who the call is made on behalf of. Never null; defaults to {@link ContextCaller#session()}. */
    public ContextCaller getCaller() {
        return caller;
    }

    /**
     * Whether a soft budget hint asked for proactive compaction. An engine that honours it lowers its effective
     * compaction trigger to the warning band for this call.
     */
    public boolean isBudgetForced() {
        return budgetForced;
    }

    /**
     * Caller-supplied attribution for any LLM call the engine makes on the caller's behalf (a summary, typically).
     * The engine merges it with its own defaults so caller fields win on overlap.
     */
    public Optional<LlmCallMetadata> getCallMetadata() {
        return Optional.ofNullable(callMetadata);
    }

    /** Builder for {@link ContextRequest}. */
    public static final class Builder {
        private TranscriptBuffer transcriptBuffer;
        private String systemPrompt;
        private LlmModel model;
        private HookRegistry hookRegistry;
        private Environment environment;
        private ContextCaller caller;
        private boolean budgetForced;
        private LlmCallMetadata callMetadata;

        private Builder() {
        }

        public Builder transcriptBuffer(TranscriptBuffer transcriptBuffer) {
            this.transcriptBuffer = transcriptBuffer;
            return this;
        }

        /**
         * @param systemPrompt
         *            the system prompt sent with the view, or {@code null} to use the transcript buffer's
         * @return this builder
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
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

        /**
         * @param caller
         *            who the call is made on behalf of, or {@code null} for {@link ContextCaller#session()}
         * @return this builder
         */
        public Builder caller(ContextCaller caller) {
            this.caller = caller;
            return this;
        }

        public Builder budgetForced(boolean budgetForced) {
            this.budgetForced = budgetForced;
            return this;
        }

        public Builder callMetadata(LlmCallMetadata callMetadata) {
            this.callMetadata = callMetadata;
            return this;
        }

        public ContextRequest build() {
            return new ContextRequest(this);
        }
    }
}
