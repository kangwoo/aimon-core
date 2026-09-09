package at.aimon.core.llm;

/**
 * Provider-neutral reasoning-effort level requested for a single LLM call.
 *
 * <p>
 * Reasoning models spend a variable amount of hidden deliberation before answering, and expose a knob controlling how
 * much. Every vendor spells that knob differently, so this enum is the neutral vocabulary a caller sets on
 * {@link LlmModel}; provider modules translate it to their wire value at request-build time, the same way
 * {@link StopReason} is translated in the other direction.
 *
 * <p>
 * The ladder deliberately stops at {@link #HIGH} rather than re-exporting one vendor's full range. It is the
 * <em>common subset</em> — the levels that survive translation to a second provider. Anthropic expresses the same axis
 * as a thinking <em>token budget</em>, so even these five are a mapping rather than a shared type; a caller that needs
 * a vendor-only rung is asking for a provider-specific escape hatch, which is a different design. Adding a constant
 * later is source-compatible.
 *
 * <p>
 * <strong>The constants are declared in ascending order of effort</strong>, so the natural
 * {@link Enum#compareTo(Enum) ordering} compares rungs without a second table: a model's ladder can be written as a
 * range from its lowest rung, and an {@link java.util.EnumSet} of rungs iterates in ladder order, so a message that
 * prints one reads as a ladder. That ordering is load-bearing rather than cosmetic — a constant inserted later must go
 * in its place on the ladder, not at the end.
 *
 * <p>
 * Whether a model takes this parameter at all is a capability, not a request value: see
 * {@link at.aimon.core.llm.capability.ModelCapabilities#supportsReasoningEffort()}. Which of these rungs it actually
 * accepts is a second capability: see
 * {@link at.aimon.core.llm.capability.ModelCapabilities#acceptedReasoningEfforts()}, which is a set rather than a
 * floor because at least one model's ladder has a gap in the middle of it.
 */
public enum ReasoningEffort {

    /**
     * Disable reasoning for this call.
     *
     * <p>
     * Not merely "the lowest rung" — it is the level some endpoints require when tools are present, so a provider may
     * send it explicitly rather than omitting the parameter (see
     * {@link at.aimon.core.llm.capability.ModelCapabilities#supportsToolsWithReasoning()}).
     */
    NONE,

    /** The smallest non-zero amount of reasoning. */
    MINIMAL,

    /** Little reasoning; favours latency over depth. */
    LOW,

    /** A balanced amount of reasoning. */
    MEDIUM,

    /** Extensive reasoning; favours depth over latency. */
    HIGH
}
