package at.aimon.core.agent.compact;

import at.aimon.core.agent.exception.AgentException;

/**
 * The failure reason of a {@code /compact} that found another compaction of the same session in progress
 * (context-engine §5.6).
 *
 * <p>
 * The session lock is taken with {@code tryLock}: waiting would stall the command behind a summary call, and doing
 * nothing silently would tell the user the command worked. So the engine returns a failed
 * {@link CompactionResult} carrying this, and the command shows it. Like a hook block, it is not a transient fault and
 * does not count against the circuit breaker.
 */
public class CompactionContendedException extends AgentException {

    private static final long serialVersionUID = 1L;

    public CompactionContendedException(String message) {
        super(message);
    }
}
