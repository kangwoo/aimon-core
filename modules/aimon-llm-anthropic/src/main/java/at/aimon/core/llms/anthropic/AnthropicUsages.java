package at.aimon.core.llms.anthropic;

import java.util.Map;
import java.util.Optional;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.Usage;

/**
 * Reads Anthropic's thinking-token counter, which this SDK version does not model.
 *
 * <p>
 * The API reports it as {@code usage.output_tokens_details.thinking_tokens}, and on the streaming path the breakdown
 * appears only on the final {@code message_delta} event. Neither {@link Usage} nor {@link MessageDeltaUsage} in SDK
 * 2.13.0 has an accessor for it — the string appears nowhere in the sources jar — but both carry
 * {@code @JsonAnySetter}/{@code @JsonAnyGetter}, so the field lands in {@code _additionalProperties()} and can be read
 * from there. When the SDK grows the accessor, one class changes.
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

    private static final String OUTPUT_TOKENS_DETAILS = "output_tokens_details";
    private static final String THINKING_TOKENS = "thinking_tokens";

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
        return usage == null ? 0 : thinkingTokens(usage._additionalProperties());
    }

    /**
     * Reads the thinking-token count off a streamed {@code message_delta}'s usage.
     *
     * @param usage
     *            the usage document (may be null)
     * @return the count, or {@code 0} when it is absent or unreadable
     */
    static int thinkingTokens(MessageDeltaUsage usage) {
        return usage == null ? 0 : thinkingTokens(usage._additionalProperties());
    }

    private static int thinkingTokens(Map<String, JsonValue> additionalProperties) {
        if (additionalProperties == null) {
            return 0;
        }
        final JsonValue details = additionalProperties.get(OUTPUT_TOKENS_DETAILS);
        if (details == null) {
            return 0;
        }
        // asObject() and asNumber() are empty both for an absent value and for one whose JSON type is not the
        // expected one, which is exactly the set of shapes that would otherwise raise. They are read through Object
        // and pattern-matched because JsonValue reaches Java as a raw subtype of JsonField, so their return types
        // erase — a cast would be unchecked, and the instanceof is honest about the same check at no extra cost.
        final Object fields = unwrap(details.asObject());
        if (!(fields instanceof Map<?, ?> object)) {
            return 0;
        }
        if (!(object.get(THINKING_TOKENS) instanceof JsonValue thinkingTokens)) {
            return 0;
        }
        final Object count = unwrap(thinkingTokens.asNumber());
        return count instanceof Number number ? toIntOrZero(number) : 0;
    }

    /** Reads a raw {@link Optional} without an unchecked cast; see the comment at the call site. */
    private static Object unwrap(Optional<?> value) {
        return value.isPresent() ? value.get() : null;
    }

    private static int toIntOrZero(Number value) {
        final long asLong = value.longValue();
        if (asLong < 0 || asLong > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) asLong;
    }
}
