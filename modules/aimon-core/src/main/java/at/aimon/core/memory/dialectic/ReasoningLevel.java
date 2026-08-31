package at.aimon.core.memory.dialectic;

/**
 * Cost / quality knob for {@link DialecticEngine} queries.
 *
 * <p>
 * Each level pins an upper bound on the LLM token budget. Future ReAct-style
 * implementations will additionally select different tool sets per level
 * (design doc §6.2 — {@code DialecticTools.MINIMAL/STANDARD/FULL}); the stage 3
 * single-shot engine ignores the tool axis.
 */
public enum ReasoningLevel {

    /** Lowest cost; suitable for quick lookups. */
    FAST(4_000),

    /** Default; balances depth and cost. */
    BALANCED(16_000),

    /** Highest depth; multi-step reasoning when ReAct loop arrives. */
    DEEP(64_000);

    private final int maxTokens;

    ReasoningLevel(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getMaxTokens() {
        return maxTokens;
    }
}
