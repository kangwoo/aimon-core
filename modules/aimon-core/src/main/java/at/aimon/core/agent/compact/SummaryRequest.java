package at.aimon.core.agent.compact;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
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
    private final Environment environment;
    private final String customInstructions;
    private final LlmCallMetadata callMetadata;

    private SummaryRequest(Builder builder) {
        this.messages = List.copyOf(Objects.requireNonNull(builder.messages, "messages cannot be null"));
        this.systemPrompt = Objects.requireNonNullElse(builder.systemPrompt, "");
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId cannot be null");
        this.executionId = builder.executionId;
        this.trigger = Objects.requireNonNull(builder.trigger, "trigger cannot be null");
        this.model = Objects.requireNonNull(builder.model, "model cannot be null");
        this.hookRegistry = Objects.requireNonNull(builder.hookRegistry, "hookRegistry cannot be null");
        this.environment = Objects.requireNonNull(builder.environment, "environment cannot be null");
        this.customInstructions = builder.customInstructions;
        this.callMetadata = builder.callMetadata;
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

    public Environment getEnvironment() {
        return environment;
    }

    public Optional<String> getCustomInstructions() {
        return Optional.ofNullable(customInstructions);
    }

    /** Same meaning as {@link CompactionRequest#getCallMetadata()}. */
    public Optional<LlmCallMetadata> getCallMetadata() {
        return Optional.ofNullable(callMetadata);
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
        private Environment environment;
        private String customInstructions;
        private LlmCallMetadata callMetadata;

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

        public Builder environment(Environment environment) {
            this.environment = environment;
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

        public SummaryRequest build() {
            return new SummaryRequest(this);
        }
    }
}
