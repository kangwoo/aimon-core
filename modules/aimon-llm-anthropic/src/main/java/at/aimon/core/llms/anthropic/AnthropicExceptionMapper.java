package at.aimon.core.llms.anthropic;

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

import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.SseException;

import at.aimon.core.llm.exception.LlmAuthException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.exception.LlmRateLimitedException;
import at.aimon.core.llms.anthropic.exception.AnthropicException;

/**
 * Maps Anthropic SDK errors to the provider-neutral {@link LlmClientException} taxonomy.
 *
 * <p>
 * Without this mapping every SDK failure would be wrapped in a flat {@link AnthropicException}, which
 * {@link at.aimon.core.llm.retry.LlmRetryPolicy#isRetryable(LlmClientException)} does not recognise — so retry and
 * model-fallback were effectively no-ops for the Anthropic provider. Recognising rate-limit (429) and overload
 * (500&ndash;504 / 529) responses as their retryable counterparts restores that resilience, and preserving the
 * {@code Retry-After} header lets the gateway honour the server-suggested backoff.
 *
 * <p>
 * The mapper is package-private and stateless. It exposes two entry points:
 *
 * <ul>
 * <li>{@link #map(Throwable, String)} — inspects a concrete SDK exception, extracting the HTTP status, body, and
 * headers from any {@link AnthropicServiceException} before delegating to
 * {@link #mapFromHttpDetails(int, String, String, Duration, Throwable)}.
 * <li>{@link #mapFromHttpDetails(int, String, String)} — a pure helper that tests can call directly without having to
 * construct the Kotlin-only SDK exception types.
 * </ul>
 *
 * <p>
 * The mapping matrix follows the canonical Anthropic error codes:
 *
 * <table>
 * <caption>Status-code / marker mapping</caption>
 * <tr>
 * <th>Signal</th>
 * <th>Mapped exception</th>
 * </tr>
 * <tr>
 * <td>429</td>
 * <td>{@link LlmRateLimitedException} (carrying the parsed {@code Retry-After})</td>
 * </tr>
 * <tr>
 * <td>500 / 502 / 503 / 504 / 529 or an {@code overloaded} marker</td>
 * <td>{@link LlmOverloadedException}</td>
 * </tr>
 * <tr>
 * <td>400 / 422 whose body signals a context-length overflow</td>
 * <td>{@link LlmPromptTooLongException}</td>
 * </tr>
 * <tr>
 * <td>401 / 403</td>
 * <td>{@link LlmAuthException}</td>
 * </tr>
 * <tr>
 * <td>400 / 422 otherwise</td>
 * <td>{@link LlmInvalidRequestException}</td>
 * </tr>
 * <tr>
 * <td>anything else (including non-HTTP SDK errors)</td>
 * <td>{@link AnthropicException}</td>
 * </tr>
 * </table>
 */
final class AnthropicExceptionMapper {

    private static final Logger log = LoggerFactory.getLogger(AnthropicExceptionMapper.class);

    private static final String OVERLOADED_MARKER = "overloaded";
    private static final String RATE_LIMIT_MARKER = "rate_limit";
    private static final String PROMPT_TOO_LONG_MARKER = "prompt is too long";
    private static final String CONTEXT_LENGTH_MESSAGE_MARKER = "maximum context length";

    /** SDK messages are formatted as {@code "<status>: <body-json>"}; strip the redundant leading status. */
    private static final Pattern LEADING_STATUS_PATTERN = Pattern.compile("^\\d{3}:\\s*");

    /** Anthropic phrasing: {@code "prompt is too long: 210000 tokens > 200000 maximum"}. */
    private static final Pattern REQUESTED_TOKENS_PATTERN = Pattern.compile("(\\d+)\\s+tokens",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL_LIMIT_PATTERN = Pattern.compile(">\\s*(\\d+)\\s+maximum",
            Pattern.CASE_INSENSITIVE);

    private AnthropicExceptionMapper() {
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

        // A mid-stream error event arrives on an already-opened SSE stream, so the SDK stamps this exception with the
        // stream-open status (HTTP 200) rather than the failure's real status. Routing it through the generic
        // AnthropicServiceException branch below would classify it by that 200 and fall through to the non-retryable
        // base AnthropicException — diverging from the blocking messages().create() path, which surfaces the same
        // server-side failure as a 5xx -> retryable LlmOverloadedException. Classify from the error body instead so a
        // cancellable (streaming-re-routed) call keeps the same retry budget as the blocking one.
        if (sdkError instanceof SseException sse) {
            return mapMidStreamError(bodyText(sse), parseRetryAfterFromHeaders(headersOf(sse)), messagePrefix,
                    sdkError);
        }

        // Every HTTP-carrying SDK error (429, 5xx, 400, 401, 403, 404, 422, unexpected) extends
        // AnthropicServiceException, exposing statusCode()/headers()/body() uniformly.
        if (sdkError instanceof AnthropicServiceException svc) {
            final Duration retryAfter = parseRetryAfterFromHeaders(headersOf(svc));
            return mapFromHttpDetails(statusCodeOf(svc), bodyText(svc), messagePrefix, retryAfter, sdkError);
        }

        // Non-HTTP SDK errors (I/O, invalid-data, SSE, generic retryable marker) keep the module's base type.
        log.debug("Anthropic SDK exception has no HTTP metadata; falling back to AnthropicException: {}",
                sdkError.getClass().getName());
        return new AnthropicException(buildMessage(messagePrefix, sdkError), sdkError);
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
     * @param body
     *            the provider-supplied error body / message (may be null)
     * @param messagePrefix
     *            a human-readable prefix that will be prepended to the resulting exception message (must not be null)
     * @return the mapped exception — never {@code null}
     * @throws NullPointerException
     *             if {@code messagePrefix} is null
     */
    static LlmClientException mapFromHttpDetails(int httpStatus, String body, String messagePrefix) {
        return mapFromHttpDetails(httpStatus, body, messagePrefix, null, null);
    }

    /**
     * Package-private variant that additionally accepts the parsed {@code Retry-After} hint and the original cause so
     * the mapped exception can carry the server backoff and preserve the SDK stack trace.
     *
     * @param httpStatus
     *            the HTTP status code returned by the provider
     * @param body
     *            the provider-supplied error body / message (may be null)
     * @param messagePrefix
     *            a human-readable prefix that will be prepended to the resulting exception message (must not be null)
     * @param retryAfter
     *            the {@code Retry-After} hint parsed from the response headers (may be null)
     * @param cause
     *            the underlying SDK error (may be null)
     * @return the mapped exception — never {@code null}
     * @throws NullPointerException
     *             if {@code messagePrefix} is null
     */
    static LlmClientException mapFromHttpDetails(int httpStatus, String body, String messagePrefix, Duration retryAfter,
            Throwable cause) {
        Objects.requireNonNull(messagePrefix, "messagePrefix must not be null");

        final String composed = compose(messagePrefix, httpStatus, body);

        if (httpStatus == 429) {
            return new LlmRateLimitedException(composed, retryAfter, cause);
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return new LlmAuthException(composed, cause);
        }
        if (isOverloaded(httpStatus, body)) {
            return new LlmOverloadedException(composed, cause);
        }
        if (httpStatus == 400 || httpStatus == 422) {
            if (isContextLengthError(body)) {
                return new LlmPromptTooLongException(composed, extractRequestedTokens(body),
                        extractModelLimitTokens(body), cause);
            }
            return new LlmInvalidRequestException(composed, cause);
        }
        return new AnthropicException(composed, cause);
    }

    /**
     * Classifies a <em>mid-stream</em> error — one delivered as an SSE error event on an already-opened HTTP-200
     * stream,
     * where the transport status no longer reflects the failure. Such an error can only be a server-side condition that
     * occurred after generation started (the request itself was already accepted), so it is mapped to the same
     * retryable taxonomy the blocking path would produce for the equivalent 5xx: {@link LlmRateLimitedException} when
     * the body signals a rate limit, otherwise {@link LlmOverloadedException}. This keeps the gateway's retry budget
     * intact for calls re-routed through streaming (non-streaming in-flight abort).
     *
     * <p>
     * Package-private so tests can exercise the classification directly without constructing a Kotlin-only
     * {@code SseException}.
     *
     * @param body
     *            the provider error body from the mid-stream payload (may be null)
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
    static LlmClientException mapMidStreamError(String body, Duration retryAfter, String messagePrefix,
            Throwable cause) {
        Objects.requireNonNull(messagePrefix, "messagePrefix must not be null");

        final String composed = composeMidStream(messagePrefix, body);
        if (containsIgnoreCase(body, RATE_LIMIT_MARKER)) {
            return new LlmRateLimitedException(composed, retryAfter, cause);
        }
        return new LlmOverloadedException(composed, cause);
    }

    private static String composeMidStream(String prefix, String body) {
        final StringBuilder sb = new StringBuilder(prefix);
        sb.append(": mid-stream error");
        if (body != null && !body.isBlank()) {
            sb.append(" - ").append(body);
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
        final String trimmed = retryAfterHeader.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        // delta-seconds form (RFC 7231 §7.1.3).
        try {
            final long seconds = Long.parseLong(trimmed);
            if (seconds < 0) {
                return Optional.of(Duration.ZERO);
            }
            return Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException ignored) {
            // Fall through to HTTP-date parsing.
        }

        // HTTP-date form (RFC 1123 / RFC 7231 §7.1.1.1).
        try {
            final ZonedDateTime target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            final Duration delta = Duration.between(Instant.now(), target.toInstant());
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
        final List<String> values;
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

    private static int statusCodeOf(AnthropicServiceException svc) {
        try {
            return svc.statusCode();
        } catch (RuntimeException e) {
            log.debug("Unable to read status code from SDK exception: {}", e.getMessage());
            return -1;
        }
    }

    private static Headers headersOf(AnthropicServiceException svc) {
        try {
            return svc.headers();
        } catch (RuntimeException e) {
            log.debug("Unable to read headers from SDK exception: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the human-readable body of an SDK service exception. The SDK formats its message as
     * {@code "<status>: <body-json>"}; the leading status is stripped so it is not duplicated by {@link #compose}.
     */
    private static String bodyText(AnthropicServiceException svc) {
        final String raw = svc.getMessage();
        if (raw == null) {
            return null;
        }
        return LEADING_STATUS_PATTERN.matcher(raw).replaceFirst("");
    }

    private static boolean isOverloaded(int status, String body) {
        if (status == 500 || status == 502 || status == 503 || status == 504 || status == 529) {
            return true;
        }
        return containsIgnoreCase(body, OVERLOADED_MARKER);
    }

    private static boolean isContextLengthError(String body) {
        return containsIgnoreCase(body, PROMPT_TOO_LONG_MARKER)
                || containsIgnoreCase(body, CONTEXT_LENGTH_MESSAGE_MARKER);
    }

    private static Integer extractRequestedTokens(String body) {
        return firstGroupAsInt(REQUESTED_TOKENS_PATTERN, body);
    }

    private static Integer extractModelLimitTokens(String body) {
        return firstGroupAsInt(MODEL_LIMIT_PATTERN, body);
    }

    private static Integer firstGroupAsInt(Pattern pattern, String body) {
        if (body == null) {
            return null;
        }
        final Matcher m = pattern.matcher(body);
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

    private static String compose(String prefix, int httpStatus, String body) {
        final StringBuilder sb = new StringBuilder(prefix);
        sb.append(": HTTP ").append(httpStatus);
        if (body != null && !body.isBlank()) {
            sb.append(" - ").append(body);
        }
        return sb.toString();
    }

    private static String buildMessage(String prefix, Throwable sdkError) {
        final String raw = sdkError.getMessage();
        if (raw == null || raw.isBlank()) {
            return prefix + ": " + sdkError.getClass().getSimpleName();
        }
        return prefix + ": " + raw;
    }
}
