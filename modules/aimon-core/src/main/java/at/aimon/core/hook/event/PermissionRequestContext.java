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
 * Context for {@link PermissionRequestHook}.
 *
 * <p>
 * Fired before a tool is dispatched, asking registered permission hooks whether the requested operation is allowed.
 * Hooks may return {@link at.aimon.core.hook.execution.HookResult#allow()},
 * {@link at.aimon.core.hook.execution.HookResult#ask(String)} or
 * {@link at.aimon.core.hook.execution.HookResult#deny(String)}; the dispatcher merges results with the standard
 * {@code DENY > ASK > ALLOW} precedence.
 *
 * <p>
 * Immutable value object. Use {@link #builder()} to create instances.
 */
public final class PermissionRequestContext implements HookContext {

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
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private PermissionRequestContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Invoker type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Invoker name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        toolName = Objects.requireNonNull(builder.toolName, "Tool name cannot be null");
        toolInput = Objects.requireNonNull(builder.toolInput, "Tool input cannot be null");
        principal = builder.principal;
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
     * Gets the name of the tool whose permission is being checked.
     *
     * @return the tool name (never null)
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Gets the tool input the LLM produced for this call.
     *
     * @return the tool input (never null)
     */
    public ToolInput getToolInput() {
        return toolInput;
    }

    /**
     * Gets the principal initiating the request, if known.
     *
     * @return an optional principal; empty when the dispatcher has no identity context
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
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
        return "PermissionRequestContext{" + "invokerType=" + invokerType + ", invokerName='" + invokerName + '\''
                + ", toolName='" + toolName + '\'' + ", principal=" + principal + ", timestamp=" + timestamp + '}';
    }

    /** Builder for {@link PermissionRequestContext}. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private String toolName;
        private ToolInput toolInput;
        private Principal principal;
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
         * Sets the principal initiating the request. Optional.
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
         * @return a new {@link PermissionRequestContext} (never null)
         */
        public PermissionRequestContext build() {
            return new PermissionRequestContext(this);
        }
    }
}
