package at.aimon.core.llms.anthropic;

import java.util.Optional;

import com.anthropic.core.JsonField;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.Usage;

/**
 * Reads Anthropic's thinking-token counter off a usage document.
 *
 * <p>
 * The API reports it as {@code usage.output_tokens_details.thinking_tokens}, and on the streaming path the breakdown
 * appears only on the final {@code message_delta} event. SDK 2.13.0 modelled neither the breakdown nor the counter, so
 * this class used to dig both out of {@code _additionalProperties()}. Since 2.62.0 the SDK models both —
 * {@link OutputTokensDetails}, reachable from {@link Usage} and {@link MessageDeltaUsage} alike — and the untyped read
 * stopped finding anything, because a modelled field never lands among the additional properties. This class is where
 * that change lands; the one class the old comment promised would have to change.
 *
 * <p>
 * The typed accessors ({@code outputTokensDetails()}, {@code thinkingTokens()}) are <strong>not</strong> what is used
 * here: both raise {@code AnthropicInvalidDataException} when the server sends a shape they did not expect, and this
 * counter must never do that. The raw accessors ({@code _outputTokensDetails()}, {@code _thinkingTokens()}) return the
 * same values through {@link JsonField}, whose {@code asKnown()} and {@code asNumber()} answer an empty
 * {@link Optional} for exactly the shapes the typed pair would have thrown on.
 *
 * <p>
 * <strong>It never throws.</strong> A missing key, a wrong shape, a non-numeric value, an overflow — all yield
 * {@code 0}, because a counter that could not be read must not fail a turn that otherwise succeeded. The same split
 * the OpenAI path draws: accounting degrades, identity throws.
 *
 * <p>
 * The value is reported and <strong>neither priced nor added to {@code totalTokens}</strong>: thinking tokens are
 * billed as output tokens, so they are already contained in {@code output_tokens}.
 */
final class AnthropicUsages {

    private AnthropicUsages() {
    }

    /**
     * Reads the thinking-token count off a blocking response's usage.
     *
     * @param usage
     *            the usage document (may be null)
     * @return the count, or {@code 0} when it is absent or unreadable
     */
    static int thinkingTokens(Usage usage) {
        return usage == null ? 0 : thinkingTokens(usage._outputTokensDetails());
    }

    /**
     * Reads the thinking-token count off a streamed {@code message_delta}'s usage.
     *
     * @param usage
     *            the usage document (may be null)
     * @return the count, or {@code 0} when it is absent or unreadable
     */
    static int thinkingTokens(MessageDeltaUsage usage) {
        return usage == null ? 0 : thinkingTokens(usage._outputTokensDetails());
    }

    private static int thinkingTokens(JsonField<OutputTokensDetails> details) {
        if (details == null) {
            return 0;
        }
        // asKnown() is empty when the breakdown is absent, null, or some shape other than an object — the set of
        // cases outputTokensDetails() would have thrown on. asNumber() is empty on the same terms one level down,
        // which covers a breakdown carrying no counter or a counter that is not a number.
        final Optional<OutputTokensDetails> breakdown = details.asKnown();
        if (breakdown.isEmpty()) {
            return 0;
        }
        return breakdown.get()._thinkingTokens().asNumber().map(AnthropicUsages::toIntOrZero).orElse(0);
    }

    private static int toIntOrZero(Number value) {
        final long asLong = value.longValue();
        if (asLong < 0 || asLong > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) asLong;
    }
}
