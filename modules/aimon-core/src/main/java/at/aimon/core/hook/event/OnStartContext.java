package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;

/**
 * Context for OnStartHook.
 *
 * <p>
 * Provides information about the start of execution.
 *
 * <p>
 * Immutable value object. Use builder to create instances.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OnStartContext context = OnStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
 *             .invokerName("default-agent").environment(environment)
 *             .userMessage("What files are in the current directory?").build();
 * }
 * </pre>
 */
public final class OnStartContext implements HookContext {
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
    private final String userMessage;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private OnStartContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Executor type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Executor name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        userMessage = Objects.requireNonNull(builder.userMessage, "User message cannot be null");
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
     * Gets the user message that started the execution.
     *
     * @return The user message (never null)
     */
    public String getUserMessage() {
        return userMessage;
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
        return "OnStartContext{" + "invokerType=" + invokerType + ", invokerName='" + invokerName + '\''
                + ", userMessage='" + userMessage + '\'' + ", timestamp=" + timestamp + ", executionAttributes="
                + executionAttributes + '}';
    }

    /** Builder for OnStartContext. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private String userMessage;
        private Instant timestamp = Instant.now();
        private Map<String, Object> executionAttributes;

        private Builder() {
        }

        /**
         * Sets the executor type.
         *
         * @param invokerType
         *            The executor type (must not be null)
         * @return This builder
         */
        public Builder executorType(InvokerType invokerType) {
            this.invokerType = invokerType;
            return this;
        }

        /**
         * Sets the invoker name.
         *
         * @param invokerName
         *            The invoker name (must not be null)
         * @return This builder
         */
        public Builder invokerName(String invokerName) {
            this.invokerName = invokerName;
            return this;
        }

        /**
         * Sets the hook registry.
         *
         * @param hookRegistry
         *            The hook registry (must not be null)
         * @return This builder
         */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /**
         * Sets the environment.
         *
         * @param environment
         *            The environment (must not be null)
         * @return This builder
         */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the user message.
         *
         * @param userMessage
         *            The user message (must not be null)
         * @return This builder
         */
        public Builder userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        /**
         * Sets the timestamp.
         *
         * <p>
         * Defaults to {@code Instant.now()} if not set.
         *
         * @param timestamp
         *            The timestamp (must not be null)
         * @return This builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the execution attributes.
         *
         * <p>
         * <b>Note:</b> The map is stored using {@code Map.copyOf()}, which creates a shallow copy. Map values should be
         * effectively immutable types (e.g., {@code String}, {@code Integer}).
         *
         * @param executionAttributes
         *            The execution attributes (can be null)
         * @return This builder
         */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /**
         * Builds the context.
         *
         * @return A new OnStartContext (never null)
         * @throws NullPointerException
         *             if any required field is null
         */
        public OnStartContext build() {
            return new OnStartContext(this);
        }
    }
}
