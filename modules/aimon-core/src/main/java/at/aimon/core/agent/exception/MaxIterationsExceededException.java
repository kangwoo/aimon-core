package at.aimon.core.agent.exception;

/**
 * Exception thrown when an agent exceeds the maximum number of iterations.
 *
 * <p>
 * This typically indicates that the agent is unable to complete the task within the configured iteration limit,
 * possibly due to:
 *
 * <ul>
 * <li>Task complexity exceeding expectations
 * <li>Agent getting stuck in a loop
 * <li>Insufficient tools or information
 * <li>Configuration with too low iteration limit
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * if (iterationCount >= maxIterations) {
 *     throw new MaxIterationsExceededException(maxIterations);
 * }
 * }
 * </pre>
 */
public class MaxIterationsExceededException extends AgentException {

    private final int maxIterations;

    /**
     * Creates a new MaxIterationsExceededException.
     *
     * @param maxIterations
     *            The maximum number of iterations that was exceeded
     */
    public MaxIterationsExceededException(int maxIterations) {
        super("Agent exceeded maximum iterations: " + maxIterations);
        this.maxIterations = maxIterations;
    }

    /**
     * Creates a new MaxIterationsExceededException with a custom message.
     *
     * @param message
     *            The error message
     * @param maxIterations
     *            The maximum number of iterations that was exceeded
     */
    public MaxIterationsExceededException(String message, int maxIterations) {
        super(message);
        this.maxIterations = maxIterations;
    }

    /**
     * Gets the maximum number of iterations that was exceeded.
     *
     * @return The maximum iterations
     */
    public int getMaxIterations() {
        return maxIterations;
    }
}
