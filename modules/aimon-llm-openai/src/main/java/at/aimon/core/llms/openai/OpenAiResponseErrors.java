package at.aimon.core.llms.openai;

import java.util.Optional;
import java.util.Set;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFailedEvent;

import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmInvalidRequestException;

/**
 * Turns a Responses provider failure into the exception {@link OpenAIExceptionMapper} would have produced.
 *
 * <p>
 * <strong>On this endpoint a provider failure arrives as data, not as an SDK exception</strong>, and that is the whole
 * reason this class exists. The SDK's SSE decoder throws only when the decoded payload has a <em>top-level</em>
 * {@code "error"} key. Chat Completions' mid-stream failure has that shape, which is why the exception mapper's
 * {@code SseException} branch is reachable there at all. None of the three Responses shapes does: a
 * {@link ResponseErrorEvent} carries {@code code}/{@code message}/{@code param}/{@code sequence_number} and no
 * {@code error} key; a {@link ResponseFailedEvent} nests its error inside {@code response}; and a blocking call that
 * returns HTTP 200 with {@code status: "failed"} is an ordinary return value.
 *
 * <p>
 * Left unhandled, the same server-side condition that yields a retryable {@code LlmOverloadedException} on the
 * blocking/Chat path would yield <em>no exception at all</em> here — and both ways of not-throwing are bad in their
 * own way. Leaving the aggregator unclosed escapes as {@code IllegalStateException} from <em>outside</em> the client's
 * try, unmapped and reporting AIMON's internal state instead of the provider's error; closing it without throwing
 * turns the failure into a <em>silent success</em> that the executor accepts as the assistant's final answer.
 *
 * <p>
 * So this class builds the exception and the caller throws it <em>from inside the client's try</em>, where the
 * existing cascade classifies it — including the second catch's {@code isCancelled()} check, which is what makes a
 * provider failure that coincides with a local cancellation report as a cancellation rather than a server fault.
 */
final class OpenAiResponseErrors {

    /**
     * The codes treated as transient.
     *
     * <p>
     * Stated as a transient set with "anything unrecognised keeps parity" rather than as a list of terminal codes, so
     * a code OpenAI adds later inherits today's retryable behaviour instead of silently becoming non-retryable.
     * {@code vector_store_timeout} is here for shape rather than for use — AIMON does not use file search.
     */
    private static final Set<String> TRANSIENT_CODES = Set.of("server_error", "rate_limit_exceeded",
            "vector_store_timeout");

    private OpenAiResponseErrors() {
    }

    /**
     * Builds the exception for a {@code response.error} stream event.
     *
     * @param event
     *            the error event (must not be null)
     * @param messagePrefix
     *            the prefix the mapper prepends (must not be null)
     * @return the exception to throw from inside the client's try
     */
    static LlmClientException fromErrorEvent(ResponseErrorEvent event, String messagePrefix) {
        // message() is a required accessor; read it defensively because a malformed event must still produce a
        // classified failure rather than an OpenAIInvalidDataException from inside the mapper.
        final String message = event._message().asKnown().orElse("");
        return classify(event.code().orElse(null), message, messagePrefix, null);
    }

    /**
     * Builds the exception for a {@code response.failed} stream event.
     *
     * @param event
     *            the failed event (must not be null)
     * @param messagePrefix
     *            the prefix the mapper prepends (must not be null)
     * @return the exception to throw from inside the client's try
     */
    static LlmClientException fromFailedEvent(ResponseFailedEvent event, String messagePrefix) {
        return fromResponse(event.response(), messagePrefix);
    }

    /**
     * Builds the exception for a response whose {@code status} is not a terminal success.
     *
     * @param response
     *            the response (must not be null)
     * @param messagePrefix
     *            the prefix the mapper prepends (must not be null)
     * @return the exception to throw from inside the client's try
     */
    static LlmClientException fromResponse(Response response, String messagePrefix) {
        // Every accessor here is read defensively. These are required fields, and the whole job of this class is to
        // turn a provider failure into a classified exception -- so a malformed error object must not raise an
        // OpenAIInvalidDataException on the way and replace the provider's failure with our own.
        final Optional<ResponseError> error = response.error();
        final String code = error.flatMap(e -> e._code().asKnown()).flatMap(c -> c._value().asString()).orElse(null);
        final String message = error.flatMap(e -> e._message().asKnown()).orElseGet(
                () -> "OpenAI response ended with status " + statusOf(response) + " and carried no error object");
        return classify(code, message, messagePrefix, null);
    }

    /**
     * Whether a response status means the turn did not produce an answer and must be reported as a failure rather
     * than converted.
     *
     * @param response
     *            the response (must not be null)
     * @return {@code true} for {@code failed}, {@code cancelled}, {@code in_progress} and {@code queued}
     */
    static boolean isFailureStatus(Response response) {
        final String status = statusOf(response);
        return "failed".equals(status) || "cancelled".equals(status) || "in_progress".equals(status)
                || "queued".equals(status);
    }

    /** The raw {@code status} wire value, or {@code "unknown"} when it is absent or not a string. */
    private static String statusOf(Response response) {
        return response.status().flatMap(status -> status._value().asString()).orElse("unknown");
    }

    /**
     * Classifies a provider error code.
     *
     * <p>
     * The default is exact parity with the Chat path: {@code OpenAIExceptionMapper.mapMidStreamError} turns
     * {@code rate_limit_exceeded} into a rate-limit exception and everything else into the retryable overloaded one,
     * which is what the blocking path produces for the equivalent condition.
     *
     * <p>
     * The one deliberate divergence is the request-content family. {@code ResponseError.Code} enumerates codes —
     * {@code invalid_prompt}, the {@code invalid_image*} group, {@code image_too_large}, {@code bio_policy},
     * {@code data_residency_mismatch} — that a blocking call would have rejected as a 400, i.e. non-retryable.
     * Sending those through the mid-stream mapper would make a permanently-broken request retry until the policy
     * gives up.
     */
    private static LlmClientException classify(String code, String message, String messagePrefix, Throwable cause) {
        if (isTerminalContentFailure(code)) {
            return new LlmInvalidRequestException(compose(messagePrefix, code, message), cause);
        }
        return OpenAIExceptionMapper.mapMidStreamError(null, code, message, null, messagePrefix, cause);
    }

    private static boolean isTerminalContentFailure(String code) {
        if (code == null || TRANSIENT_CODES.contains(code)) {
            return false;
        }
        // A code this SDK version does not model keeps parity (retryable) rather than becoming terminal, so the
        // failure mode of being wrong about a future code is bounded.
        return ResponseError.Code.of(code).value() != ResponseError.Code.Value._UNKNOWN;
    }

    private static String compose(String prefix, String code, String message) {
        final StringBuilder sb = new StringBuilder(prefix);
        sb.append(": provider error");
        if (code != null && !code.isBlank()) {
            sb.append(" [").append(code).append(']');
        }
        if (message != null && !message.isBlank()) {
            sb.append(" - ").append(message);
        }
        return sb.toString();
    }
}
