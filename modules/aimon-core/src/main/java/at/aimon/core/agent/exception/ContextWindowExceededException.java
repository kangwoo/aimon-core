package at.aimon.core.agent.exception;

/**
 * Thrown when the conversation has grown past the model's effective context window blocking limit and compaction is
 * either disabled or has failed.
 *
 * <p>
 * Distinguishes "context window exhaustion" from generic LLM provider errors so that callers (e.g. {@code
 * OrcaAgentExecutor}) can fail fast with an actionable error rather than retry blindly.
 */
public class ContextWindowExceededException extends AgentException {

    private static final long serialVersionUID = 1L;

    private final int estimatedTokens;
    private final int blockingLimit;

    public ContextWindowExceededException(int estimatedTokens, int blockingLimit, String reason) {
        super("Context window exceeded (estimated=" + estimatedTokens + ", blockingLimit=" + blockingLimit + "): "
                + reason);
        this.estimatedTokens = estimatedTokens;
        this.blockingLimit = blockingLimit;
    }

    public ContextWindowExceededException(int estimatedTokens, int blockingLimit, String reason, Throwable cause) {
        super("Context window exceeded (estimated=" + estimatedTokens + ", blockingLimit=" + blockingLimit + "): "
                + reason, cause);
        this.estimatedTokens = estimatedTokens;
        this.blockingLimit = blockingLimit;
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    public int getBlockingLimit() {
        return blockingLimit;
    }
}
