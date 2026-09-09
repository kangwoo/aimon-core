package at.aimon.core.llms.anthropic;

import java.util.Objects;

/**
 * How much of its thinking Anthropic sends back on the <em>adaptive</em> dialect —
 * {@code thinking: {"type": "adaptive", "display": "..."}}.
 *
 * <p>
 * On the current model generation the field defaults to {@code "omitted"}, so an adaptive request receives the signed
 * thinking blocks it has to replay but <b>no readable thinking text at all</b>. That is why forwarding the deltas on
 * its own would have shipped an always-empty channel on this dialect: nothing arrives to forward until this field asks
 * for it.
 *
 * <p>
 * <b>Absent means off.</b> There is no {@code OFF} constant and no default value — an unset
 * {@link AnthropicConfig#getThinkingDisplay()} sends no {@code display} at all, which leaves the request
 * byte-identical to what it was before this key existed. Two reasons for that shape rather than a third constant: a
 * boxed null already says "not written", the way {@code thinkingBudgetTokens} does; and {@code off} is a YAML 1.1
 * reserved word, which is the entire reason {@code AnthropicProviderConfig.ThinkingModeDeserializer} has to exist on
 * the neighbouring key.
 *
 * <p>
 * <b>The budgeted dialect does not take this field.</b> {@code ThinkingConfigEnabled} carries {@code budget_tokens}
 * and {@code type}, and whether it would accept a {@code display} sibling is unmeasured — so nothing is sent there.
 * It costs nothing: on that dialect {@code thinking_delta} events already arrive, and the key's other half (opening
 * the forwarding gate) works regardless. An operator who writes it under {@code EXTENDED} is told once that the word
 * reached nothing while the forwarding did.
 *
 * @see AnthropicConfig.Builder#thinkingDisplay(AnthropicThinkingDisplay)
 * @see AnthropicThinkingMode
 */
public enum AnthropicThinkingDisplay {

    /** {@code "summarized"} — a server-written summary of the model's thinking. */
    SUMMARIZED("summarized"),

    /** {@code "updates"} — short progress updates rather than a running summary. */
    UPDATES("updates");

    private final String wireValue;

    AnthropicThinkingDisplay(String wireValue) {
        this.wireValue = Objects.requireNonNull(wireValue, "wireValue");
    }

    /**
     * The spelling this constant takes on the wire.
     *
     * @return the {@code display} value (never null)
     */
    public String wireValue() {
        return wireValue;
    }
}
