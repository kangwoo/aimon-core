package at.aimon.core.toolinvocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.tools.ToolContextKeys;

/**
 * The gated {@link ToolCallDispatcher}: every call runs the full {@link SingleToolInvoker} pipeline — the
 * PermissionRequest hooks, the side-effect approval gate, the PreTool hooks, the tool against the bound registry and
 * allow-list, and the PostTool hooks.
 *
 * <p>
 * The binding fixes what is the same for every call of one loop: the registry the tool is executed against, the
 * session registry its interrupt behaviour is read from, the allow-list, the invoker, the hook registry, the
 * execution attributes, the interrupt coordinator, the invoker identity stamped on the hook contexts, and the
 * programmatic settings published to each call as {@link ToolContextKeys#CALLER_PROGRAMMATIC}. What varies
 * per call is an argument of {@link #dispatch}; the loop's iteration is read from the context
 * ({@link ToolContextKeys#ITERATION_COUNT}, {@code 0} when absent).
 *
 * <p>
 * Immutable and thread-safe. Built through {@link #builder()}; every field is required.
 */
public final class DefaultToolCallDispatcher implements ToolCallDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolCallDispatcher.class);

    private final SingleToolInvoker invoker;
    private final InvokerType invokerType;
    private final String invokerName;
    private final HookRegistry hookRegistry;
    private final Map<String, Object> executionAttributes;
    private final ToolRegistry toolRegistry;
    private final ToolRegistry sessionRegistry;
    private final List<AllowedTool> allowedTools;
    private final InterruptCoordinator coordinator;
    private final ProgrammaticToolSettings programmatic;

    private DefaultToolCallDispatcher(Builder builder) {
        this.invoker = Objects.requireNonNull(builder.invoker, "invoker cannot be null");
        this.invokerType = Objects.requireNonNull(builder.invokerType, "invokerType cannot be null");
        this.invokerName = Objects.requireNonNull(builder.invokerName, "invokerName cannot be null");
        this.hookRegistry = Objects.requireNonNull(builder.hookRegistry, "hookRegistry cannot be null");
        this.executionAttributes = Objects.requireNonNull(builder.executionAttributes,
                "executionAttributes cannot be null");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry cannot be null");
        this.sessionRegistry = Objects.requireNonNull(builder.sessionRegistry, "sessionRegistry cannot be null");
        this.allowedTools = List.copyOf(Objects.requireNonNull(builder.allowedTools, "allowedTools cannot be null"));
        this.coordinator = Objects.requireNonNull(builder.coordinator, "coordinator cannot be null");
        this.programmatic = Objects.requireNonNull(builder.programmatic, "programmatic cannot be null");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolDispatchResult dispatch(ToolUse toolUse, ToolCallOrigin origin, ToolContext context) {
        Objects.requireNonNull(toolUse, "toolUse cannot be null");
        Objects.requireNonNull(origin, "origin cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        try {
            final ToolInvocationSpec spec = ToolInvocationSpec.builder().invokerType(invokerType)
                    .invokerName(invokerName).hookRegistry(hookRegistry).executionAttributes(executionAttributes)
                    .toolRegistry(toolRegistry).sessionRegistry(sessionRegistry).allowedTools(allowedTools)
                    .coordinator(coordinator).programmatic(programmatic).toolContext(context).toolUse(toolUse)
                    .origin(origin).iterationCount(context.get(ToolContextKeys.ITERATION_COUNT).orElse(0)).build();
            return invoker.invoke(spec);
        } catch (RuntimeException e) {
            // The pipeline answers failures inside the hook sequence itself; what reaches here failed around it (the
            // interrupt-behaviour lookup, the registrar). The contract is still an error result, never a throw.
            log.error("Tool call dispatch failed: tool={}, toolUseId={}", toolUse.getName(), toolUse.getId(), e);
            return ToolDispatchResults.error(toolUse.getId(), "Tool execution error: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The narrowed dispatcher executes against {@code registry} and reads interrupt behaviour from it too, unless it is
     * this dispatcher's own registry, in which case the session registry is kept.
     */
    @Override
    public ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools,
            ProgrammaticToolSettings programmatic) {
        Objects.requireNonNull(registry, "registry cannot be null");
        Objects.requireNonNull(allowedTools, "allowedTools cannot be null");
        Objects.requireNonNull(programmatic, "programmatic cannot be null");
        ToolCallNarrowing.requireSubset(toolRegistry, registry);
        final List<AllowedTool> bounded = ToolCallNarrowing.intersect(this.allowedTools, allowedTools);
        return toBuilder().toolRegistry(registry).sessionRegistry(registry == toolRegistry ? sessionRegistry : registry)
                .allowedTools(bounded).programmatic(programmatic.narrowedBy(this.programmatic)).build();
    }

    private Builder toBuilder() {
        return builder().invoker(invoker).invokerType(invokerType).invokerName(invokerName).hookRegistry(hookRegistry)
                .executionAttributes(executionAttributes).toolRegistry(toolRegistry).sessionRegistry(sessionRegistry)
                .allowedTools(allowedTools).coordinator(coordinator).programmatic(programmatic);
    }

    /** Builder for {@link DefaultToolCallDispatcher}. */
    public static final class Builder {

        private SingleToolInvoker invoker;
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Map<String, Object> executionAttributes;
        private ToolRegistry toolRegistry;
        private ToolRegistry sessionRegistry;
        private List<AllowedTool> allowedTools;
        private InterruptCoordinator coordinator;
        private ProgrammaticToolSettings programmatic;

        private Builder() {
        }

        /**
         * Sets the pipeline every call runs through.
         *
         * @param invoker
         *            the invoker (must not be null)
         * @return this builder
         */
        public Builder invoker(SingleToolInvoker invoker) {
            this.invoker = invoker;
            return this;
        }

        /**
         * Sets the invoker type stamped on every hook context.
         *
         * @param invokerType
         *            {@code MAIN_AGENT} or {@code SUBAGENT} (must not be null)
         * @return this builder
         */
        public Builder invokerType(InvokerType invokerType) {
            this.invokerType = invokerType;
            return this;
        }

        /**
         * Sets the invoker name stamped on every hook context.
         *
         * @param invokerName
         *            the agent or subagent name (must not be null)
         * @return this builder
         */
        public Builder invokerName(String invokerName) {
            this.invokerName = invokerName;
            return this;
        }

        /**
         * Sets the hook registry the hooks are resolved from.
         *
         * @param hookRegistry
         *            the registry (must not be null)
         * @return this builder
         */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /**
         * Sets the execution attributes threaded through every hook context.
         *
         * @param executionAttributes
         *            the attributes (must not be null)
         * @return this builder
         */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /**
         * Sets the registry the tool is executed against.
         *
         * @param toolRegistry
         *            the registry (must not be null)
         * @return this builder
         */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /**
         * Sets the registry the tool's interrupt behaviour is read from.
         *
         * @param sessionRegistry
         *            the registry (must not be null)
         * @return this builder
         */
        public Builder sessionRegistry(ToolRegistry sessionRegistry) {
            this.sessionRegistry = sessionRegistry;
            return this;
        }

        /**
         * Sets the allow-list; empty means unrestricted.
         *
         * @param allowedTools
         *            the allow-list (must not be null)
         * @return this builder
         */
        public Builder allowedTools(List<AllowedTool> allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        /**
         * Sets the coordinator per-tool terminator registrars are minted from.
         *
         * @param coordinator
         *            the coordinator (must not be null)
         * @return this builder
         */
        public Builder coordinator(InterruptCoordinator coordinator) {
            this.coordinator = coordinator;
            return this;
        }

        /**
         * Sets the programmatic settings of the agent, fork or skill whose loop this dispatcher gates. They are
         * published to every call as {@link ToolContextKeys#CALLER_PROGRAMMATIC}, the ceiling of any fork the call
         * spawns. Required, with no default, so a site that forgets it fails rather than publishing
         * {@link ProgrammaticToolSettings#none()} by accident.
         *
         * @param programmatic
         *            the settings (must not be null)
         * @return this builder
         */
        public Builder programmatic(ProgrammaticToolSettings programmatic) {
            this.programmatic = programmatic;
            return this;
        }

        /**
         * Builds the dispatcher.
         *
         * @return a new dispatcher (never null)
         * @throws NullPointerException
         *             if any field is missing
         */
        public DefaultToolCallDispatcher build() {
            return new DefaultToolCallDispatcher(this);
        }
    }
}
