package at.aimon.core.llms.openai;

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

@DisplayName("OpenAIExceptionMapper")
class OpenAIExceptionMapperTest {

    private static final String PREFIX = "OpenAI API call failed";

    @Nested
    @DisplayName("Status / code matrix via mapFromHttpDetails")
    class StatusMatrix {

        @Test
        @DisplayName("HTTP 429 maps to LlmRateLimitedException")
        void http429() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(429, "rate_limit_exceeded",
                    "too many requests", PREFIX);

            assertThat(mapped).isInstanceOf(LlmRateLimitedException.class);
            assertThat(mapped.getMessage()).contains("429").contains("too many requests");
            assertThat(((LlmRateLimitedException) mapped).getRetryAfter()).isEmpty();
        }

        @Test
        @DisplayName("HTTP 500 maps to LlmOverloadedException")
        void http500() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(500, "server_error", "internal error",
                    PREFIX);
            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("HTTP 502 maps to LlmOverloadedException")
        void http502() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(502, null, "bad gateway", PREFIX);
            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("HTTP 503 maps to LlmOverloadedException")
        void http503() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(503, null, "service unavailable",
                    PREFIX);
            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("HTTP 504 maps to LlmOverloadedException")
        void http504() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(504, null, "gateway timeout", PREFIX);
            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("'overloaded' marker in body maps to LlmOverloadedException even on unusual status")
        void overloadedMarker() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(529, "overloaded",
                    "Server is overloaded", PREFIX);
            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
        }

        @Test
        @DisplayName("HTTP 400 with context_length_exceeded maps to LlmPromptTooLongException")
        void http400ContextCode() {
            String body = "This model's maximum context length is 4096 tokens. "
                    + "However, your messages resulted in 5000 tokens. Please reduce the length.";
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(400, "context_length_exceeded", body,
                    PREFIX);

            assertThat(mapped).isInstanceOf(LlmPromptTooLongException.class);
            LlmPromptTooLongException ptl = (LlmPromptTooLongException) mapped;
            assertThat(ptl.getModelLimitTokens()).contains(4096);
            assertThat(ptl.getRequestedTokens()).contains(5000);
        }

        @Test
        @DisplayName("HTTP 400 whose message mentions 'maximum context length' still maps to PromptTooLong")
        void http400ContextViaMessage() {
            String body = "This model's maximum context length is 8192 tokens. Shorter prompt required.";
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(400, null, body, PREFIX);

            assertThat(mapped).isInstanceOf(LlmPromptTooLongException.class);
            LlmPromptTooLongException ptl = (LlmPromptTooLongException) mapped;
            assertThat(ptl.getModelLimitTokens()).contains(8192);
            assertThat(ptl.getRequestedTokens()).isEmpty();
        }

        @Test
        @DisplayName("HTTP 400 without context-length signal maps to LlmInvalidRequestException")
        void http400GenericInvalidRequest() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(400, "invalid_request_error",
                    "Missing required parameter 'messages'", PREFIX);
            assertThat(mapped).isInstanceOf(LlmInvalidRequestException.class);
        }

        @Test
        @DisplayName("HTTP 401 maps to LlmAuthException")
        void http401() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(401, "invalid_api_key", "no key",
                    PREFIX);
            assertThat(mapped).isInstanceOf(LlmAuthException.class);
        }

        @Test
        @DisplayName("HTTP 403 maps to LlmAuthException")
        void http403() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(403, null, "forbidden", PREFIX);
            assertThat(mapped).isInstanceOf(LlmAuthException.class);
        }

        @Test
        @DisplayName("Unknown HTTP status maps to base LlmClientException")
        void unknownStatus() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(418, "im_a_teapot", "rfc 2324",
                    PREFIX);

            assertThat(mapped).isInstanceOf(LlmClientException.class);
            assertThat(mapped).isNotInstanceOf(LlmRateLimitedException.class);
            assertThat(mapped).isNotInstanceOf(LlmAuthException.class);
            assertThat(mapped).isNotInstanceOf(LlmOverloadedException.class);
            assertThat(mapped).isNotInstanceOf(LlmInvalidRequestException.class);
            assertThat(mapped).isNotInstanceOf(LlmPromptTooLongException.class);
        }

        @Test
        @DisplayName("Composed message preserves prefix, status, code, and body")
        void composedMessage() {
            LlmClientException mapped = OpenAIExceptionMapper.mapFromHttpDetails(429, "rate_limit_exceeded",
                    "slow down", PREFIX);

            assertThat(mapped.getMessage()).contains(PREFIX).contains("429").contains("rate_limit_exceeded")
                    .contains("slow down");
        }
    }

    @Nested
    @DisplayName("Retry-After parsing")
    class RetryAfterParsing {

        @Test
        @DisplayName("Numeric seconds are parsed as a Duration")
        void numericSeconds() {
            assertThat(OpenAIExceptionMapper.parseRetryAfter("30")).contains(Duration.ofSeconds(30));
            assertThat(OpenAIExceptionMapper.parseRetryAfter(" 15 ")).contains(Duration.ofSeconds(15));
            assertThat(OpenAIExceptionMapper.parseRetryAfter("0")).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("Negative numeric values collapse to Duration.ZERO")
        void negativeSecondsBecomeZero() {
            assertThat(OpenAIExceptionMapper.parseRetryAfter("-5")).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("RFC 1123 HTTP-date in the future yields a positive duration")
        void rfc1123Future() {
            ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusHours(1);
            String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(future);

            Duration parsed = OpenAIExceptionMapper.parseRetryAfter(header).orElseThrow();
            // Allow some slack for clock drift / test scheduling.
            assertThat(parsed).isGreaterThan(Duration.ofMinutes(58)).isLessThan(Duration.ofMinutes(62));
        }

        @Test
        @DisplayName("RFC 1123 HTTP-date in the past collapses to Duration.ZERO")
        void rfc1123Past() {
            ZonedDateTime past = ZonedDateTime.now(ZoneOffset.UTC).minusHours(1);
            String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(past);

            assertThat(OpenAIExceptionMapper.parseRetryAfter(header)).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("null, blank and unparseable headers yield Optional.empty")
        void emptyInputs() {
            assertThat(OpenAIExceptionMapper.parseRetryAfter(null)).isEmpty();
            assertThat(OpenAIExceptionMapper.parseRetryAfter("")).isEmpty();
            assertThat(OpenAIExceptionMapper.parseRetryAfter("   ")).isEmpty();
            assertThat(OpenAIExceptionMapper.parseRetryAfter("not a date")).isEmpty();
        }
    }

    @Nested
    @DisplayName("SDK fallback via map(Throwable, String)")
    class SdkFallback {

        @Test
        @DisplayName("Non-SDK runtime exceptions fall back to base LlmClientException")
        void runtimeExceptionFallback() {
            RuntimeException sdkLike = new RuntimeException("connection refused");

            LlmClientException mapped = OpenAIExceptionMapper.map(sdkLike, PREFIX);

            assertThat(mapped).isInstanceOf(LlmClientException.class);
            // Must not be any of the specialised subtypes.
            assertThat(mapped).isNotInstanceOf(LlmRateLimitedException.class);
            assertThat(mapped).isNotInstanceOf(LlmAuthException.class);
            assertThat(mapped).isNotInstanceOf(LlmOverloadedException.class);
            assertThat(mapped).isNotInstanceOf(LlmInvalidRequestException.class);
            assertThat(mapped).isNotInstanceOf(LlmPromptTooLongException.class);
            assertThat(mapped.getCause()).isSameAs(sdkLike);
            assertThat(mapped.getMessage()).contains(PREFIX).contains("connection refused");
        }

        @Test
        @DisplayName("Exceptions without a message fall back gracefully")
        void exceptionWithoutMessage() {
            RuntimeException sdkLike = new RuntimeException();

            LlmClientException mapped = OpenAIExceptionMapper.map(sdkLike, PREFIX);

            assertThat(mapped).isInstanceOf(LlmClientException.class);
            assertThat(mapped.getCause()).isSameAs(sdkLike);
            assertThat(mapped.getMessage()).contains(PREFIX);
        }
    }

    /**
     * A mid-stream SSE error event carries the stream-open status (HTTP 200), not the failure's real status. These
     * tests pin that such an error is classified from its payload into the <em>retryable</em> taxonomy rather than
     * falling through to a non-retryable base {@link LlmClientException} — otherwise a streaming-re-routed cancellable
     * call would silently lose the retry budget the blocking path enjoys for the equivalent server-side 5xx.
     */
    @Nested
    @DisplayName("Mid-stream SSE error classification")
    class MidStreamErrors {

        @Test
        @DisplayName("A non-rate-limit mid-stream error maps to the retryable LlmOverloadedException")
        void nonRateLimitBecomesOverloaded() {
            LlmClientException mapped = OpenAIExceptionMapper.mapMidStreamError("server_error", null,
                    "The server had an error while processing your request", null, PREFIX, null);

            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
            assertThat(mapped).isNotInstanceOf(LlmRateLimitedException.class);
            assertThat(mapped.getMessage()).contains(PREFIX).contains("mid-stream").contains("server_error");
        }

        @Test
        @DisplayName("A rate-limit mid-stream error (by type) maps to LlmRateLimitedException carrying Retry-After")
        void rateLimitByTypeCarriesRetryAfter() {
            LlmClientException mapped = OpenAIExceptionMapper.mapMidStreamError("rate_limit_exceeded", null,
                    "slow down", Duration.ofSeconds(7), PREFIX, null);

            assertThat(mapped).isInstanceOf(LlmRateLimitedException.class);
            assertThat(((LlmRateLimitedException) mapped).getRetryAfter()).contains(Duration.ofSeconds(7));
        }

        @Test
        @DisplayName("A rate-limit mid-stream error signalled only by code still maps to LlmRateLimitedException")
        void rateLimitByCode() {
            LlmClientException mapped = OpenAIExceptionMapper.mapMidStreamError(null, "rate_limit_reached", "slow down",
                    null, PREFIX, null);

            assertThat(mapped).isInstanceOf(LlmRateLimitedException.class);
        }

        @Test
        @DisplayName("A real HTTP-200 SseException routes through map() to a retryable type, never the base fallback")
        void realSseExceptionRoutesToRetryable() {
            com.openai.errors.SseException sse = com.openai.errors.SseException.builder().statusCode(200)
                    .headers(com.openai.core.http.Headers.builder().build()).build();

            LlmClientException mapped = OpenAIExceptionMapper.map(sse, PREFIX);

            assertThat(mapped).isInstanceOf(LlmOverloadedException.class);
            assertThat(mapped.getCause()).isSameAs(sse);
        }
    }
}
