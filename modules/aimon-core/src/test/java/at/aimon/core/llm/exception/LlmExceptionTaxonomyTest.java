package at.aimon.core.llm.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LLM exception taxonomy")
class LlmExceptionTaxonomyTest {

    @Nested
    @DisplayName("Subclass relation")
    class Hierarchy {

        @Test
        @DisplayName("All taxonomy exceptions extend LlmClientException")
        void allSubtypesExtendLlmClientException() {
            assertThat(new LlmRateLimitedException("rate")).isInstanceOf(LlmClientException.class);
            assertThat(new LlmOverloadedException("over")).isInstanceOf(LlmClientException.class);
            assertThat(new LlmPromptTooLongException("ctx")).isInstanceOf(LlmClientException.class);
            assertThat(new LlmAuthException("auth")).isInstanceOf(LlmClientException.class);
            assertThat(new LlmInvalidRequestException("bad")).isInstanceOf(LlmClientException.class);
        }
    }

    @Nested
    @DisplayName("Rate-limited exception")
    class RateLimited {

        @Test
        @DisplayName("retryAfter defaults to empty when omitted")
        void retryAfterDefaultsEmpty() {
            LlmRateLimitedException e = new LlmRateLimitedException("too many");
            assertThat(e.getRetryAfter()).isEmpty();
            assertThat(e.getCause()).isNull();
            assertThat(e.getMessage()).isEqualTo("too many");
        }

        @Test
        @DisplayName("retryAfter is preserved when provided")
        void retryAfterPreserved() {
            Duration retry = Duration.ofSeconds(12);
            LlmRateLimitedException e = new LlmRateLimitedException("too many", retry);
            assertThat(e.getRetryAfter()).contains(retry);
        }

        @Test
        @DisplayName("cause + retryAfter are both preserved")
        void causeAndRetryAfter() {
            Throwable cause = new RuntimeException("root");
            Duration retry = Duration.ofMillis(500);
            LlmRateLimitedException e = new LlmRateLimitedException("too many", retry, cause);
            assertThat(e.getCause()).isSameAs(cause);
            assertThat(e.getRetryAfter()).contains(retry);
        }

        @Test
        @DisplayName("null cause is allowed")
        void nullCauseAllowed() {
            LlmRateLimitedException e = new LlmRateLimitedException("too many", (Throwable) null);
            assertThat(e.getCause()).isNull();
        }

        @Test
        @DisplayName("null message is rejected")
        void rejectNullMessage() {
            assertThatThrownBy(() -> new LlmRateLimitedException(null, Duration.ofSeconds(1)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("getRetryAfter never returns null Optional")
        void retryAfterIsNeverNull() {
            LlmRateLimitedException e = new LlmRateLimitedException("x", null, null);
            assertThat(e.getRetryAfter()).isNotNull();
            assertThat(e.getRetryAfter()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Overloaded exception")
    class Overloaded {

        @Test
        @DisplayName("Standard constructors preserve message and cause")
        void messageAndCause() {
            LlmOverloadedException e1 = new LlmOverloadedException("server down");
            assertThat(e1.getMessage()).isEqualTo("server down");
            assertThat(e1.getCause()).isNull();

            Throwable cause = new IllegalStateException("root");
            LlmOverloadedException e2 = new LlmOverloadedException("server down", cause);
            assertThat(e2.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("null cause is allowed")
        void nullCauseAllowed() {
            LlmOverloadedException e = new LlmOverloadedException("x", null);
            assertThat(e.getCause()).isNull();
        }

        @Test
        @DisplayName("null message is rejected")
        void rejectNullMessage() {
            assertThatThrownBy(() -> new LlmOverloadedException(null, new RuntimeException()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Prompt-too-long exception")
    class PromptTooLong {

        @Test
        @DisplayName("Token metadata defaults to empty when omitted")
        void metadataDefaultsEmpty() {
            LlmPromptTooLongException e = new LlmPromptTooLongException("too long");
            assertThat(e.getRequestedTokens()).isEmpty();
            assertThat(e.getModelLimitTokens()).isEmpty();
            assertThat(e.getCause()).isNull();
        }

        @Test
        @DisplayName("Token metadata is preserved")
        void metadataPreserved() {
            LlmPromptTooLongException e = new LlmPromptTooLongException("too long", 6000, 4096);
            assertThat(e.getRequestedTokens()).contains(6000);
            assertThat(e.getModelLimitTokens()).contains(4096);
        }

        @Test
        @DisplayName("Token metadata + cause are both preserved")
        void metadataAndCause() {
            Throwable cause = new RuntimeException("root");
            LlmPromptTooLongException e = new LlmPromptTooLongException("too long", 6000, 4096, cause);
            assertThat(e.getRequestedTokens()).contains(6000);
            assertThat(e.getModelLimitTokens()).contains(4096);
            assertThat(e.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("null tokens yield empty Optionals")
        void nullTokens() {
            LlmPromptTooLongException e = new LlmPromptTooLongException("too long", null, null, null);
            assertThat(e.getRequestedTokens()).isEmpty();
            assertThat(e.getModelLimitTokens()).isEmpty();
            assertThat(e.getCause()).isNull();
        }

        @Test
        @DisplayName("null message is rejected")
        void rejectNullMessage() {
            assertThatThrownBy(() -> new LlmPromptTooLongException(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Auth exception")
    class Auth {

        @Test
        @DisplayName("Standard constructors preserve message and cause")
        void messageAndCause() {
            LlmAuthException e1 = new LlmAuthException("no key");
            assertThat(e1.getMessage()).isEqualTo("no key");
            assertThat(e1.getCause()).isNull();

            Throwable cause = new SecurityException("denied");
            LlmAuthException e2 = new LlmAuthException("no key", cause);
            assertThat(e2.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("null cause is allowed")
        void nullCauseAllowed() {
            LlmAuthException e = new LlmAuthException("x", null);
            assertThat(e.getCause()).isNull();
        }

        @Test
        @DisplayName("null message is rejected")
        void rejectNullMessage() {
            assertThatThrownBy(() -> new LlmAuthException(null, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Invalid-request exception")
    class InvalidRequest {

        @Test
        @DisplayName("Standard constructors preserve message and cause")
        void messageAndCause() {
            LlmInvalidRequestException e1 = new LlmInvalidRequestException("bad body");
            assertThat(e1.getMessage()).isEqualTo("bad body");
            assertThat(e1.getCause()).isNull();

            Throwable cause = new IllegalArgumentException("missing field");
            LlmInvalidRequestException e2 = new LlmInvalidRequestException("bad body", cause);
            assertThat(e2.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("null cause is allowed")
        void nullCauseAllowed() {
            LlmInvalidRequestException e = new LlmInvalidRequestException("x", null);
            assertThat(e.getCause()).isNull();
        }

        @Test
        @DisplayName("null message is rejected")
        void rejectNullMessage() {
            assertThatThrownBy(() -> new LlmInvalidRequestException(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
