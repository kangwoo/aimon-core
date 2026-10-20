package at.aimon.core.tools;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolContext;

/**
 * Static accessors for two facts about the fork a tool call runs in: how deep it is, and which task id it was launched
 * under.
 *
 * <p>
 * Exists for the same reason {@link CallerAllowedTools} and {@link InvokingSessionAccess} do: the values are read at a
 * handful of spawn and display sites, and every site must agree on what <b>absence</b> means. An absent
 * {@link ToolContextKeys#FORK_DEPTH} means depth 0 — a session's turn or a run with no fork ancestor. An absent
 * {@link #TASK_ID_ATTRIBUTE} means the run is not a fork, or the parallel foreground task feature is off.
 */
public final class ForkAccess {

    /**
     * Execution attribute under which a fork carries its own task id, so its {@code SubagentStart} /
     * {@code SubagentStop}
     * hooks, the hooks of the tools it calls and an approval prompt it raises can say which of several concurrent forks
     * they belong to. Set only when the parallel foreground task feature is enabled; a nested fork overwrites it with
     * its own id.
     */
    public static final String TASK_ID_ATTRIBUTE = "aimon.subagent.task_id";

    private ForkAccess() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * @param context
     *            the tool context (must not be null)
     * @return how many forks deep the run making this call is; 0 when the key is absent
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static int depthOf(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(ToolContextKeys.FORK_DEPTH).orElse(0);
    }

    /**
     * Returns the depth to give a fork spawned from this context.
     *
     * @param context
     *            the tool context of the code doing the spawning (must not be null)
     * @return {@link #depthOf(ToolContext)} plus one
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static int childDepthOf(ToolContext context) {
        return depthOf(context) + 1;
    }

    /**
     * @param executionAttributes
     *            the execution attributes of a run (nullable)
     * @return the fork's task id when {@link #TASK_ID_ATTRIBUTE} holds a string, otherwise empty
     */
    public static Optional<String> taskIdOf(Map<String, Object> executionAttributes) {
        if (executionAttributes == null) {
            return Optional.empty();
        }
        return executionAttributes.get(TASK_ID_ATTRIBUTE) instanceof String taskId
                ? Optional.of(taskId)
                : Optional.empty();
    }

    /**
     * @param context
     *            the tool context (must not be null)
     * @return the task id of the fork this call runs in, read from {@link ToolContextKeys#EXECUTION_ATTRIBUTES_KEY};
     *         empty when absent
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static Optional<String> taskIdOf(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return taskIdOf(context.get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY).orElse(null));
    }
}
