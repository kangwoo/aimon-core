package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;

/**
 * Context for {@link OnSessionEndHook}.
 *
 * <p>
 * Fired when a {@code LiveSession} is closed. Advisory chain — fired for both clean and abnormal terminations; hooks
 * must not block.
 *
 * <p>
 * Not every firing has a session behind it. A rewake replay drives the same hook chain without ever opening a
 * {@code LiveSession}, so {@link #getSessionId()} is empty there and {@link #getExecutionId()} carries the
 * correlation id instead.
 *
 * <p>
 * Immutable value object. Use {@link #builder()} to create instances.
 */
public final class OnSessionEndContext implements HookContext {

    /**
     * Creates a new builder.
     *
     * @return A new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final InvokerType invokerType;
    private final String invokerName;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final SessionId sessionId;
    private final ExecutionId executionId;
    private final String agentRuntimeId;
    private final boolean clean;
    private final String terminationReason;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private OnSessionEndContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Invoker type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Invoker name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        sessionId = builder.sessionId;
        executionId = builder.executionId;
        agentRuntimeId = builder.agentRuntimeId != null ? builder.agentRuntimeId : "";
        clean = builder.clean;
        terminationReason = builder.terminationReason;
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

    /**
     * Gets the session id this firing belongs to, when there is one.
     *
     * @return the session id; empty when the chain was driven without a session (a rewake replay), in which case
     *         {@link #getExecutionId()} carries the correlation id
     */
    public Optional<SessionId> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * Gets the correlation id of the run that fired this chain, when the run has no session of its own.
     *
     * @return the execution id; empty for an ordinary session end, where {@link #getSessionId()} identifies the run
     *         instead
     */
    public Optional<ExecutionId> getExecutionId() {
        return Optional.ofNullable(executionId);
    }

    /**
     * Gets the agent runtime id backing this session.
     *
     * @return the context id (never null; empty string when unknown)
     */
    public String getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /**
     * Indicates whether the session terminated cleanly.
     *
     * @return {@code true} if the session ended via a normal {@code close()}; {@code false} if abnormal
     */
    public boolean isClean() {
        return clean;
    }

    /**
     * Gets the optional termination reason for non-clean shutdowns.
     *
     * @return optional reason; empty for clean terminations or when no reason was supplied
     */
    public Optional<String> getTerminationReason() {
        return Optional.ofNullable(terminationReason).filter(s -> !s.isEmpty());
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
        return "OnSessionEndContext{" + "invokerType=" + invokerType + ", invokerName='" + invokerName + '\''
                + ", sessionId=" + sessionId + ", executionId=" + executionId + ", clean=" + clean + ", timestamp="
                + timestamp + '}';
    }

    /** Builder for {@link OnSessionEndContext}. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private SessionId sessionId;
        private ExecutionId executionId;
        private String agentRuntimeId;
        private boolean clean = true;
        private String terminationReason;
        private Instant timestamp = Instant.now();
        private Map<String, Object> executionAttributes;

        private Builder() {
        }

        /**
         * Sets the invoker type.
         *
         * @param invokerType
         *            the invoker type (must not be null)
         * @return this builder
         */
        public Builder invokerType(InvokerType invokerType) {
            this.invokerType = invokerType;
            return this;
        }

        /**
         * Sets the invoker name.
         *
         * @param invokerName
         *            the invoker name (must not be null)
         * @return this builder
         */
        public Builder invokerName(String invokerName) {
            this.invokerName = invokerName;
            return this;
        }

        /**
         * Sets the hook registry.
         *
         * @param hookRegistry
         *            the hook registry (must not be null)
         * @return this builder
         */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /**
         * Sets the environment.
         *
         * @param environment
         *            the environment (must not be null)
         * @return this builder
         */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the session id. Leave unset when the chain is driven without a session; set
         * {@link #executionId(ExecutionId)} instead so the firing is still correlatable.
         *
         * @param sessionId
         *            the session id (may be null)
         * @return this builder
         */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
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

        /**
         * Sets the agent runtime id (optional; defaults to empty string).
         *
         * @param agentRuntimeId
         *            the context id (may be null)
         * @return this builder
         */
        public Builder agentRuntimeId(String agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * Sets the clean-termination flag (defaults to {@code true}).
         *
         * @param clean
         *            whether the session ended cleanly
         * @return this builder
         */
        public Builder clean(boolean clean) {
            this.clean = clean;
            return this;
        }

        /**
         * Sets the optional termination reason.
         *
         * @param terminationReason
         *            the reason (may be null)
         * @return this builder
         */
        public Builder terminationReason(String terminationReason) {
            this.terminationReason = terminationReason;
            return this;
        }

        /**
         * Sets the timestamp; defaults to {@link Instant#now()} when not set.
         *
         * @param timestamp
         *            the timestamp (must not be null)
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the execution attributes (stored via {@link Map#copyOf(Map)}).
         *
         * @param executionAttributes
         *            the attributes (may be null)
         * @return this builder
         */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /**
         * Builds the context.
         *
         * @return a new {@link OnSessionEndContext} (never null)
         */
        public OnSessionEndContext build() {
            return new OnSessionEndContext(this);
        }
    }
}
