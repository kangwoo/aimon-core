package at.aimon.core.agent.tool.permission;

import java.util.Objects;

/**
 * A second permission check a call must pass: the allowed-tools entries of <em>another</em> tool, judged against a
 * subject of this call.
 *
 * <p>
 * Returned by {@link ToolPermissionDelegationAware#permissionDelegation}. A tool that can do what another tool does
 * declares it, so the patterns written for that other tool cannot be stepped around — a {@code Monitor} that runs a
 * shell command must be allowed by the {@code Bash} entries as well as by its own. Immutable; thread-safe.
 */
public final class PermissionDelegation {

    private final String toolName;
    private final PermissionSubject subject;

    private PermissionDelegation(String toolName, PermissionSubject subject) {
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        this.subject = Objects.requireNonNull(subject, "subject must not be null");
    }

    /**
     * Creates a delegation.
     *
     * @param toolName
     *            the tool whose allowed-tools entries must also admit the call, e.g. {@code "Bash"} (must not be blank)
     * @param subject
     *            what those entries' patterns are matched against (must not be null)
     * @return the delegation
     */
    public static PermissionDelegation to(String toolName, PermissionSubject subject) {
        return new PermissionDelegation(toolName, subject);
    }

    /**
     * @return the tool whose entries must also admit the call (never null)
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * @return the subject matched against that tool's patterns (never null)
     */
    public PermissionSubject getSubject() {
        return subject;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof PermissionDelegation that && toolName.equals(that.toolName) && subject.equals(that.subject);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolName, subject);
    }

    @Override
    public String toString() {
        return "PermissionDelegation{" + toolName + ", " + subject.describe() + '}';
    }
}
