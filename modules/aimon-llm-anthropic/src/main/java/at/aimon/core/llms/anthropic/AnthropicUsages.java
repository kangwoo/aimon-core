package at.aimon.core.llms.anthropic;

import java.util.Locale;
import java.util.Optional;

import com.anthropic.core.JsonField;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.Usage;

import at.aimon.core.llm.ServiceTier;
import at.aimon.core.llm.TokenUsage;

/**
 * Reads Anthropic's usage document: the whole-context prompt count, the prompt-cache counters, the applied service
 * tier, and the thinking-token counter.
 *
 * <h2>Prompt and cache</h2>
 *
 * <p>
 * Anthropic's {@code input_tokens} counts only the <em>uncached</em> input; cache reads
 * ({@code cache_read_input_tokens}) and cache writes ({@code cache_creation_input_tokens}) are reported beside it, not
 * inside it. {@link TokenUsage#getPromptTokens()} is the whole input context, so {@link #toTokenUsage} adds the two
 * back. A cache counter the response did not carry stays unmeasured rather than zero. Cache writes are reported as
 * unmeasured too when any of them used the one-hour TTL ({@code cache_creation.ephemeral_1h_input_tokens > 0}): those
 * are billed at a different rate than five-minute writes, and a single cache-write count would misprice them. The
 * tokens still count toward the prompt — they were processed — only their cache-write classification is withheld.
 *
 * <h2>Service tier</h2>
 *
 * <p>
 * {@code usage.service_tier} maps {@code standard} and {@code batch} to themselves. Anthropic's {@code priority} is a
 * capacity commitment, not the premium {@code fast} tier OpenAI's {@code priority} is, so it is preserved as reported
 * rather than mapped.
 *
 * <h2>Thinking tokens</h2>
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
     * Builds the usage from Anthropic's counters, restoring the whole-context prompt count.
     *
     * @param uncachedInput
     *            {@code input_tokens}
     * @param output
     *            {@code output_tokens}
     * @param cacheRead
     *            {@code cache_read_input_tokens}, or null when not reported
     * @param cacheWrite
     *            {@code cache_creation_input_tokens}, or null when not reported
     * @param cacheWriteSingleRate
     *            false when some writes used a TTL a single cache-write rate cannot price
     * @param thinking
     *            the thinking tokens, a subset of {@code output}
     * @throws ArithmeticException
     *             if a count exceeds {@code int} range
     */
    static TokenUsage toTokenUsage(long uncachedInput, long output, Long cacheRead, Long cacheWrite,
            boolean cacheWriteSingleRate, int thinking) {
        final int prompt = Math.toIntExact(
                uncachedInput + (cacheRead == null ? 0 : cacheRead) + (cacheWrite == null ? 0 : cacheWrite));
        final int out = Math.toIntExact(output);
        // Thinking tokens are billed as output tokens, so they are contained in `out` rather than added to it.
        return TokenUsage.of(prompt, out, Math.addExact(prompt, out), thinking).withCacheTokens(
                cacheRead == null ? null : Math.toIntExact(cacheRead),
                cacheWrite == null || !cacheWriteSingleRate ? null : Math.toIntExact(cacheWrite));
    }

    /**
     * Builds the usage of a blocking response.
     *
     * @throws ArithmeticException
     *             if a count exceeds {@code int} range
     */
    static TokenUsage toTokenUsage(Usage usage) {
        return toTokenUsage(usage.inputTokens(), usage.outputTokens(), counter(usage._cacheReadInputTokens()),
                counter(usage._cacheCreationInputTokens()), cacheWriteSingleRate(usage._cacheCreation()),
                thinkingTokens(usage));
    }

    /**
     * Reads one cache counter.
     *
     * @return the count, or null when absent, null, non-numeric or negative
     */
    static Long counter(JsonField<Long> field) {
        if (field == null) {
            return null;
        }
        return field.asKnown().filter(value -> value >= 0).orElse(null);
    }

    /**
     * Reports whether every cache write can be priced at the single (five-minute) cache-write rate.
     *
     * @return false when the breakdown reports one-hour writes; true when it reports none or is absent
     */
    static boolean cacheWriteSingleRate(JsonField<CacheCreation> breakdown) {
        if (breakdown == null) {
            return true;
        }
        return breakdown.asKnown().flatMap(creation -> creation._ephemeral1hInputTokens().asKnown())
                .map(longTtl -> longTtl == 0).orElse(true);
    }

    /**
     * Reads the tier Anthropic applied.
     *
     * @return the tier, or null when not reported
     */
    static ServiceTier serviceTier(Usage usage) {
        if (usage == null) {
            return null;
        }
        try {
            return usage._serviceTier().asKnown().flatMap(tier -> tier._value().asString())
                    .map(value -> value.trim().toLowerCase(Locale.ROOT)).flatMap(ServiceTier::of).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads the model Anthropic served the message with.
     *
     * @return the model, or null when not reported
     */
    static String model(Message message) {
        // Never throws, like every read here: a message missing the field is "not reported", not a failed call.
        try {
            return message._model().asKnown().flatMap(model -> model._value().asString()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
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
