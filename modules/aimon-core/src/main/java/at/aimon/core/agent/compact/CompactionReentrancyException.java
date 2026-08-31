package at.aimon.core.agent.compact;

import at.aimon.core.agent.exception.AgentException;

/**
 * Thrown by {@link DefaultCompactionEngine} when a nested compaction attempt is detected on the same thread (e.g.
 * triggered from inside a {@code PreCompactHook} or a subagent invocation).
 *
 * <p>
 * This is a defensive guard rather than a transient failure: callers (notably {@link CompactionGuard}) MUST NOT count
 * it against the circuit-breaker failure tally, since incrementing on programmer error would silently disable AUTO
 * compaction for the session.
 */
public class CompactionReentrancyException extends AgentException {

    private static final long serialVersionUID = 1L;

    public CompactionReentrancyException(String message) {
        super(message);
    }
}
