package at.aimon.core.toolinvocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.ToolUse;

/**
 * Immutable description of a single tool invocation, carrying everything {@link SingleToolInvoker#invoke} needs to run
 * the shared per-tool pipeline (interrupt-registrar management, PermissionRequest / PreTool / execute / PostTool hooks)
 * for one {@link ToolUse}.
 *
 * <p>
 * The spec exists so both the main-agent ReAct loop ({@code OrcaAgentExecutor}) and the subagent loop
 * ({@code DefaultSubagentExecutor}) can share the same tool-invocation core without duplicating the delicate hook and
 * interrupt sequencing (removing parity/drift risk). The two callers differ only in a handful of values that this
 * spec captures explicitly:
 *
 * <ul>
 * <li>{@link #getInvokerType()} / {@link #getInvokerName()} — {@code MAIN_AGENT} vs {@code SUBAGENT} identity woven
 * into
 * every hook context.
 * <li>{@link #getAllowedTools()} — the main agent runs unrestricted (empty list); a subagent constrains dispatch to its
 * declared allow-list.
 * </ul>
 *
 * <p>
 * All fields are required except {@link #getEnvironment() environment}, which may be {@code null} (mirrors the existing
 * executors, which pass a possibly-null environment straight to the hook context builders).
 */
public final class ToolInvocationSpec {

    private final InvokerType invokerType;
    private final String invokerName;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final Map<String, Object> executionAttributes;
    private final ToolRegistry toolRegistry;
    private final ToolRegistry sessionRegistry;
    private final List<AllowedTool> allowedTools;
    private final InterruptCoordinator coordinator;
    private final ToolContext toolContext;
    private final ToolUse toolUse;
    private final int iterationCount;

    private ToolInvocationSpec(Builder builder) {
        this.invokerType = Objects.requireNonNull(builder.invokerType, "invokerType cannot be null");
        this.invokerName = Objects.requireNonNull(builder.invokerName, "invokerName cannot be null");
        this.hookRegistry = Objects.requireNonNull(builder.hookRegistry, "hookRegistry cannot be null");
        this.environment = builder.environment;
        this.executionAttributes = Objects.requireNonNull(builder.executionAttributes,
                "executionAttributes cannot be null");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry cannot be null");
        this.sessionRegistry = Objects.requireNonNull(builder.sessionRegistry, "sessionRegistry cannot be null");
        this.allowedTools = Objects.requireNonNull(builder.allowedTools, "allowedTools cannot be null");
        this.coordinator = Objects.requireNonNull(builder.coordinator, "coordinator cannot be null");
        this.toolContext = Objects.requireNonNull(builder.toolContext, "toolContext cannot be null");
        this.toolUse = Objects.requireNonNull(builder.toolUse, "toolUse cannot be null");
        this.iterationCount = builder.iterationCount;
    }

    /** @return a new builder for {@link ToolInvocationSpec}. */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the invoker type ({@code MAIN_AGENT} or {@code SUBAGENT}) stamped onto every hook context. */
    public InvokerType getInvokerType() {
        return invokerType;
    }

    /** @return the invoker name (agent or subagent name) stamped onto every hook context. */
    public String getInvokerName() {
        return invokerName;
    }

    /** @return the hook registry used to resolve and fire hooks for this invocation. */
    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    /** @return the execution environment passed to hook contexts, or {@code null} when none is bound. */
    public Environment getEnvironment() {
        return environment;
    }

    /** @return the execution attributes threaded through every hook context (never null). */
    public Map<String, Object> getExecutionAttributes() {
        return executionAttributes;
    }

    /** @return the registry the tool is actually looked up and executed against. */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /** @return the per-session registry consulted for the target tool's {@code InterruptBehavior}. */
    public ToolRegistry getSessionRegistry() {
        return sessionRegistry;
    }

    /** @return the permission allow-list; an empty list means no restriction (the main-agent default). */
    public List<AllowedTool> getAllowedTools() {
        return allowedTools;
    }

    /** @return the turn-scoped interrupt coordinator used to mint the per-tool terminator registrar. */
    public InterruptCoordinator getCoordinator() {
        return coordinator;
    }

    /** @return the base tool context enriched (per invocation) with the current tool-use id and any registrar. */
    public ToolContext getToolContext() {
        return toolContext;
    }

    /** @return the tool use request being dispatched. */
    public ToolUse getToolUse() {
        return toolUse;
    }

    /** @return the current ReAct iteration count, forwarded to Pre/PostTool hook contexts. */
    public int getIterationCount() {
        return iterationCount;
    }

    /**
     * Builder for {@link ToolInvocationSpec}.
     */
    public static final class Builder {

        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private Map<String, Object> executionAttributes;
        private ToolRegistry toolRegistry;
        private ToolRegistry sessionRegistry;
        private List<AllowedTool> allowedTools;
        private InterruptCoordinator coordinator;
        private ToolContext toolContext;
        private ToolUse toolUse;
        private int iterationCount;

        private Builder() {
        }

        /** Sets the invoker type ({@code MAIN_AGENT} or {@code SUBAGENT}). */
        public Builder invokerType(InvokerType invokerType) {
            this.invokerType = invokerType;
            return this;
        }

        /** Sets the invoker name (agent or subagent name). */
        public Builder invokerName(String invokerName) {
            this.invokerName = invokerName;
            return this;
        }

        /** Sets the hook registry used to resolve and fire hooks. */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /** Sets the execution environment passed to hook contexts (may be null). */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /** Sets the execution attributes threaded through every hook context. */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /** Sets the registry the tool is looked up and executed against. */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /** Sets the per-session registry consulted for the tool's interrupt behaviour. */
        public Builder sessionRegistry(ToolRegistry sessionRegistry) {
            this.sessionRegistry = sessionRegistry;
            return this;
        }

        /** Sets the permission allow-list; an empty list means no restriction. */
        public Builder allowedTools(List<AllowedTool> allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        /** Sets the turn-scoped interrupt coordinator. */
        public Builder coordinator(InterruptCoordinator coordinator) {
            this.coordinator = coordinator;
            return this;
        }

        /** Sets the base tool context enriched per invocation. */
        public Builder toolContext(ToolContext toolContext) {
            this.toolContext = toolContext;
            return this;
        }

        /** Sets the tool use request being dispatched. */
        public Builder toolUse(ToolUse toolUse) {
            this.toolUse = toolUse;
            return this;
        }

        /** Sets the current ReAct iteration count. */
        public Builder iterationCount(int iterationCount) {
            this.iterationCount = iterationCount;
            return this;
        }

        /** @return a new immutable {@link ToolInvocationSpec} from this builder's state. */
        public ToolInvocationSpec build() {
            return new ToolInvocationSpec(this);
        }
    }
}
