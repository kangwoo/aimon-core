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
 * for it. Both halves of that are measured — {@code summarized} returns a populated {@code thinking} block (471
 * characters and a signature, on {@code claude-opus-5}), and the accepted set is exactly
 * <b>{@code {summarized, omitted}}</b>.
 *
 * <p>
 * <b>Absent means off.</b> There is no {@code OFF} constant and no default value — an unset
 * {@link AnthropicConfig#getThinkingDisplay()} sends no {@code display} at all, which leaves the request
 * byte-identical to what it was before this key existed. Two reasons for that shape rather than a second constant: a
 * boxed null already says "not written", the way {@code thinkingBudgetTokens} does; and {@code off} is a YAML 1.1
 * reserved word, which is the entire reason {@code AnthropicProviderConfig.ThinkingModeDeserializer} has to exist on
 * the neighbouring key.
 *
 * <p>
 * <b>{@code omitted} is a value the server accepts and this enum deliberately does not carry.</b> It is the server's
 * own default, so writing it is behaviourally identical to leaving the key unset — it would buy an operator no
 * capability that an absent key does not already give them. Worse, it would be self-contradicting: this key does two
 * things, and the second is opening the forwarding gate ({@code AnthropicLlmClient} passes
 * {@code getThinkingDisplay().isPresent()} to {@link AnthropicStreamingMapper}, which gates on presence rather than on
 * the value), so {@code thinkingDisplay: omitted} would mean "open the reasoning channel, and ask the server to put
 * nothing in it". Under {@code EXTENDED} it would be plainer still: no {@code display} is written on the budgeted
 * shape at all, so presence alone opens the gate and {@code omitted} would <em>stream thinking text</em> under a word
 * meaning the opposite. Mirroring the vendor's enumeration is not this type family's practice either —
 * {@link AnthropicThinkingMode} is {@code OFF}/{@code EXTENDED}/{@code ADAPTIVE}/{@code AUTO}, where
 * {@code EXTENDED} writes the wire's {@code enabled}, {@code OFF} writes nothing at all, and {@code AUTO} is not a
 * wire value in any dialect — it asks the capability table which of the other two to send.
 *
 * <p>
 * <b>One constant is not an argument for a boolean.</b> The accepted set is the server's to widen — the sibling ask on
 * the other provider already carries three grains ({@code auto}/{@code concise}/{@code detailed}) — and widening a
 * shipped boolean configuration key into an enum is a breaking type change, the failure mode
 * {@code reasoning-delta-stream.md} §3.4 names. An enum of one extends additively; a boolean does not.
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
    SUMMARIZED("summarized");

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
