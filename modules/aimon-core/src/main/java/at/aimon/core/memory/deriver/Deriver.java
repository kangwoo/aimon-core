package at.aimon.core.memory.deriver;

/**
 * Strategy for turning a batch of messages into {@link at.aimon.core.memory.Observation observations}.
 *
 * <p>
 * Implementations are stateless across invocations: any in-flight bookkeeping
 * lives in the queue manager, not on the deriver. The deriver receives a
 * fully-formed {@link DerivationContext} and returns a {@link DerivationResult}.
 */
public interface Deriver {

    /**
     * Processes the messages in {@code ctx} and returns the observations derived
     * from them. Implementations are expected to respect
     * {@link DerivationContext#getTokenBudget()} when calling LLMs.
     *
     * @throws NullPointerException
     *             if {@code ctx} is null
     */
    DerivationResult derive(DerivationContext ctx);
}
