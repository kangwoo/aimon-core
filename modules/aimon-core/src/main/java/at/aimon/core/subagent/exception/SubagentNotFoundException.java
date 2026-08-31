package at.aimon.core.subagent.exception;

/**
 * Exception thrown when a requested subagent is not found.
 *
 * <p>
 * This exception indicates that the subagent with the specified name does not exist in the registry.
 */
public class SubagentNotFoundException extends SubagentException {
    private static final long serialVersionUID = 1L;

    private final String subagentName;

    /**
     * Creates a new SubagentNotFoundException.
     *
     * @param subagentName
     *            The name of the subagent that was not found
     */
    public SubagentNotFoundException(String subagentName) {
        super("Subagent not found: " + subagentName);
        this.subagentName = subagentName;
    }

    /**
     * Returns the name of the subagent that was not found.
     *
     * @return The subagent name
     */
    public String getSubagentName() {
        return subagentName;
    }
}
