package at.aimon.core.agent.compact;

import at.aimon.core.agent.exception.AgentException;

/**
 * The failure reason of a compaction that had nothing it may summarize: everything in the view is either already a
 * summary or something the model has not answered yet (context-engine §13.10).
 *
 * <p>
 * It is not a fault. No summary was asked for, no hook fired and the view is unchanged, so it does not count against
 * the circuit breaker, and {@link DefaultCompactionGuard#decide} answers the AUTO path with a warning rather than
 * with a compaction that failed. A manual {@code /compact} shows it as the reason nothing happened.
 */
public class NothingToCompactException extends AgentException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message
     *            why nothing could be summarized; shown to the user by {@code /compact} and carried in the guard's
     *            warning
     */
    public NothingToCompactException(String message) {
        super(message);
    }
}
