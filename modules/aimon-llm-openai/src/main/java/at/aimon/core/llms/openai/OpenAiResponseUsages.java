package at.aimon.core.llms.openai;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.core.JsonField;
import com.openai.models.responses.ResponseUsage;

import at.aimon.core.llm.TokenUsage;

/**
 * Reads a Responses usage document into {@link TokenUsage}, degrading rather than throwing.
 *
 * <p>
 * <strong>Every counter on {@link ResponseUsage} is a required accessor, not just the reasoning one</strong>, and the
 * trap is five deep: {@code inputTokens()}, {@code outputTokens()} and {@code totalTokens()} at the top level,
 * {@code outputTokensDetails()} beside them, and {@code reasoningTokens()} one level down inside that. A usage
 * document missing any of them throws {@code OpenAIInvalidDataException} at access time — before the details are ever
 * reached, in the top-level case.
 *
 * <p>
 * So every level is read through its raw {@code JsonField} accessor and an absent value counts as zero. That is the
 * accounting half of a deliberate split: <em>accounting degrades, identity throws</em>. A missing token counter must
 * not fail a call that otherwise succeeded — cost under-reports and the turn completes — whereas a
 * {@code function_call} missing its {@code call_id} is a provider fault that has to surface, and does, because the
 * tool-call accessors are read normally.
 */
final class OpenAiResponseUsages {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponseUsages.class);

    private OpenAiResponseUsages() {
    }

    /**
     * Converts a usage document, if there is one.
     *
     * @param usage
     *            the usage reported by the response (must not be null; may be empty)
     * @return the token usage, {@link TokenUsage#empty()} when absent or out of {@code int} range
     */
    static TokenUsage toTokenUsage(Optional<ResponseUsage> usage) {
        if (usage.isEmpty()) {
            return TokenUsage.empty();
        }
        final ResponseUsage reported = usage.get();
        final long inputTokens = asLongOrZero(reported._inputTokens());
        final long outputTokens = asLongOrZero(reported._outputTokens());
        final long totalTokens = asLongOrZero(reported._totalTokens());
        final long reasoningTokens = reported._outputTokensDetails().asKnown()
                .map(details -> asLongOrZero(details._reasoningTokens())).orElse(0L);

        try {
            // The same narrowing guard the Chat path already has, applied to the fourth counter too: the SDK reports
            // longs and TokenUsage takes ints.
            //
            // The Math.max is the one place "degrade to zero" cannot be taken literally. TokenUsage enforces
            // total >= prompt + completion and *throws* otherwise, so a document that reports input and output but
            // omits total cannot yield total = 0 -- it would fail the call, which is precisely what this class exists
            // to prevent. Reporting the sum keeps the counters that were actually read and keeps the invariant true.
            return TokenUsage.of(Math.toIntExact(inputTokens), Math.toIntExact(outputTokens),
                    Math.toIntExact(Math.max(totalTokens, inputTokens + outputTokens)),
                    Math.toIntExact(reasoningTokens));
        } catch (ArithmeticException e) {
            log.warn("Token count exceeded Integer range; falling back to empty usage: {}", e.getMessage());
            return TokenUsage.empty();
        }
    }

    /**
     * Reads one counter, treating an absent or non-numeric field as zero.
     *
     * <p>
     * {@code asKnown()} is empty for a missing field and for a field whose JSON type is not the expected one, which is
     * exactly the set of cases that would otherwise raise {@code OpenAIInvalidDataException}.
     */
    private static long asLongOrZero(JsonField<Long> field) {
        return field.asKnown().orElse(0L);
    }
}
