package at.aimon.core.skill.hook.action;

import java.util.Objects;

/**
 * Declarative action (AIMON extension, SK-13) that blocks a tool execution with a fixed reason.
 *
 * <p>
 * Only valid as the action of a {@code preTool} hook — {@link at.aimon.core.hook.event.PreToolHook} is the only
 * hook type whose {@link at.aimon.core.hook.execution.HookResult} can veto execution. The
 * {@code SkillHookSetParser}
 * rejects deny actions attached to any other event.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class DenyAction implements HookAction {

    private final String reason;

    /**
     * Creates a new deny action.
     *
     * @param reason
     *            The block reason surfaced to the LLM (must not be null or blank)
     * @throws NullPointerException
     *             if reason is null
     * @throws IllegalArgumentException
     *             if reason is blank
     */
    public DenyAction(String reason) {
        Objects.requireNonNull(reason, "Reason cannot be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Reason cannot be blank");
        }
        this.reason = reason;
    }

    /**
     * Returns the block reason surfaced to the LLM via {@code HookResult.block(reason)}.
     *
     * @return The reason (never null, never blank)
     */
    public String getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DenyAction that)) {
            return false;
        }
        return reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return reason.hashCode();
    }

    @Override
    public String toString() {
        return "DenyAction{reason='" + reason + "'}";
    }
}
