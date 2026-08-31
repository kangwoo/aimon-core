package at.aimon.core.agent.tool.search;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks which deferred tools have been activated for a single session.
 *
 * <p>
 * Each session gets its own instance, so activation state is fully isolated between sessions. Eager tools are
 * not tracked here — they are always active.
 *
 * <p>
 * This class is <b>not</b> thread-safe. Within a single session the ReAct loop is synchronous, so no concurrent
 * access is expected.
 */
public final class ToolActivationState {

    private final Set<String> activatedToolNames = new HashSet<>();

    /**
     * Activates a deferred tool by name.
     *
     * <p>
     * If the tool is already activated, this method is a no-op (idempotent).
     *
     * @param toolName
     *            the tool name (must not be null)
     * @throws NullPointerException
     *             if toolName is null
     */
    public void activate(String toolName) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        activatedToolNames.add(toolName);
    }

    /**
     * Activates multiple deferred tools at once.
     *
     * @param toolNames
     *            the tool names to activate (must not be null)
     * @throws NullPointerException
     *             if toolNames is null
     */
    public void activateAll(List<String> toolNames) {
        Objects.requireNonNull(toolNames, "Tool names cannot be null");
        activatedToolNames.addAll(toolNames);
    }

    /**
     * Returns whether the specified tool has been activated.
     *
     * @param toolName
     *            the tool name (must not be null)
     * @return {@code true} if the tool is activated
     * @throws NullPointerException
     *             if toolName is null
     */
    public boolean isActivated(String toolName) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        return activatedToolNames.contains(toolName);
    }

    /**
     * Returns an unmodifiable snapshot of all activated tool names.
     *
     * @return the set of activated tool names (never null)
     */
    public Set<String> getActivatedToolNames() {
        return Set.copyOf(activatedToolNames);
    }

    /**
     * Clears all activation state.
     */
    public void reset() {
        activatedToolNames.clear();
    }

}
