package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.exception.LlmAuthException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.exception.LlmRateLimitedException;
import at.aimon.core.llms.anthropic.exception.AnthropicException;

@DisplayName("AnthropicExceptionMapper")
class AnthropicExceptionMapperTest {

    private static final String PREFIX = "Anthropic API call failed";

    @Nested
    @DisplayName("Status matrix via mapFromHttpDetails")
    class StatusMatrix {

        @Test
        @DisplayName("HTTP 429 maps to LlmRateLimitedException")
        void http429() {
            LlmClientException mapped = AnthropicExceptionMapper.mapFromHttpDetails(429,
                    "{\"type\":\"rate_limit_error\"}", PREFIX);

            assertThat(mapped).isInstanceOf(LlmRateLimitedException.class);
            assertThat(mapped.getMessage()).contains("429").contains("rate_limit_error");
            assertThat(((LlmRateLimitedException) mapped).getRetryAfter()).isEmpty();
        }

        @Test
        @DisplayName("HTTP 429 propagates a Retry-After hint when supplied")
        void http429WithRetryAfter() {
            LlmClientException mapped = AnthropicExceptionMapper.mapFromHttpDetails(429, "slow down", PREFIX,
                    Duration.ofSeconds(7), null);

            assertThat(mapped).isInstanceOf(LlmRateLimitedException.class);
            assertThat(((LlmRateLimitedException) mapped).getRetryAfter()).contains(Duration.ofSeconds(7));
        }

        @Test
        @DisplayName("HTTP 500 maps to LlmOverloadedException")
        void http500() {
            assertThat(AnthropicExceptionMapper.mapFromHttpDetails(500, "internal error", PREFIX))
                    .isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("HTTP 502 maps to LlmOverloadedException")
        void http502() {
            assertThat(AnthropicExceptionMapper.mapFromHttpDetails(502, "bad gateway", PREFIX))
                    .isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("HTTP 503 maps to LlmOverloadedException")
        void http503() {
            assertThat(AnthropicExceptionMapper.mapFromHttpDetails(503, "service unavailable", PREFIX))
                    .isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("HTTP 504 maps to LlmOverloadedException")
        void http504() {
            assertThat(AnthropicExceptionMapper.mapFromHttpDetails(504, "gateway timeout", PREFIX))
                    .isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("HTTP 529 (Anthropic overloaded) maps to LlmOverloadedException")
        void http529() {
            LlmClientException mapped = AnthropicExceptionMapper.mapFromHttpDetails(529,
                    "{\"type\":\"overloaded_error\"}", PREFIX);
            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("'overloaded' marker in body maps to LlmOverloadedException even on an unusual status")
        void overloadedMarker() {
            LlmClientException mapped = AnthropicExceptionMapper.mapFromHttpDetails(555, "Server is overloaded",
                    PREFIX);
            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("HTTP 400 'prompt is too long' maps to LlmPromptTooLongException with token counts")
        void http400PromptTooLong() {
            String body = "{\"type\":\"invalid_request_error\",\"message\":"
                    + "\"prompt is too long: 210000 tokens > 200000 maximum\"}";
            LlmClientException mapped = AnthropicExceptionMapper.mapFromHttpDetails(400, body, PREFIX);

            assertThat(mapped).isInstanceOf(LlmPromptTooLongException.class);
            LlmPromptTooLongException ptl = (LlmPromptTooLongException) mapped;
            assertThat(ptl.getRequestedTokens()).contains(210000);
            assertThat(ptl.getModelLimitTokens()).contains(200000);
        }

        @Test
        @DisplayName("HTTP 400 mentioning 'maximum context length' still maps to PromptTooLong")
        void http400ContextViaMessage() {
            String body = "request exceeds the maximum context length for this model";
            LlmClientException mapped = AnthropicExceptionMapper.mapFromHttpDetails(400, body, PREFIX);

            assertThat(mapped).isInstanceOf(LlmPromptTooLongException.class);
            LlmPromptTooLongException ptl = (LlmPromptTooLongException) mapped;
            assertThat(ptl.getRequestedTokens()).isEmpty();
            assertThat(ptl.getModelLimitTokens()).isEmpty();
        }

        @Test
        @DisplayName("HTTP 400 without a context-length signal maps to LlmInvalidRequestException")
        void http400Generic() {
            LlmClientException mapped = AnthropicExceptionMapper.mapFromHttpDetails(400,
                    "{\"type\":\"invalid_request_error\",\"message\":\"messages: at least one message is required\"}",
                    PREFIX);
            assertThat(mapped).isInstanceOf(LlmInvalidRequestException.class);
        }

        @Test
        @DisplayName("HTTP 422 maps to LlmInvalidRequestException")
        void http422() {
            assertThat(AnthropicExceptionMapper.mapFromHttpDetails(422, "unprocessable entity", PREFIX))
                    .isInstanceOf(LlmInvalidRequestException.class);
        }

        @Test
        @DisplayName("HTTP 401 maps to LlmAuthException")
        void http401() {
            assertThat(AnthropicExceptionMapper.mapFromHttpDetails(401, "authentication_error", PREFIX))
                    .isInstanceOf(LlmAuthException.class);
        }

        @Test
        @DisplayName("HTTP 403 maps to LlmAuthException")
        void http403() {
            assertThat(AnthropicExceptionMapper.mapFromHttpDetails(403, "permission_error", PREFIX))
                    .isInstanceOf(LlmAuthException.class);
        }

        @Test
        @DisplayName("HTTP 404 / unknown status maps to the module AnthropicException, not a specialised subtype")
        void unknownStatus() {
            LlmClientException mapped = AnthropicExceptionMapper.mapFromHttpDetails(404, "not_found_error", PREFIX);

            assertThat(mapped).isInstanceOf(AnthropicException.class);
            assertThat(mapped).isNotInstanceOf(LlmRateLimitedException.class);
            assertThat(mapped).isNotInstanceOf(LlmAuthException.class);
            assertThat(mapped).isNotInstanceOf(LlmOverloadedException.class);
            assertThat(mapped).isNotInstanceOf(LlmInvalidRequestException.class);
            assertThat(mapped).isNotInstanceOf(LlmPromptTooLongException.class);
        }

        @Test
        @DisplayName("Composed message preserves prefix, status, and body")
        void composedMessage() {
            LlmClientException mapped = AnthropicExceptionMapper.mapFromHttpDetails(429, "slow down", PREFIX);
            assertThat(mapped.getMessage()).contains(PREFIX).contains("429").contains("slow down");
        }
    }

    @Nested
    @DisplayName("Retry-After parsing")
    class RetryAfterParsing {

        @Test
        @DisplayName("Numeric seconds are parsed as a Duration")
        void numericSeconds() {
            assertThat(AnthropicExceptionMapper.parseRetryAfter("30")).contains(Duration.ofSeconds(30));
            assertThat(AnthropicExceptionMapper.parseRetryAfter(" 15 ")).contains(Duration.ofSeconds(15));
            assertThat(AnthropicExceptionMapper.parseRetryAfter("0")).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("Negative numeric values collapse to Duration.ZERO")
        void negativeSecondsBecomeZero() {
            assertThat(AnthropicExceptionMapper.parseRetryAfter("-5")).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("RFC 1123 HTTP-date in the future yields a positive duration")
        void rfc1123Future() {
            ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusHours(1);
            String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(future);

            Duration parsed = AnthropicExceptionMapper.parseRetryAfter(header).orElseThrow();
            // Allow some slack for clock drift / test scheduling.
            assertThat(parsed).isGreaterThan(Duration.ofMinutes(58)).isLessThan(Duration.ofMinutes(62));
        }

        @Test
        @DisplayName("RFC 1123 HTTP-date in the past collapses to Duration.ZERO")
        void rfc1123Past() {
            ZonedDateTime past = ZonedDateTime.now(ZoneOffset.UTC).minusHours(1);
            String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(past);

            assertThat(AnthropicExceptionMapper.parseRetryAfter(header)).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("null, blank and unparseable headers yield Optional.empty")
        void emptyInputs() {
            assertThat(AnthropicExceptionMapper.parseRetryAfter(null)).isEmpty();
            assertThat(AnthropicExceptionMapper.parseRetryAfter("")).isEmpty();
            assertThat(AnthropicExceptionMapper.parseRetryAfter("   ")).isEmpty();
            assertThat(AnthropicExceptionMapper.parseRetryAfter("not a date")).isEmpty();
        }
    }

    @Nested
    @DisplayName("SDK fallback via map(Throwable, String)")
    class SdkFallback {

        @Test
        @DisplayName("Non-service SDK errors fall back to the module AnthropicException")
        void nonServiceExceptionFallback() {
            RuntimeException sdkLike = new RuntimeException("connection reset");

            LlmClientException mapped = AnthropicExceptionMapper.map(sdkLike, PREFIX);

            assertThat(mapped).isInstanceOf(AnthropicException.class);
            assertThat(mapped).isNotInstanceOf(LlmRateLimitedException.class);
            assertThat(mapped).isNotInstanceOf(LlmOverloadedException.class);
            assertThat(mapped.getCause()).isSameAs(sdkLike);
            assertThat(mapped.getMessage()).contains(PREFIX).contains("connection reset");
        }

        @Test
        @DisplayName("Exceptions without a message fall back gracefully")
        void exceptionWithoutMessage() {
            RuntimeException sdkLike = new RuntimeException();

            LlmClientException mapped = AnthropicExceptionMapper.map(sdkLike, PREFIX);

            assertThat(mapped).isInstanceOf(AnthropicException.class);
            assertThat(mapped.getCause()).isSameAs(sdkLike);
            assertThat(mapped.getMessage()).contains(PREFIX);
        }
    }

    /**
     * A mid-stream SSE error event carries the stream-open status (HTTP 200), not the failure's real status. These
     * tests pin that such an error is classified from its body into the <em>retryable</em> taxonomy rather than falling
     * through to the non-retryable base {@link AnthropicException} — otherwise a streaming-re-routed cancellable call
     * would silently lose the retry budget the blocking path enjoys for the equivalent server-side 5xx.
     */
    @Nested
    @DisplayName("Mid-stream SSE error classification")
    class MidStreamErrors {

        @Test
        @DisplayName("A non-rate-limit mid-stream error (api_error) maps to the retryable LlmOverloadedException")
        void apiErrorBecomesOverloaded() {
            LlmClientException mapped = AnthropicExceptionMapper.mapMidStreamError(
                    "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Internal server error\"}}",
                    null, PREFIX, null);

            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
            assertThat(mapped).isNotInstanceOf(LlmRateLimitedException.class);
            assertThat(mapped.getMessage()).contains(PREFIX).contains("mid-stream").contains("api_error");
        }

        @Test
        @DisplayName("An overloaded_error mid-stream body maps to LlmOverloadedException")
        void overloadedErrorBecomesOverloaded() {
            LlmClientException mapped = AnthropicExceptionMapper.mapMidStreamError(
                    "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}", null, PREFIX, null);

            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("A rate_limit_error mid-stream body maps to LlmRateLimitedException carrying Retry-After")
        void rateLimitErrorCarriesRetryAfter() {
            LlmClientException mapped = AnthropicExceptionMapper.mapMidStreamError(
                    "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}", Duration.ofSeconds(11), PREFIX,
                    null);

            assertThat(mapped).isInstanceOf(LlmRateLimitedException.class);
            assertThat(((LlmRateLimitedException) mapped).getRetryAfter()).contains(Duration.ofSeconds(11));
        }

        @Test
        @DisplayName("A real HTTP-200 SseException routes through map() to a retryable type, never the base fallback")
        void realSseExceptionRoutesToRetryable() {
            com.anthropic.errors.SseException sse = com.anthropic.errors.SseException.builder().statusCode(200)
                    .headers(com.anthropic.core.http.Headers.builder().build())
                    .body(com.anthropic.core.JsonValue.from(java.util.Map.of("type", "error"))).build();

            LlmClientException mapped = AnthropicExceptionMapper.map(sse, PREFIX);

            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
            assertThat(mapped).isNotInstanceOf(AnthropicException.class);
            assertThat(mapped.getCause()).isSameAs(sse);
        }
    }
}
