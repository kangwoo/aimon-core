package at.aimon.core.agent;

/**
 * Represents the type of executor invoking tools.
 *
 * <p>
 * This helps distinguish tools agent runtimes for:
 *
 * <ul>
 * <li>UI presentation (e.g., different indentation levels)
 * <li>Logging and monitoring
 * <li>Permission and security policies
 * <li>Performance metrics
 * </ul>
 *
 * <p>
 * <b>Design Note:</b> This is implemented as an enum rather than a Value Object because:
 * <ul>
 * <li>The set of invoker types is small and stable (main agent, subagent, nested)
 * <li>New invoker types are extremely rare and would require system-wide changes anyway
 * <li>The getDisplayDepth() method provides simple utility logic that doesn't warrant extensibility
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // In main agent
 *     ToolContext context = ToolContext.forExecutor(InvokerType.mainAgent());
 *     toolExecutor.execute(toolUse, context);
 *
 *     // In subagent
 *     ToolContext context = ToolContext.forExecutor(InvokerType.subagent());
 *     toolExecutor.execute(toolUse, context);
 *
 *     // In formatter
 *     InvokerType type = context.getExecutorType().orElse(InvokerType.mainAgent());
 *     int depth = type.getDisplayDepth();
 * }
 * </pre>
 */
public enum InvokerType {
    /** Main agent executor - top-level agent execution. */
    MAIN_AGENT,

    /** Subagent executor - delegated task execution. */
    SUBAGENT,

    /** Nested subagent executor - subagent invoked by another subagent. */
    NESTED_SUBAGENT;

    /**
     * Returns the main agent invoker type.
     *
     * <p>
     * Factory method that improves readability and allows for future extensibility without breaking existing code.
     *
     * @return The MAIN_AGENT invoker type
     */
    public static InvokerType mainAgent() {
        return MAIN_AGENT;
    }

    /**
     * Returns the subagent invoker type.
     *
     * <p>
     * Factory method that improves readability and allows for future extensibility without breaking existing code.
     *
     * @return The SUBAGENT invoker type
     */
    public static InvokerType subagent() {
        return SUBAGENT;
    }

    /**
     * Returns the nested subagent invoker type.
     *
     * <p>
     * Factory method that improves readability and allows for future extensibility without breaking existing code.
     *
     * @return The NESTED_SUBAGENT invoker type
     */
    public static InvokerType nestedSubagent() {
        return NESTED_SUBAGENT;
    }

    /**
     * Returns the display depth for UI presentation. Can be used by formatters to determine indentation level.
     *
     * @return The display depth (0 for main agent, 1 for subagent, 2 for nested)
     */
    public int getDisplayDepth() {
        return switch (this) {
            case MAIN_AGENT -> 0;
            case SUBAGENT -> 1;
            case NESTED_SUBAGENT -> 2;
        };
    }
}
