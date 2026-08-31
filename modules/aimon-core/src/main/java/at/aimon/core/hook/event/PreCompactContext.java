package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;

/**
 * Context for {@link PreCompactHook}.
 *
 * <p>
 * Provides information about a compaction that is about to be performed, allowing hooks to inject custom summary
 * instructions or block the compaction outright.
 *
 * <p>
 * Not every firing has a session behind it. A rewake replay drives this chain without a session, so
 * {@link #getSessionIdValue()} is empty there and {@link #getExecutionId()} carries the correlation id instead.
 *
 * <p>
 * Immutable value object. Use builder to create instances.
 */
public final class PreCompactContext implements HookContext {

    public static Builder builder() {
        return new Builder();
    }

    private final InvokerType invokerType;
    private final String invokerName;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final CompactionTrigger trigger;
    private final String sessionIdValue;
    private final ExecutionId executionId;
    private final int messageCount;
    private final int estimatedTokens;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private PreCompactContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Invoker type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Invoker name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        trigger = Objects.requireNonNull(builder.trigger, "Trigger cannot be null");
        sessionIdValue = builder.sessionIdValue != null ? builder.sessionIdValue : "";
        executionId = builder.executionId;
        messageCount = builder.messageCount;
        estimatedTokens = builder.estimatedTokens;
        timestamp = Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null");
        executionAttributes = builder.executionAttributes != null ? Map.copyOf(builder.executionAttributes) : Map.of();
    }

    @Override
    public InvokerType getInvokerType() {
        return invokerType;
    }

    @Override
    public String getInvokerName() {
        return invokerName;
    }

    @Override
    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    @Override
    public Environment getEnvironment() {
        return environment;
    }

    public CompactionTrigger getTrigger() {
        return trigger;
    }

    /**
     * Gets the id of the session being compacted, as a string.
     *
     * @return the session id value; never null, but empty when the chain was driven without a session (a rewake
     *         replay), in which case {@link #getExecutionId()} carries the correlation id
     */
    public String getSessionIdValue() {
        return sessionIdValue;
    }

    /**
     * Gets the correlation id of the run that fired this chain, when the run has no session of its own.
     *
     * @return the execution id; empty for an ordinary compaction, where {@link #getSessionIdValue()} identifies the
     *         run instead
     */
    public Optional<ExecutionId> getExecutionId() {
        return Optional.ofNullable(executionId);
    }

    public int getMessageCount() {
        return messageCount;
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public Map<String, Object> getExecutionAttributes() {
        return executionAttributes;
    }

    @Override
    public String toString() {
        return "PreCompactContext{trigger=" + trigger + ", sessionId='" + sessionIdValue + "', executionId="
                + executionId + ", messageCount=" + messageCount + ", estimatedTokens=" + estimatedTokens + '}';
    }

    /** Builder for {@link PreCompactContext}. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private CompactionTrigger trigger;
        private String sessionIdValue;
        private ExecutionId executionId;
        private int messageCount;
        private int estimatedTokens;
        private Instant timestamp;
        private Map<String, Object> executionAttributes;

        private Builder() {
        }

        public Builder invokerType(InvokerType invokerType) {
            this.invokerType = invokerType;
            return this;
        }

        public Builder invokerName(String invokerName) {
            this.invokerName = invokerName;
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

        public Builder trigger(CompactionTrigger trigger) {
            this.trigger = trigger;
            return this;
        }

        /**
         * Sets the id of the session being compacted. Leave unset when the chain is driven without a session; set
         * {@link #executionId(ExecutionId)} instead so the firing is still correlatable.
         *
         * @param sessionIdValue
         *            the session id value (may be null, treated as empty)
         * @return this builder
         */
        public Builder sessionIdValue(String sessionIdValue) {
            this.sessionIdValue = sessionIdValue;
            return this;
        }

        /**
         * Sets the correlation id of a run that has no session of its own.
         *
         * @param executionId
         *            the execution id (may be null)
         * @return this builder
         */
        public Builder executionId(ExecutionId executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder messageCount(int messageCount) {
            this.messageCount = messageCount;
            return this;
        }

        public Builder estimatedTokens(int estimatedTokens) {
            this.estimatedTokens = estimatedTokens;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        public PreCompactContext build() {
            if (timestamp == null) {
                timestamp = Instant.now();
            }
            return new PreCompactContext(this);
        }
    }
}
