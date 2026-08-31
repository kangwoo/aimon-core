package at.aimon.core.llm;

/**
 * Provider-neutral reason for why an LLM stopped generating a response.
 *
 * <p>
 * Every LLM provider reports a stop/finish reason in its own vocabulary (Anthropic {@code end_turn} /
 * {@code max_tokens} / {@code tool_use}, OpenAI {@code stop} / {@code length} / {@code tool_calls}, ...). Provider
 * modules map their wire value to this neutral enum before it crosses the module boundary, so {@code aimon-core} can
 * make control-flow decisions (e.g. detecting a truncated turn) without knowing any provider's raw vocabulary.
 *
 * <p>
 * {@link #UNKNOWN} is the safe default when a provider did not report a reason, or reported one that has no neutral
 * slot (e.g. Anthropic {@code pause_turn}). Consumers treat {@code UNKNOWN} exactly as they treated the historical
 * "no reason available" case, so mapping an unrecognised value to {@code UNKNOWN} never changes behaviour.
 */
public enum StopReason {

    /** The model finished its turn naturally (Anthropic {@code end_turn}, OpenAI {@code stop}). */
    END_TURN,

    /**
     * The model stopped because it wants to call one or more tools (Anthropic {@code tool_use}, OpenAI
     * {@code tool_calls}).
     */
    TOOL_USE,

    /**
     * The model was cut off because it reached the maximum output token limit (Anthropic {@code max_tokens}, OpenAI
     * {@code length}). This is the only truncating reason — see {@link #isTruncated()}.
     */
    MAX_TOKENS,

    /** The model stopped because it emitted a configured stop sequence (Anthropic {@code stop_sequence}). */
    STOP_SEQUENCE,

    /** The provider refused to generate a response (Anthropic {@code refusal}, OpenAI {@code content_filter}). */
    REFUSAL,

    /** No reason was reported, or the reported reason has no neutral slot. Treated as a normal, non-truncated stop. */
    UNKNOWN;

    /**
     * @return {@code true} if generation was cut off before the model could finish (i.e. {@link #MAX_TOKENS}). A
     *         truncated response's text is incomplete and must not be treated as a finished final answer.
     */
    public boolean isTruncated() {
        return this == MAX_TOKENS;
    }
}
