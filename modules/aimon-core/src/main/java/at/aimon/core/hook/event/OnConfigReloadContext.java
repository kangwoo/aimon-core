package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;

/**
 * Context for {@link OnConfigReloadHook}.
 *
 * <p>
 * Application-scoped event fired after the hook configuration has been reloaded by
 * {@code HookConfigWatcher}. Hooks observe the reload — they cannot block it.
 *
 * <p>
 * The {@code reloadCounter} is monotonically increasing and is used to break re-entrancy: if a hook causes another
 * reload, the watcher rejects the nested invocation when {@code depth > 1} (R4 in the design plan).
 *
 * <p>
 * Immutable value object. Use {@link #builder()} to create instances.
 */
public final class OnConfigReloadContext implements HookContext {

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
    private final long reloadCounter;
    private final String configSource;
    private final boolean successful;
    private final String failureReason;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private OnConfigReloadContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Invoker type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Invoker name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        if (builder.reloadCounter < 0) {
            throw new IllegalArgumentException("Reload counter must be >= 0, got: " + builder.reloadCounter);
        }
        reloadCounter = builder.reloadCounter;
        configSource = builder.configSource != null ? builder.configSource : "";
        successful = builder.successful;
        failureReason = builder.failureReason != null ? builder.failureReason : "";
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
     * Gets the monotonically-increasing reload counter.
     *
     * @return the reload counter (>= 0)
     */
    public long getReloadCounter() {
        return reloadCounter;
    }

    /**
     * Gets the source identifier of the reloaded configuration (e.g. file path or layer name).
     *
     * @return the config source (never null; empty string when unknown)
     */
    public String getConfigSource() {
        return configSource;
    }

    /**
     * Whether the reload completed successfully.
     *
     * @return {@code true} if the new configuration was applied, {@code false} if the watcher rolled back
     */
    public boolean isSuccessful() {
        return successful;
    }

    /**
     * Gets the failure reason when {@link #isSuccessful()} is {@code false}.
     *
     * @return the failure reason (never null; empty string when the reload succeeded)
     */
    public String getFailureReason() {
        return failureReason;
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
        return "OnConfigReloadContext{" + "invokerType=" + invokerType + ", invokerName='" + invokerName + '\''
                + ", reloadCounter=" + reloadCounter + ", configSource='" + configSource + '\'' + ", successful="
                + successful + ", failureReason='" + failureReason + '\'' + ", timestamp=" + timestamp + '}';
    }

    /** Builder for {@link OnConfigReloadContext}. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private long reloadCounter;
        private String configSource;
        private boolean successful = true;
        private String failureReason;
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
         * Sets the monotonically-increasing reload counter.
         *
         * @param reloadCounter
         *            the counter (must be >= 0)
         * @return this builder
         */
        public Builder reloadCounter(long reloadCounter) {
            this.reloadCounter = reloadCounter;
            return this;
        }

        /**
         * Sets the source identifier (e.g. path or layer) of the reloaded config.
         *
         * @param configSource
         *            the source (may be null; defaults to empty string)
         * @return this builder
         */
        public Builder configSource(String configSource) {
            this.configSource = configSource;
            return this;
        }

        /**
         * Marks whether the reload succeeded; defaults to {@code true}.
         *
         * @param successful
         *            whether the reload succeeded
         * @return this builder
         */
        public Builder successful(boolean successful) {
            this.successful = successful;
            return this;
        }

        /**
         * Sets the failure reason; ignored when {@link #successful(boolean)} is {@code true}.
         *
         * @param failureReason
         *            the reason (may be null; defaults to empty string)
         * @return this builder
         */
        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
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
         * @return a new {@link OnConfigReloadContext} (never null)
         */
        public OnConfigReloadContext build() {
            return new OnConfigReloadContext(this);
        }
    }
}
