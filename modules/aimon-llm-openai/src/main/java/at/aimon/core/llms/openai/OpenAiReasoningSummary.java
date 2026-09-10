package at.aimon.core.llms.openai;

/**
 * How detailed a reasoning summary this deployment asks OpenAI for — {@code reasoning.summary} on the Responses
 * request.
 *
 * <p>
 * On this endpoint the reasoning itself is {@code encrypted_content}: ciphertext by design, useful only for replaying
 * the item on the next turn and unreadable by anyone. The <em>summary</em> is the only human-readable surrogate the
 * API offers, and nothing asks for one unless this key is set — which is why forwarding the summary deltas on their
 * own would have shipped an always-empty channel.
 *
 * <p>
 * A framework enum rather than the SDK's {@code Reasoning.Summary} for the reason {@link OpenAiReasoningEfforts}
 * exists: a vendor SDK type on a public configuration surface makes that SDK part of this project's API. Absent means
 * unset means off — no {@code reasoning.summary} on the wire and no reasoning delta forwarded.
 *
 * @see OpenAIConfig.Builder#reasoningSummary(OpenAiReasoningSummary)
 */
public enum OpenAiReasoningSummary {

    /** {@code auto} — let the server choose how much to summarise. */
    AUTO,

    /** {@code concise} — a short summary. */
    CONCISE,

    /** {@code detailed} — a fuller summary, at a correspondingly higher token cost. */
    DETAILED
}
