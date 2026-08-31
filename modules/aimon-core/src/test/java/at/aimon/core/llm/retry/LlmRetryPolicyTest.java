package at.aimon.core.llm.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.exception.LlmAuthException;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.exception.LlmRateLimitedException;

@DisplayName("LlmRetryPolicy")
class LlmRetryPolicyTest {

    @Nested
    @DisplayName("defaultPolicy()")
    class Defaults {

        @Test
        @DisplayName("uses 3 attempts, 500ms base, 20% jitter, and retries 429/5xx")
        void defaultsMatchDocumentedContract() {
            LlmRetryPolicy policy = LlmRetryPolicy.defaultPolicy();

            assertThat(policy.getMaxAttempts()).isEqualTo(3);
            assertThat(policy.getBackoffBase()).isEqualTo(Duration.ofMillis(500));
            assertThat(policy.getJitterFactor()).isEqualTo(0.2);
            assertThat(policy.getRetryableExceptions()).containsExactlyInAnyOrder(LlmRateLimitedException.class,
                    LlmOverloadedException.class);
        }

        @Test
        @DisplayName("treats rate-limit and overload as retryable, non-transient errors as not retryable")
        void defaultsRetryabilityMatrix() {
            LlmRetryPolicy policy = LlmRetryPolicy.defaultPolicy();

            assertThat(policy.isRetryable(new LlmRateLimitedException("429"))).isTrue();
            assertThat(policy.isRetryable(new LlmOverloadedException("503"))).isTrue();
            assertThat(policy.isRetryable(new LlmPromptTooLongException("context"))).isFalse();
            assertThat(policy.isRetryable(new LlmAuthException("401"))).isFalse();
            assertThat(policy.isRetryable(new LlmInvalidRequestException("400"))).isFalse();
            assertThat(policy.isRetryable(new LlmClientException("generic"))).isFalse();
        }

        @Test
        @DisplayName("returns an unmodifiable retryable-exceptions set")
        void retryableExceptionsIsUnmodifiable() {
            Set<Class<? extends LlmClientException>> set = LlmRetryPolicy.defaultPolicy().getRetryableExceptions();
            assertThatThrownBy(() -> set.add(LlmClientException.class))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("isRetryable(...)")
    class IsRetryable {

        @Test
        @DisplayName("matches registered class by equality")
        void matchesExactClass() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(2).backoffBase(Duration.ofMillis(10))
                    .jitterFactor(0.0).retryableExceptions(Set.of(LlmRateLimitedException.class)).build();

            assertThat(policy.isRetryable(new LlmRateLimitedException("429"))).isTrue();
            assertThat(policy.isRetryable(new LlmOverloadedException("503"))).isFalse();
        }

        @Test
        @DisplayName("matches subclasses of a registered supertype")
        void matchesSubclassesOfSupertype() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(2).backoffBase(Duration.ofMillis(10))
                    .jitterFactor(0.0).retryableExceptions(Set.of(LlmClientException.class)).build();

            // Every LlmClientException subclass should be retryable when the supertype is registered.
            assertThat(policy.isRetryable(new LlmRateLimitedException("429"))).isTrue();
            assertThat(policy.isRetryable(new LlmOverloadedException("503"))).isTrue();
            assertThat(policy.isRetryable(new LlmAuthException("401"))).isTrue();
            assertThat(policy.isRetryable(new LlmPromptTooLongException("context"))).isTrue();
        }

        @Test
        @DisplayName("never retries a cancelled call, even when a matching supertype is registered")
        void cancellationIsNeverRetryable() {
            // Adversarial policy: register the whole LlmClientException hierarchy as retryable.
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(3).backoffBase(Duration.ofMillis(10))
                    .jitterFactor(0.0).retryableExceptions(Set.of(LlmClientException.class)).build();

            // A generic client exception matches the supertype and is retryable...
            assertThat(policy.isRetryable(new LlmClientException("generic"))).isTrue();
            // ...but a cancellation is a terminal, caller-initiated abort and is excluded regardless of the set.
            assertThat(policy.isRetryable(new LlmCallCancelledException("aborted"))).isFalse();
        }

        @Test
        @DisplayName("returns false when the retryable set is empty")
        void emptyRetryableSetMatchesNothing() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(1).backoffBase(Duration.ZERO).jitterFactor(0.0)
                    .retryableExceptions(Set.of()).build();

            assertThat(policy.isRetryable(new LlmRateLimitedException("429"))).isFalse();
        }

        @Test
        @DisplayName("rejects null exception argument")
        void rejectsNullException() {
            LlmRetryPolicy policy = LlmRetryPolicy.defaultPolicy();
            assertThatThrownBy(() -> policy.isRetryable(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("computeDelay(...)")
    class ComputeDelay {

        @Test
        @DisplayName("returns exactly the exponential value when jitterFactor is 0")
        void noJitterYieldsDeterministicExponential() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(4).backoffBase(Duration.ofMillis(100))
                    .jitterFactor(0.0).retryableExceptions(Set.of(LlmRateLimitedException.class)).build();

            Random rng = new Random(42);
            assertThat(policy.computeDelay(1, rng)).isEqualTo(Duration.ofMillis(100));
            assertThat(policy.computeDelay(2, rng)).isEqualTo(Duration.ofMillis(200));
            assertThat(policy.computeDelay(3, rng)).isEqualTo(Duration.ofMillis(400));
            assertThat(policy.computeDelay(4, rng)).isEqualTo(Duration.ofMillis(800));
        }

        @Test
        @DisplayName("stays within ±jitterFactor*base of the exponential when jitter is enabled")
        void jitterStaysWithinBounds() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(5).backoffBase(Duration.ofMillis(100))
                    .jitterFactor(0.5).retryableExceptions(Set.of(LlmRateLimitedException.class)).build();

            Random rng = new Random(2026);
            Duration baseDeviation = Duration.ofMillis(50); // 0.5 * 100ms

            for (int attempt = 1; attempt <= 5; attempt++) {
                long expectedCenterMs = 100L << (attempt - 1);
                Duration delay = policy.computeDelay(attempt, rng);

                long expectedMinMs = Math.max(0L, expectedCenterMs - baseDeviation.toMillis());
                long expectedMaxMs = expectedCenterMs + baseDeviation.toMillis();
                assertThat(delay.toMillis()).as("attempt=%d", attempt).isBetween(expectedMinMs, expectedMaxMs);
            }
        }

        @Test
        @DisplayName("never returns a negative duration even when jitter would underflow")
        void neverReturnsNegativeDuration() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(2).backoffBase(Duration.ofMillis(1))
                    .jitterFactor(1.0).retryableExceptions(Set.of(LlmRateLimitedException.class)).build();

            Random rng = new Random(0);
            for (int i = 0; i < 100; i++) {
                Duration delay = policy.computeDelay(1, rng);
                assertThat(delay.isNegative()).as("iteration %d", i).isFalse();
            }
        }

        @Test
        @DisplayName("returns Duration.ZERO when backoffBase is zero regardless of attempt or jitter")
        void zeroBaseYieldsZero() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(3).backoffBase(Duration.ZERO)
                    .jitterFactor(0.75).retryableExceptions(Set.of(LlmRateLimitedException.class)).build();

            Random rng = new Random(7);
            assertThat(policy.computeDelay(1, rng)).isEqualTo(Duration.ZERO);
            assertThat(policy.computeDelay(5, rng)).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("rejects attemptNumber < 1")
        void rejectsZeroOrNegativeAttempt() {
            LlmRetryPolicy policy = LlmRetryPolicy.defaultPolicy();
            Random rng = new Random();
            assertThatThrownBy(() -> policy.computeDelay(0, rng)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> policy.computeDelay(-3, rng)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null random")
        void rejectsNullRandom() {
            LlmRetryPolicy policy = LlmRetryPolicy.defaultPolicy();
            assertThatThrownBy(() -> policy.computeDelay(1, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("does not overflow for very large attempt numbers")
        void largeAttemptNumberDoesNotOverflow() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(100).backoffBase(Duration.ofMillis(500))
                    .jitterFactor(0.0).retryableExceptions(Set.of(LlmRateLimitedException.class)).build();

            Random rng = new Random(1);
            // Asking for attempt 100 would overflow a naive long shift; computeDelay must cap internally.
            Duration delay = policy.computeDelay(100, rng);
            assertThat(delay.isNegative()).isFalse();
        }
    }

    @Nested
    @DisplayName("builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("rejects maxAttempts < 1")
        void rejectsMaxAttemptsBelowOne() {
            assertThatThrownBy(() -> LlmRetryPolicy.builder().maxAttempts(0).backoffBase(Duration.ZERO)
                    .jitterFactor(0.0).retryableExceptions(Set.of()).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects negative backoffBase")
        void rejectsNegativeBackoff() {
            assertThatThrownBy(() -> LlmRetryPolicy.builder().maxAttempts(1).backoffBase(Duration.ofMillis(-1))
                    .jitterFactor(0.0).retryableExceptions(Set.of()).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects jitterFactor outside [0.0, 1.0]")
        void rejectsJitterOutsideBounds() {
            assertThatThrownBy(() -> LlmRetryPolicy.builder().maxAttempts(1).backoffBase(Duration.ZERO)
                    .jitterFactor(-0.01).retryableExceptions(Set.of()).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LlmRetryPolicy.builder().maxAttempts(1).backoffBase(Duration.ZERO)
                    .jitterFactor(1.01).retryableExceptions(Set.of()).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null backoffBase")
        void rejectsNullBackoff() {
            assertThatThrownBy(() -> LlmRetryPolicy.builder().backoffBase(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null retryableExceptions set")
        void rejectsNullRetryableSet() {
            assertThatThrownBy(() -> LlmRetryPolicy.builder().retryableExceptions(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null entry added via addRetryableException")
        void rejectsNullAddedRetryable() {
            assertThatThrownBy(() -> LlmRetryPolicy.builder().addRetryableException(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("accepts maxAttempts == 1 meaning no retry")
        void acceptsSingleAttempt() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(1).backoffBase(Duration.ZERO).jitterFactor(0.0)
                    .retryableExceptions(Set.of()).build();
            assertThat(policy.getMaxAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("addRetryableException accumulates entries")
        void addRetryableAccumulates() {
            LlmRetryPolicy policy = LlmRetryPolicy.builder().maxAttempts(2).backoffBase(Duration.ZERO).jitterFactor(0.0)
                    .addRetryableException(LlmRateLimitedException.class)
                    .addRetryableException(LlmOverloadedException.class).build();

            assertThat(policy.getRetryableExceptions()).containsExactlyInAnyOrder(LlmRateLimitedException.class,
                    LlmOverloadedException.class);
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equal configurations are equal and share hashCode")
        void equality() {
            LlmRetryPolicy a = LlmRetryPolicy.defaultPolicy();
            LlmRetryPolicy b = LlmRetryPolicy.defaultPolicy();
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("different jitter values are not equal")
        void inequalityOnJitter() {
            LlmRetryPolicy a = LlmRetryPolicy.builder().maxAttempts(2).backoffBase(Duration.ofMillis(100))
                    .jitterFactor(0.1).retryableExceptions(Set.of(LlmRateLimitedException.class)).build();
            LlmRetryPolicy b = LlmRetryPolicy.builder().maxAttempts(2).backoffBase(Duration.ofMillis(100))
                    .jitterFactor(0.2).retryableExceptions(Set.of(LlmRateLimitedException.class)).build();
            assertThat(a).isNotEqualTo(b);
        }
    }
}
