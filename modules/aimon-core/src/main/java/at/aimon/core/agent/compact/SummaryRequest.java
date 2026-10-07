package at.aimon.core.agent.compact;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;

/**
 * Input parameters for {@link CompactionEngine#summarize}: the messages to fold into one summary, and nothing about
 * where that summary goes.
 *
 * <p>
 * Unlike {@link CompactionRequest} this carries no transcript buffer. The caller hands over exactly the messages to
 * summarize and decides itself what to do with the summary; the engine never rewrites a transcript for a summarize
 * call.
 *
 * <p>
 * Immutable value object built via {@link Builder}.
 */
public final class SummaryRequest {

    private final List<Message> messages;
    private final String systemPrompt;
    private final SessionId sessionId;
    private final ExecutionId executionId;
    private final CompactionTrigger trigger;
    private final LlmModel model;
    private final HookRegistry hookRegistry;
    private final ExecutionEnvironment executionEnvironment;
    private final CancellationSignal executionCancellation;
    private final String customInstructions;
    private final LlmCallMetadata callMetadata;
    private final boolean rolling;
    private final String previousSummary;
    private final int targetSummaryTokens;

    private SummaryRequest(Builder builder) {
        this.messages = List.copyOf(Objects.requireNonNull(builder.messages, "messages cannot be null"));
        this.systemPrompt = Objects.requireNonNullElse(builder.systemPrompt, "");
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId cannot be null");
        this.executionId = builder.executionId;
        this.trigger = Objects.requireNonNull(builder.trigger, "trigger cannot be null");
        this.model = Objects.requireNonNull(builder.model, "model cannot be null");
        this.hookRegistry = Objects.requireNonNull(builder.hookRegistry, "hookRegistry cannot be null");
        this.executionEnvironment = builder.executionEnvironment;
        this.executionCancellation = builder.executionCancellation;
        this.customInstructions = builder.customInstructions;
        this.callMetadata = builder.callMetadata;
        this.rolling = builder.rolling;
        this.previousSummary = builder.previousSummary == null || builder.previousSummary.isBlank()
                ? null
                : builder.previousSummary;
        this.targetSummaryTokens = builder.targetSummaryTokens;
        if (targetSummaryTokens < 0) {
            throw new IllegalArgumentException("targetSummaryTokens must be >= 0, got: " + targetSummaryTokens);
        }
        if (previousSummary != null && !rolling) {
            throw new IllegalArgumentException("a previous summary is only updated by a rolling summary");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The messages to summarize, in order. Immutable. */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * The system prompt of the conversation the messages come from. Used only to size them the way the provider does;
     * the summary call has its own system prompt. Empty when not supplied.
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * The transcript label the messages come from &mdash; the session id of a session, or the label a session-less run
     * gives its buffer. Attributes the summary call and, when {@link #getExecutionId()} is empty, identifies the
     * compaction to hooks.
     */
    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * The run to identify the compaction by when it has no session of its own. See
     * {@link CompactionRequest#getExecutionId()} for why the identity has to arrive explicitly.
     */
    public Optional<ExecutionId> getExecutionId() {
        return Optional.ofNullable(executionId);
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

    /**
     * The execution environment of the execution being compacted, carried into the PreCompact / PostCompact hook
     * contexts. Empty when the caller had none in reach.
     */
    public Optional<ExecutionEnvironment> getExecutionEnvironment() {
        return Optional.ofNullable(executionEnvironment);
    }

    /**
     * The cancellation signal of the execution being compacted. It is carried into the PreCompact / PostCompact hook
     * contexts so a hook's command stops when that execution is interrupted, and it bounds the summary LLM call: an
     * interrupt aborts the call in flight, and the result is a failure carrying
     * {@link at.aimon.core.llm.exception.LlmCallCancelledException}. Empty when no signal there can trip — a manual
     * {@code /compact}, a rewake replay — or the caller had none in reach.
     *
     * @return the compacting execution's cancellation signal, or empty when there is none
     */
    public Optional<CancellationSignal> getExecutionCancellation() {
        return Optional.ofNullable(executionCancellation);
    }

    public Optional<String> getCustomInstructions() {
        return Optional.ofNullable(customInstructions);
    }

    /** Same meaning as {@link CompactionRequest#getCallMetadata()}. */
    public Optional<LlmCallMetadata> getCallMetadata() {
        return Optional.ofNullable(callMetadata);
    }

    /**
     * Whether this summary is one generation of a rolling summary (context-engine §5.4): the ten sections with
     * {@code Key decisions and constraints} added, the cumulative sections kept, and a target length. {@code false} —
     * the default — is the nine-section summary a full compaction has always asked for.
     */
    public boolean isRolling() {
        return rolling;
    }

    /**
     * The summary this one updates, when a rolling span widens. The messages are then only what the span newly
     * absorbs, and the instruction is "update the previous summary", not "summarize". Empty for a first summary.
     */
    public Optional<String> getPreviousSummary() {
        return Optional.ofNullable(previousSummary);
    }

    /** The summary length the prompt asks for, in tokens. {@code 0} asks for none. Never enforced by a second call. */
    public int getTargetSummaryTokens() {
        return targetSummaryTokens;
    }

    /** Builder for {@link SummaryRequest}. */
    public static final class Builder {
        private List<Message> messages;
        private String systemPrompt;
        private SessionId sessionId;
        private ExecutionId executionId;
        private CompactionTrigger trigger;
        private LlmModel model;
        private HookRegistry hookRegistry;
        private ExecutionEnvironment executionEnvironment;
        private CancellationSignal executionCancellation;
        private String customInstructions;
        private LlmCallMetadata callMetadata;
        private boolean rolling;
        private String previousSummary;
        private int targetSummaryTokens;

        private Builder() {
        }

        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * @param executionId
         *            the session-less run's id, or {@code null} for a summary taken inside a genuine session
         * @return this builder
         */
        public Builder executionId(ExecutionId executionId) {
            this.executionId = executionId;
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

        public Builder executionEnvironment(ExecutionEnvironment executionEnvironment) {
            this.executionEnvironment = executionEnvironment;
            return this;
        }

        /**
         * @param executionCancellation
         *            the compacting execution's cancellation signal for the compaction hooks, or {@code null} when
         *            there is none
         * @return this builder
         */
        public Builder executionCancellation(CancellationSignal executionCancellation) {
            this.executionCancellation = executionCancellation;
            return this;
        }

        public Builder customInstructions(String customInstructions) {
            this.customInstructions = customInstructions;
            return this;
        }

        public Builder callMetadata(LlmCallMetadata callMetadata) {
            this.callMetadata = callMetadata;
            return this;
        }

        /**
         * @param rolling
         *            whether this is a rolling summary (see {@link SummaryRequest#isRolling()})
         * @return this builder
         */
        public Builder rolling(boolean rolling) {
            this.rolling = rolling;
            return this;
        }

        /**
         * @param previousSummary
         *            the summary to update, or {@code null} / blank for a first summary; requires {@link #rolling}
         * @return this builder
         */
        public Builder previousSummary(String previousSummary) {
            this.previousSummary = previousSummary;
            return this;
        }

        /**
         * @param targetSummaryTokens
         *            the length to ask for in tokens, {@code 0} for none (must be {@code >= 0})
         * @return this builder
         */
        public Builder targetSummaryTokens(int targetSummaryTokens) {
            this.targetSummaryTokens = targetSummaryTokens;
            return this;
        }

        public SummaryRequest build() {
            return new SummaryRequest(this);
        }
    }
}
