package at.aimon.core.llms.openai;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.openai.errors.UnauthorizedException;

import at.aimon.core.llm.exception.LlmAuthException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.exception.LlmRateLimitedException;

/**
 * Maps OpenAI SDK errors to the provider-neutral {@link LlmClientException} taxonomy.
 *
 * <p>
 * The mapper is package-private and stateless. It exposes two entry points:
 *
 * <ul>
 * <li>{@link #map(Throwable, String)} — inspects a concrete SDK exception and delegates to
 * {@link #mapFromHttpDetails(int, String, String, String)} once the HTTP metadata has been extracted.
 * <li>{@link #mapFromHttpDetails(int, String, String, String)} — a pure helper that tests can call directly without
 * having to instantiate Kotlin-only SDK exception types.
 * </ul>
 *
 * <p>
 * The mapping matrix follows the canonical OpenAI error codes:
 *
 * <table>
 * <caption>Status-code / error-code mapping</caption>
 * <tr>
 * <th>Signal</th>
 * <th>Mapped exception</th>
 * </tr>
 * <tr>
 * <td>429</td>
 * <td>{@link LlmRateLimitedException}</td>
 * </tr>
 * <tr>
 * <td>500 / 502 / 503 / 504 or {@code overloaded}</td>
 * <td>{@link LlmOverloadedException}</td>
 * </tr>
 * <tr>
 * <td>400 with {@code context_length_exceeded}</td>
 * <td>{@link LlmPromptTooLongException}</td>
 * </tr>
 * <tr>
 * <td>401 / 403</td>
 * <td>{@link LlmAuthException}</td>
 * </tr>
 * <tr>
 * <td>400 otherwise</td>
 * <td>{@link LlmInvalidRequestException}</td>
 * </tr>
 * <tr>
 * <td>anything else</td>
 * <td>base {@link LlmClientException}</td>
 * </tr>
 * </table>
 */
final class OpenAIExceptionMapper {

    private static final Logger log = LoggerFactory.getLogger(OpenAIExceptionMapper.class);

    private static final String CONTEXT_LENGTH_CODE = "context_length_exceeded";
    private static final String CONTEXT_LENGTH_MESSAGE_MARKER = "maximum context length";
    private static final String OVERLOADED_MARKER = "overloaded";
    private static final String RATE_LIMIT_MARKER = "rate_limit";

    private static final Pattern MODEL_LIMIT_PATTERN = Pattern.compile("maximum context length is\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUESTED_TOKENS_PATTERN = Pattern.compile("resulted in\\s+(\\d+)\\s+tokens",
            Pattern.CASE_INSENSITIVE);

    private OpenAIExceptionMapper() {
        // Static-only utility.
    }

    /**
     * Maps an SDK error to the provider-neutral {@link LlmClientException} taxonomy.
     *
     * @param sdkError
     *            the SDK exception (must not be null)
     * @param messagePrefix
     *            a human-readable prefix that will be prepended to the resulting exception message (must not be null)
     * @return the mapped exception — never {@code null}
     * @throws NullPointerException
     *             if {@code sdkError} or {@code messagePrefix} is null
     */
    static LlmClientException map(Throwable sdkError, String messagePrefix) {
        Objects.requireNonNull(sdkError, "sdkError must not be null");
        Objects.requireNonNull(messagePrefix, "messagePrefix must not be null");

        // Direct SDK type recognition first — preserves fidelity (Retry-After header, error code, etc.).
        if (sdkError instanceof RateLimitException rle) {
            return new LlmRateLimitedException(buildMessage(messagePrefix, rle),
                    parseRetryAfterFromHeaders(rle.headers()), sdkError);
        }
        if (sdkError instanceof UnauthorizedException ue) {
            return new LlmAuthException(buildMessage(messagePrefix, ue), sdkError);
        }
        if (sdkError instanceof PermissionDeniedException pde) {
            return new LlmAuthException(buildMessage(messagePrefix, pde), sdkError);
        }
        if (sdkError instanceof BadRequestException bre) {
            String errorCode = bre.code().orElse(null);
            String message = safeMessage(bre);
            if (isContextLengthError(errorCode, message)) {
                return new LlmPromptTooLongException(buildMessage(messagePrefix, bre), extractRequestedTokens(message),
                        extractModelLimitTokens(message), sdkError);
            }
            return new LlmInvalidRequestException(buildMessage(messagePrefix, bre), sdkError);
        }
        if (sdkError instanceof InternalServerException ise) {
            return new LlmOverloadedException(buildMessage(messagePrefix, ise), sdkError);
        }
        if (sdkError instanceof SseException sse) {
            // A mid-stream error event arrives on an already-opened SSE stream, so the SDK stamps this exception with
            // the stream-open status (HTTP 200) rather than the failure's real status. Routing it through the generic
            // OpenAIServiceException branch below would classify it by that 200 and fall through to a non-retryable
            // base
            // LlmClientException — diverging from the blocking chat().completions().create() path, which surfaces the
            // same server-side failure as a 5xx -> retryable LlmOverloadedException. Classify from the error payload
            // instead so a cancellable (streaming-re-routed) call keeps the same retry budget as the blocking one.
            return mapMidStreamError(sse.type().orElse(null), sse.code().orElse(null), safeMessage(sse),
                    parseRetryAfterFromHeaders(sse.headers()), messagePrefix, sdkError);
        }
        if (sdkError instanceof OpenAIServiceException svc) {
            return mapFromHttpDetails(svc.statusCode(), svc.code().orElse(null), safeMessage(svc), messagePrefix,
                    sdkError);
        }

        log.debug("OpenAI SDK exception has no dedicated mapping; falling back to base LlmClientException: {}",
                sdkError.getClass().getName());
        return new LlmClientException(buildMessage(messagePrefix, sdkError), sdkError);
    }

    /**
     * Maps a plain HTTP-detail tuple to the {@link LlmClientException} taxonomy without touching SDK types.
     *
     * <p>
     * This variant is used both as a fallback from {@link #map(Throwable, String)} and directly by tests, where
     * constructing real SDK exceptions is impractical because their constructors are Kotlin-only.
     *
     * @param httpStatus
     *            the HTTP status code returned by the provider
     * @param errorCode
     *            the provider-specific error code (may be null)
     * @param message
     *            the provider-supplied error message (may be null)
     * @param messagePrefix
     *            a human-readable prefix that will be prepended to the resulting exception message (must not be null)
     * @return the mapped exception — never {@code null}
     * @throws NullPointerException
     *             if {@code messagePrefix} is null
     */
    static LlmClientException mapFromHttpDetails(int httpStatus, String errorCode, String message,
            String messagePrefix) {
        return mapFromHttpDetails(httpStatus, errorCode, message, messagePrefix, null);
    }

    /**
     * Package-private variant that additionally accepts the original cause so the mapped exception can preserve the
     * stack trace of the underlying SDK error.
     *
     * @param httpStatus
     *            the HTTP status code returned by the provider
     * @param errorCode
     *            the provider-specific error code (may be null)
     * @param message
     *            the provider-supplied error message (may be null)
     * @param messagePrefix
     *            a human-readable prefix that will be prepended to the resulting exception message (must not be null)
     * @param cause
     *            the underlying SDK error (may be null)
     * @return the mapped exception — never {@code null}
     * @throws NullPointerException
     *             if {@code messagePrefix} is null
     */
    static LlmClientException mapFromHttpDetails(int httpStatus, String errorCode, String message, String messagePrefix,
            Throwable cause) {
        Objects.requireNonNull(messagePrefix, "messagePrefix must not be null");

        String composed = compose(messagePrefix, httpStatus, errorCode, message);

        if (httpStatus == 429) {
            return new LlmRateLimitedException(composed, null, cause);
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return new LlmAuthException(composed, cause);
        }
        if (httpStatus == 500 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504 || httpStatus == 529
                || containsIgnoreCase(message, OVERLOADED_MARKER) || containsIgnoreCase(errorCode, OVERLOADED_MARKER)) {
            return new LlmOverloadedException(composed, cause);
        }
        if (httpStatus == 400) {
            if (isContextLengthError(errorCode, message)) {
                return new LlmPromptTooLongException(composed, extractRequestedTokens(message),
                        extractModelLimitTokens(message), cause);
            }
            return new LlmInvalidRequestException(composed, cause);
        }
        return new LlmClientException(composed, cause);
    }

    /**
     * Classifies a <em>mid-stream</em> error — one delivered as an SSE error event on an already-opened HTTP-200
     * stream,
     * where the transport status no longer reflects the failure. Such an error can only be a server-side condition that
     * occurred after generation started (the request itself was already accepted), so it is mapped to the same
     * retryable taxonomy the blocking path would produce for the equivalent 5xx: {@link LlmRateLimitedException} when
     * the payload signals a rate limit, otherwise {@link LlmOverloadedException}. This keeps the gateway's retry budget
     * intact for calls re-routed through streaming (non-streaming in-flight abort).
     *
     * <p>
     * Package-private so tests can exercise the classification directly without constructing a Kotlin-only
     * {@code SseException}.
     *
     * @param errorType
     *            the provider error {@code type} from the mid-stream payload (may be null)
     * @param errorCode
     *            the provider error {@code code} from the mid-stream payload (may be null)
     * @param message
     *            the provider-supplied error message (may be null)
     * @param retryAfter
     *            the {@code Retry-After} hint parsed from the response headers (may be null)
     * @param messagePrefix
     *            a human-readable prefix prepended to the resulting exception message (must not be null)
     * @param cause
     *            the underlying SDK error (may be null)
     * @return the mapped exception — never {@code null}
     * @throws NullPointerException
     *             if {@code messagePrefix} is null
     */
    static LlmClientException mapMidStreamError(String errorType, String errorCode, String message, Duration retryAfter,
            String messagePrefix, Throwable cause) {
        Objects.requireNonNull(messagePrefix, "messagePrefix must not be null");

        final String composed = composeMidStream(messagePrefix, errorType, errorCode, message);
        if (containsIgnoreCase(errorType, RATE_LIMIT_MARKER) || containsIgnoreCase(errorCode, RATE_LIMIT_MARKER)) {
            return new LlmRateLimitedException(composed, retryAfter, cause);
        }
        return new LlmOverloadedException(composed, cause);
    }

    private static String composeMidStream(String prefix, String errorType, String errorCode, String message) {
        final StringBuilder sb = new StringBuilder(prefix);
        sb.append(": mid-stream error");
        final String marker = errorType != null && !errorType.isBlank() ? errorType : errorCode;
        if (marker != null && !marker.isBlank()) {
            sb.append(" [").append(marker).append(']');
        }
        if (message != null && !message.isBlank()) {
            sb.append(" - ").append(message);
        }
        return sb.toString();
    }

    /**
     * Parses a {@code Retry-After} header value. Supports both delta-seconds and RFC 1123 HTTP-date forms.
     *
     * @param retryAfterHeader
     *            the raw header value (may be null or blank)
     * @return the parsed duration, or {@link Optional#empty()} when the value cannot be interpreted. A header pointing
     *         to a past date yields {@link Duration#ZERO}.
     */
    static Optional<Duration> parseRetryAfter(String retryAfterHeader) {
        if (retryAfterHeader == null) {
            return Optional.empty();
        }
        String trimmed = retryAfterHeader.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        // delta-seconds form (RFC 7231 §7.1.3).
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) {
                return Optional.of(Duration.ZERO);
            }
            return Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException ignored) {
            // Fall through to HTTP-date parsing.
        }

        // HTTP-date form (RFC 1123 / RFC 7231 §7.1.1.1).
        try {
            ZonedDateTime target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            Instant now = Instant.now();
            Duration delta = Duration.between(now, target.toInstant());
            if (delta.isNegative()) {
                return Optional.of(Duration.ZERO);
            }
            return Optional.of(delta);
        } catch (DateTimeParseException e) {
            log.debug("Unable to parse Retry-After header '{}': {}", trimmed, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extracts a {@code Retry-After} value from the SDK {@link Headers} object.
     *
     * @param headers
     *            the headers exposed by the SDK exception (may be null)
     * @return the parsed duration, or {@code null} when unavailable
     */
    private static Duration parseRetryAfterFromHeaders(Headers headers) {
        if (headers == null) {
            return null;
        }
        List<String> values;
        try {
            values = headers.values("Retry-After");
        } catch (RuntimeException e) {
            log.debug("Unable to read Retry-After header from SDK response: {}", e.getMessage());
            return null;
        }
        if (values == null || values.isEmpty()) {
            return null;
        }
        return parseRetryAfter(values.get(0)).orElse(null);
    }

    private static boolean isContextLengthError(String errorCode, String message) {
        if (errorCode != null && CONTEXT_LENGTH_CODE.equalsIgnoreCase(errorCode)) {
            return true;
        }
        return containsIgnoreCase(message, CONTEXT_LENGTH_MESSAGE_MARKER);
    }

    private static Integer extractRequestedTokens(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = REQUESTED_TOKENS_PATTERN.matcher(message);
        if (m.find()) {
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer extractModelLimitTokens(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = MODEL_LIMIT_PATTERN.matcher(message);
        if (m.find()) {
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String compose(String prefix, int httpStatus, String errorCode, String message) {
        StringBuilder sb = new StringBuilder(prefix);
        sb.append(": HTTP ").append(httpStatus);
        if (errorCode != null && !errorCode.isBlank()) {
            sb.append(" [").append(errorCode).append(']');
        }
        if (message != null && !message.isBlank()) {
            sb.append(" - ").append(message);
        }
        return sb.toString();
    }

    private static String buildMessage(String prefix, Throwable sdkError) {
        String raw = safeMessage(sdkError);
        if (raw == null || raw.isBlank()) {
            return prefix + ": " + sdkError.getClass().getSimpleName();
        }
        return prefix + ": " + raw;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return null;
        }
        return t.getMessage();
    }
}
