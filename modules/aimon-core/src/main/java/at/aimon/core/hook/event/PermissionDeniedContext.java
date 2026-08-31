package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;

/**
 * Context for {@link PermissionDeniedHook}.
 *
 * <p>
 * Fired after a {@link PermissionRequestHook} chain produced a {@link at.aimon.core.hook.execution.Decision#DENY}
 * outcome (or after an interactive {@code ASK} resolved to DENY). Permission-denied hooks are advisory only — their
 * results are surfaced as logs / metrics but cannot revert the deny decision.
 *
 * <p>
 * Immutable value object. Use {@link #builder()} to create instances.
 */
public final class PermissionDeniedContext implements HookContext {

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
    private final String toolName;
    private final ToolInput toolInput;
    private final Principal principal;
    private final String denyReason;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private PermissionDeniedContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Invoker type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Invoker name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        toolName = Objects.requireNonNull(builder.toolName, "Tool name cannot be null");
        toolInput = Objects.requireNonNull(builder.toolInput, "Tool input cannot be null");
        principal = builder.principal;
        denyReason = Objects.requireNonNull(builder.denyReason, "Deny reason cannot be null");
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
     * Gets the tool name whose permission request was denied.
     *
     * @return the tool name (never null)
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Gets the tool input that was rejected.
     *
     * @return the tool input (never null)
     */
    public ToolInput getToolInput() {
        return toolInput;
    }

    /**
     * Gets the principal whose request was denied, if known.
     *
     * @return an optional principal
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Gets the human-readable deny reason carried over from the merged {@link PermissionRequestHook} feedback.
     *
     * @return the deny reason (never null)
     */
    public String getDenyReason() {
        return denyReason;
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
        return "PermissionDeniedContext{" + "invokerType=" + invokerType + ", invokerName='" + invokerName + '\''
                + ", toolName='" + toolName + '\'' + ", denyReason='" + denyReason + '\'' + ", principal=" + principal
                + ", timestamp=" + timestamp + '}';
    }

    /** Builder for {@link PermissionDeniedContext}. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private String toolName;
        private ToolInput toolInput;
        private Principal principal;
        private String denyReason;
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
         * Sets the tool name.
         *
         * @param toolName
         *            the tool name (must not be null)
         * @return this builder
         */
        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        /**
         * Sets the tool input.
         *
         * @param toolInput
         *            the tool input (must not be null)
         * @return this builder
         */
        public Builder toolInput(ToolInput toolInput) {
            this.toolInput = toolInput;
            return this;
        }

        /**
         * Sets the principal whose request was denied. Optional.
         *
         * @param principal
         *            the principal (may be null)
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Sets the deny reason.
         *
         * @param denyReason
         *            the deny reason (must not be null)
         * @return this builder
         */
        public Builder denyReason(String denyReason) {
            this.denyReason = denyReason;
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
         * Sets the execution attributes. The map is stored via {@link Map#copyOf(Map)}.
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
         * @return a new {@link PermissionDeniedContext} (never null)
         */
        public PermissionDeniedContext build() {
            return new PermissionDeniedContext(this);
        }
    }
}
