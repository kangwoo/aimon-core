package at.aimon.core.internal;

/**
 * Refusal texts that the model-facing loops answer a tool call with when they cannot dispatch it.
 *
 * <p>
 * The main loop ({@code at.aimon.core.agent.impl.orca}) and a fork's loop ({@code at.aimon.core.subagent.execution})
 * share these, so the two cannot drift apart. They live here rather than on a public type because neither loop's
 * package can see the other's package-private members, and the text is not something a caller outside
 * {@code aimon-core} should match on.
 */
public final class ToolDispatchRefusals {

    /**
     * The error text for a tool call whose context carries no {@link at.aimon.core.agent.tool.ToolCallDispatcher}: the
     * call is refused, not run around the gates.
     */
    public static final String NO_DISPATCHER = "Tool call refused: no tool call dispatcher is bound to this"
            + " execution";

    private ToolDispatchRefusals() {
        throw new AssertionError("This class should not be instantiated");
    }
}
