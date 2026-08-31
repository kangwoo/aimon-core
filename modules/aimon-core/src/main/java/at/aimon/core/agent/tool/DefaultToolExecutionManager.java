package at.aimon.core.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.tool.execution.DefaultToolExecutor;
import at.aimon.core.agent.tool.execution.ToolExecutionContext;
import at.aimon.core.agent.tool.execution.ToolExecutionRequest;
import at.aimon.core.agent.tool.execution.ToolExecutionResult;
import at.aimon.core.agent.tool.execution.ToolExecutor;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.DefaultToolPermissionValidator;
import at.aimon.core.agent.tool.permission.ToolPermissionValidator;
import at.aimon.core.agent.tool.schema.DefaultToolInputSchemaValidator;
import at.aimon.core.agent.tool.schema.SchemaValidationMode;
import at.aimon.core.llm.ToolUse;

/**
 * Default implementation of {@link ToolExecutionManager}.
 *
 * <p>
 * This class orchestrates tool execution by coordinating permission validation, tool lookup, and execution. It
 * delegates to a {@link ToolExecutor} for actual tool execution and a {@link ToolPermissionValidator} for permission
 * checking.
 *
 * <p>
 * Thread-safety depends on the thread-safety of the injected ToolExecutor and ToolPermissionValidator.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Use default components
 *     ToolExecutionManager manager = new DefaultToolExecutionManager();
 *
 *     // Or inject custom components
 *     ToolExecutor executor = new CustomToolExecutor();
 *     ToolPermissionValidator validator = new CustomPermissionValidator();
 *     ToolExecutionManager manager = new DefaultToolExecutionManager(executor, validator);
 * }
 * </pre>
 *
 * @see ToolExecutionManager
 * @see ToolExecutor
 * @see ToolPermissionValidator
 */
public final class DefaultToolExecutionManager implements ToolExecutionManager {

    private final ToolExecutor toolExecutor;
    private final ToolPermissionValidator permissionValidator;
    private final SideEffectLevel maxSideEffectLevel;

    /** DefaultToolExecutionManager를 생성한다. */
    public DefaultToolExecutionManager() {
        this(new DefaultToolExecutor(), new DefaultToolPermissionValidator());
    }

    /**
     * Creates a manager whose executor holds tool calls to their declared schemas in the given mode.
     *
     * <p>
     * The one thing an application has to reach for to move off {@link SchemaValidationMode#WARN}. Assembling the
     * executor by hand works too, but a mode that costs three constructors to set is a mode nobody sets, and the
     * default is the observe-first one precisely so that a host can decide when to start rejecting.
     *
     * @param validationMode
     *            how to react to a call that does not match the tool's schema (must not be null)
     */
    public DefaultToolExecutionManager(SchemaValidationMode validationMode) {
        this(new DefaultToolExecutor(new DefaultToolInputSchemaValidator(),
                Objects.requireNonNull(validationMode, "Validation mode cannot be null")),
                new DefaultToolPermissionValidator());
    }

    /**
     * Creates a manager carrying both a schema validation mode and a side-effect ceiling.
     *
     * <p>
     * The two settings are independent — one judges the shape of a call, the other what the tool is allowed to do —
     * but a host that wants both has no way to reach them through the single-argument constructors, and composing the
     * collaborators by hand would mean importing this package's implementation classes from wherever the assembly
     * lives. Hence the pair.
     *
     * @param validationMode
     *            how to react to a call that does not match the tool's schema (must not be null)
     * @param maxSideEffectLevel
     *            the most permissive side effect a tool may declare and still run (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public DefaultToolExecutionManager(SchemaValidationMode validationMode, SideEffectLevel maxSideEffectLevel) {
        this(new DefaultToolExecutor(new DefaultToolInputSchemaValidator(),
                Objects.requireNonNull(validationMode, "Validation mode cannot be null")),
                new DefaultToolPermissionValidator(), maxSideEffectLevel);
    }

    /** DefaultToolExecutionManager를 생성한다. */
    public DefaultToolExecutionManager(ToolExecutor toolExecutor, ToolPermissionValidator permissionValidator) {
        this(toolExecutor, permissionValidator, SideEffectLevel.MUTATING);
    }

    /**
     * Creates a DefaultToolExecutionManager with default collaborators and the given side-effect ceiling.
     *
     * @param maxSideEffectLevel
     *            the most permissive side effect a tool may declare and still run (must not be null)
     * @throws NullPointerException
     *             if maxSideEffectLevel is null
     */
    public DefaultToolExecutionManager(SideEffectLevel maxSideEffectLevel) {
        this(new DefaultToolExecutor(), new DefaultToolPermissionValidator(), maxSideEffectLevel);
    }

    /**
     * Creates a DefaultToolExecutionManager with a side-effect ceiling.
     *
     * <p>
     * Tools declaring a {@link SideEffectLevel} the ceiling does not {@link SideEffectLevel#permits permit} are
     * refused before execution and reported back as an error result, so the model sees why and can adapt. The default
     * ceiling is {@link SideEffectLevel#MUTATING}, which permits everything — the two-argument constructor is
     * therefore behaviourally identical to this one.
     *
     * <p>
     * This gate is the enforcement point, not the only one: {@code OrcaAgentExecutor} additionally withholds the
     * definitions of blocked tools from the LLM. The gate still matters because a model can name a tool it was never
     * shown.
     *
     * @param toolExecutor
     *            the executor performing the actual invocation (must not be null)
     * @param permissionValidator
     *            the allowlist validator (must not be null)
     * @param maxSideEffectLevel
     *            the most permissive side effect a tool may declare and still run (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultToolExecutionManager(ToolExecutor toolExecutor, ToolPermissionValidator permissionValidator,
            SideEffectLevel maxSideEffectLevel) {
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "Tool executor cannot be null");
        this.permissionValidator = Objects.requireNonNull(permissionValidator, "Permission validator cannot be null");
        this.maxSideEffectLevel = Objects.requireNonNull(maxSideEffectLevel, "Max side effect level cannot be null");
    }

    @Override
    public SideEffectLevel getMaxSideEffectLevel() {
        return maxSideEffectLevel;
    }

    /**
     * Builds the refusal message for a tool blocked by the side-effect ceiling, or returns null when it may run.
     *
     * @param tool
     *            the resolved tool (must not be null)
     * @return the refusal message, or null if the tool is permitted
     */
    private String sideEffectRefusal(Tool tool) {
        final SideEffectLevel declared = tool.getSideEffectLevel();
        if (maxSideEffectLevel.permits(declared)) {
            return null;
        }
        return "Tool '" + tool.getDefinition().getName() + "' is blocked: it declares " + declared
                + ", but this execution allows at most " + maxSideEffectLevel + ".";
    }

    @Override
    public List<ToolExecutionResult> executeAll(ToolRegistry toolRegistry, ToolContext toolContext,
            List<ToolUse> toolUses, List<AllowedTool> allowedTools) {
        Objects.requireNonNull(toolRegistry, "Tool registry cannot be null");
        Objects.requireNonNull(toolUses, "Tool uses cannot be null");
        Objects.requireNonNull(toolContext, "Tool context cannot be null");
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");

        // Resolve every call once, before either loop. The validator is now handed the same Tool instance and the
        // same ToolInput the executor will use, so there is no second lookup for the two to disagree about.
        final List<ResolvedToolUse> resolvedUses = new ArrayList<>(toolUses.size());
        for (ToolUse toolUse : toolUses) {
            resolvedUses.add(new ResolvedToolUse(toolUse, toolRegistry.findByName(toolUse.getName()).orElse(null)));
        }

        // Validate permissions before execution if restrictions exist.
        //
        // Deliberately a separate pass from the execution loop below. Validation throws, so a batch containing one
        // denied call currently executes nothing at all — merging the two loops would let call #1 run to completion
        // before call #2 is refused, turning an all-or-nothing check into a partial one.
        if (!allowedTools.isEmpty()) {
            for (ResolvedToolUse resolved : resolvedUses) {
                validatePermission(resolved, toolContext, allowedTools);
            }
        }

        // Execute tools
        final List<ToolExecutionResult> results = new ArrayList<>();
        for (ResolvedToolUse resolved : resolvedUses) {
            final ToolUse toolUse = resolved.toolUse();
            try {
                if (resolved.tool() == null) {
                    results.add(ToolExecutionResult.error(toolUse.getId(), "Unknown tool: " + toolUse.getName()));
                    continue;
                }

                // Refuse tools whose declared side effect exceeds the configured ceiling
                final String refusal = sideEffectRefusal(resolved.tool());
                if (refusal != null) {
                    results.add(ToolExecutionResult.error(toolUse.getId(), refusal));
                    continue;
                }

                // Create execution context and request
                final ToolExecutionContext executionContext = ToolExecutionContext.of(resolved.tool());
                final ToolExecutionRequest request = ToolExecutionRequest.of(toolUse.getId(), resolved.input(),
                        toolContext);

                // Execute the tool
                final ToolExecutionResult result = toolExecutor.execute(executionContext, request);
                results.add(result);
            } catch (Exception e) {
                results.add(ToolExecutionResult.error(toolUse.getId(), "Tool execution failed: " + e.getMessage(), e));
            }
        }

        return results;
    }

    @Override
    public ToolExecutionResult execute(ToolUse toolUse, ToolContext toolContext, ToolRegistry toolRegistry,
            List<AllowedTool> allowedTools) {
        Objects.requireNonNull(toolRegistry, "Tool registry cannot be null");
        Objects.requireNonNull(toolUse, "Tool use cannot be null");
        Objects.requireNonNull(toolContext, "Tool context cannot be null");
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");

        final ResolvedToolUse resolved = new ResolvedToolUse(toolUse,
                toolRegistry.findByName(toolUse.getName()).orElse(null));

        // Validate permissions before execution if restrictions exist
        if (!allowedTools.isEmpty()) {
            validatePermission(resolved, toolContext, allowedTools);
        }

        try {
            if (resolved.tool() == null) {
                return ToolExecutionResult.error(toolUse.getId(), "Unknown tool: " + toolUse.getName());
            }

            // Refuse tools whose declared side effect exceeds the configured ceiling
            final String refusal = sideEffectRefusal(resolved.tool());
            if (refusal != null) {
                return ToolExecutionResult.error(toolUse.getId(), refusal);
            }

            // Create execution context and request
            final ToolExecutionContext executionContext = ToolExecutionContext.of(resolved.tool());
            final ToolExecutionRequest request = ToolExecutionRequest.of(toolUse.getId(), resolved.input(),
                    toolContext);

            // Execute the tool
            return toolExecutor.execute(executionContext, request);
        } catch (Exception e) {
            return ToolExecutionResult.error(toolUse.getId(), "Tool execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * Runs one call past the permission validator, throwing if it is refused.
     *
     * <p>
     * A name the registry cannot resolve takes the name-only entry point. There is no instance to ask for a
     * {@link at.aimon.core.agent.tool.permission.PermissionSubject}, so only the name-level rules can apply; the call
     * is about to fail as {@code "Unknown tool: …"} regardless, and validating it first keeps an invented name from
     * reading differently to a forbidden one in the audit trail.
     */
    private void validatePermission(ResolvedToolUse resolved, ToolContext toolContext, List<AllowedTool> allowedTools) {
        if (resolved.tool() == null) {
            permissionValidator.validateByNameOrThrow(resolved.toolUse().getName(), allowedTools);
        } else {
            permissionValidator.validateOrThrow(resolved.tool(), resolved.input(), toolContext, allowedTools);
        }
    }

    /**
     * One {@link ToolUse} paired with what it resolves to, built once per call.
     *
     * <p>
     * Exists so the permission pass and the execution pass cannot end up looking at different objects: both the
     * registry lookup and the {@link ToolInput} wrapping used to happen twice per call, and the validator only ever
     * saw the raw argument map.
     */
    private static final class ResolvedToolUse {

        private final ToolUse toolUse;

        private final Tool tool;

        private final ToolInput input;

        private ResolvedToolUse(ToolUse toolUse, Tool tool) {
            this.toolUse = toolUse;
            this.tool = tool;
            this.input = ToolInput.of(toolUse.getInput());
        }

        private ToolUse toolUse() {
            return toolUse;
        }

        /** The resolved tool, or null when the registry does not know the name. */
        private Tool tool() {
            return tool;
        }

        private ToolInput input() {
            return input;
        }
    }
}
