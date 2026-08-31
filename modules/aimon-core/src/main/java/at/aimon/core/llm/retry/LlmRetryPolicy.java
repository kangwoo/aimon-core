package at.aimon.core.llm.retry;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.exception.LlmRateLimitedException;

/**
 * Immutable policy describing how a single logical LLM call should be retried when it fails with a
 * {@link LlmClientException} subtype that is considered <em>transient</em>.
 *
 * <p>
 * A policy captures four decisions:
 * <ol>
 * <li><strong>How many total attempts?</strong> {@link #getMaxAttempts()} — {@code 1} means "no retry", {@code 3} means
 * "one initial attempt + two retries".
 * <li><strong>How long to wait?</strong> {@link #getBackoffBase()} is the base delay for the first retry; subsequent
 * retries use exponential doubling.
 * <li><strong>How much jitter?</strong> {@link #getJitterFactor()} is a fraction in {@code [0.0, 1.0]} applied as
 * {@code ±factor * base} to the computed exponential delay.
 * <li><strong>Which exceptions are retryable?</strong> {@link #getRetryableExceptions()} is the set of
 * {@link LlmClientException} subclasses (matched by {@link Class#isAssignableFrom(Class)}) that should trigger a retry.
 * All other exceptions — including unrelated {@link RuntimeException}s — are rethrown immediately by the caller.
 * </ol>
 *
 * <p>
 * <strong>Cancellation is never retryable.</strong> {@link LlmCallCancelledException} represents a terminal,
 * caller-initiated abort — categorically not a transient provider failure — so {@link #isRetryable(LlmClientException)}
 * always reports {@code false} for it, <em>regardless</em> of the configured set (even if a supertype such as
 * {@link LlmClientException} is registered). This is defense-in-depth: the "never retry a cancelled call" guarantee no
 * longer rests solely on the gateway's {@code catch}-block ordering.
 *
 * <h2>Scope</h2>
 *
 * <p>
 * This class only <em>describes</em> the policy. It does not invoke an {@link at.aimon.core.llm.LlmClient}, sleep, or
 * consume a clock. The gateway that wraps {@link at.aimon.core.llm.LlmClient} with retry/backoff behaviour (RETRY-03)
 * consumes these policies.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * Instances are immutable and thread-safe. The {@link #computeDelay(int, Random)} method accepts a caller-supplied
 * {@link Random} to keep the class free of shared mutable state.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * {@code
 * LlmRetryPolicy policy = LlmRetryPolicy.defaultPolicy();
 * if (policy.isRetryable(ex) && attempt < policy.getMaxAttempts()) {
 *     Duration delay = policy.computeDelay(attempt, random);
 *     // sleep for delay ...
 * }
 * }
 * </pre>
 */
public final class LlmRetryPolicy {

    private final int maxAttempts;
    private final Duration backoffBase;
    private final double jitterFactor;
    private final Set<Class<? extends LlmClientException>> retryableExceptions;

    private LlmRetryPolicy(Builder builder) {
        if (builder.maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + builder.maxAttempts);
        }
        Objects.requireNonNull(builder.backoffBase, "backoffBase must not be null");
        if (builder.backoffBase.isNegative()) {
            throw new IllegalArgumentException("backoffBase must be non-negative, got: " + builder.backoffBase);
        }
        if (builder.jitterFactor < 0.0 || builder.jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be within [0.0, 1.0], got: " + builder.jitterFactor);
        }
        Objects.requireNonNull(builder.retryableExceptions, "retryableExceptions must not be null");

        this.maxAttempts = builder.maxAttempts;
        this.backoffBase = builder.backoffBase;
        this.jitterFactor = builder.jitterFactor;
        this.retryableExceptions = Set.copyOf(builder.retryableExceptions);
    }

    /**
     * Returns a conservative default policy suitable for most provider calls.
     *
     * <p>
     * Behaviour:
     * <ul>
     * <li>Total attempts: up to 3 (1 initial + 2 retries) — covers 429 rate-limiting with two backoffs and 5xx
     * overloads
     * with one.
     * <li>Backoff base: 500&nbsp;ms with exponential doubling and ±20% jitter.
     * <li>Retryable exceptions: {@link LlmRateLimitedException} and {@link LlmOverloadedException}.
     * </ul>
     *
     * <p>
     * Non-transient failures such as {@link at.aimon.core.llm.exception.LlmPromptTooLongException},
     * {@link at.aimon.core.llm.exception.LlmAuthException}, and
     * {@link at.aimon.core.llm.exception.LlmInvalidRequestException} are <strong>not</strong> retryable at this layer:
     * they require caller intervention (prompt compaction, credential rotation, payload fix) and retrying would waste
     * quota.
     *
     * @return an immutable default policy (never {@code null})
     */
    public static LlmRetryPolicy defaultPolicy() {
        return builder().maxAttempts(3).backoffBase(Duration.ofMillis(500)).jitterFactor(0.2)
                .retryableExceptions(Set.of(LlmRateLimitedException.class, LlmOverloadedException.class)).build();
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder} (never {@code null})
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the maximum number of attempts, counting the initial call. {@code 1} disables retry.
     *
     * @return a positive integer
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Returns the base backoff delay used for the first retry. Subsequent retries use exponential doubling.
     *
     * @return a non-negative duration (never {@code null})
     */
    public Duration getBackoffBase() {
        return backoffBase;
    }

    /**
     * Returns the jitter factor applied to each backoff delay, expressed as a fraction of the base (not of the computed
     * exponential delay). See {@link #computeDelay(int, Random)} for the exact formula.
     *
     * @return a value in {@code [0.0, 1.0]}
     */
    public double getJitterFactor() {
        return jitterFactor;
    }

    /**
     * Returns the unmodifiable set of exception classes that are considered retryable. Matching is by subclass
     * assignment: registering a supertype implicitly includes every subclass.
     *
     * @return an immutable set (never {@code null}, possibly empty)
     */
    public Set<Class<? extends LlmClientException>> getRetryableExceptions() {
        return retryableExceptions;
    }

    /**
     * Returns {@code true} when {@code ex} is an instance of at least one of the registered retryable exception
     * classes.
     *
     * <p>
     * Matching is performed by {@link Class#isAssignableFrom(Class)} so that registering a supertype (for example
     * {@link LlmClientException} itself) implicitly matches every subclass.
     *
     * <p>
     * The sole exception is {@link LlmCallCancelledException}: it is a terminal, caller-initiated abort rather than a
     * transient provider failure, so this method returns {@code false} for it unconditionally — even when a supertype
     * that would otherwise match is registered. Retrying a call the caller explicitly cancelled would defeat the
     * cancellation.
     *
     * @param ex
     *            the exception to test (must not be {@code null})
     * @return {@code true} if a retry should be attempted, {@code false} otherwise
     * @throws NullPointerException
     *             if {@code ex} is {@code null}
     */
    public boolean isRetryable(LlmClientException ex) {
        Objects.requireNonNull(ex, "ex must not be null");
        if (ex instanceof LlmCallCancelledException) {
            return false;
        }
        for (Class<? extends LlmClientException> candidate : retryableExceptions) {
            if (candidate.isAssignableFrom(ex.getClass())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes the delay that should elapse before the retry identified by {@code attemptNumber}.
     *
     * <p>
     * The formula is:
     *
     * <pre>
     * exponential = backoffBase * 2^(attemptNumber - 1)
     * jitter      = (random.nextDouble() * 2 - 1) * jitterFactor * backoffBase
     * delay       = max(0, exponential + jitter)
     * </pre>
     *
     * Note that the jitter band is proportional to {@link #getBackoffBase()}, not to the exponential result — this
     * keeps the absolute randomness bounded across attempts and matches the semantics commonly seen in provider
     * guidance.
     *
     * <p>
     * {@code attemptNumber} is 1-based and refers to the retry index:
     * <ul>
     * <li>{@code 1} — delay before the first retry (i.e. after attempt 1 failed).
     * <li>{@code 2} — delay before the second retry, etc.
     * </ul>
     *
     * <p>
     * Supplying {@code attemptNumber <= 0} throws {@link IllegalArgumentException}.
     *
     * @param attemptNumber
     *            the 1-based retry index (must be {@code >= 1})
     * @param random
     *            a caller-supplied randomness source (must not be {@code null})
     * @return the delay to wait before the given retry (never {@code null}, never negative)
     * @throws IllegalArgumentException
     *             if {@code attemptNumber < 1}
     * @throws NullPointerException
     *             if {@code random} is {@code null}
     */
    public Duration computeDelay(int attemptNumber, Random random) {
        Objects.requireNonNull(random, "random must not be null");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1, got: " + attemptNumber);
        }

        final long baseNanos = backoffBase.toNanos();
        if (baseNanos == 0L) {
            return Duration.ZERO;
        }

        // Guard against overflow: cap the exponent so (base << shift) stays within a signed long.
        final int maxShift = Math.max(0, Long.numberOfLeadingZeros(baseNanos) - 1);
        final int shift = Math.min(attemptNumber - 1, maxShift);
        final long exponentialNanos = baseNanos << shift;

        // Jitter band is ±(jitterFactor * baseNanos), independent of the exponential multiplier.
        final double jitterNanos = (random.nextDouble() * 2.0 - 1.0) * jitterFactor * baseNanos;
        final long totalNanos = Math.max(0L, Math.addExact(exponentialNanos, (long) jitterNanos));
        return Duration.ofNanos(totalNanos);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmRetryPolicy that = (LlmRetryPolicy) o;
        return maxAttempts == that.maxAttempts && Double.compare(jitterFactor, that.jitterFactor) == 0
                && backoffBase.equals(that.backoffBase) && retryableExceptions.equals(that.retryableExceptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAttempts, backoffBase, jitterFactor, retryableExceptions);
    }

    @Override
    public String toString() {
        return "LlmRetryPolicy{" + "maxAttempts=" + maxAttempts + ", backoffBase=" + backoffBase + ", jitterFactor="
                + jitterFactor + ", retryableExceptions=" + retryableExceptions + '}';
    }

    /**
     * Mutable builder for {@link LlmRetryPolicy}. Not thread-safe.
     */
    public static final class Builder {
        private int maxAttempts = 1;
        private Duration backoffBase = Duration.ZERO;
        private double jitterFactor;
        private Set<Class<? extends LlmClientException>> retryableExceptions = new HashSet<>();

        private Builder() {
        }

        /**
         * Sets the maximum total number of attempts, counting the initial call. Must be {@code >= 1}.
         *
         * @param maxAttempts
         *            the attempt ceiling (must be {@code >= 1})
         * @return this builder
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the base backoff duration used for the first retry.
         *
         * @param backoffBase
         *            the base backoff (must not be {@code null}, must be non-negative)
         * @return this builder
         * @throws NullPointerException
         *             if {@code backoffBase} is {@code null}
         */
        public Builder backoffBase(Duration backoffBase) {
            this.backoffBase = Objects.requireNonNull(backoffBase, "backoffBase must not be null");
            return this;
        }

        /**
         * Sets the jitter factor applied to each backoff delay. Must be within {@code [0.0, 1.0]}.
         *
         * @param jitterFactor
         *            the jitter fraction (must be in {@code [0.0, 1.0]})
         * @return this builder
         */
        public Builder jitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
            return this;
        }

        /**
         * Replaces the current set of retryable exception classes.
         *
         * @param retryableExceptions
         *            the set of exception classes considered transient (must not be {@code null}; may be empty)
         * @return this builder
         * @throws NullPointerException
         *             if {@code retryableExceptions} is {@code null}
         */
        public Builder retryableExceptions(Set<Class<? extends LlmClientException>> retryableExceptions) {
            Objects.requireNonNull(retryableExceptions, "retryableExceptions must not be null");
            this.retryableExceptions = new HashSet<>(retryableExceptions);
            return this;
        }

        /**
         * Appends a retryable exception class to the current set.
         *
         * @param retryable
         *            the exception class (must not be {@code null})
         * @return this builder
         * @throws NullPointerException
         *             if {@code retryable} is {@code null}
         */
        public Builder addRetryableException(Class<? extends LlmClientException> retryable) {
            Objects.requireNonNull(retryable, "retryable must not be null");
            this.retryableExceptions.add(retryable);
            return this;
        }

        /**
         * Builds the {@link LlmRetryPolicy}.
         *
         * @return a new immutable policy (never {@code null})
         * @throws IllegalArgumentException
         *             if {@code maxAttempts < 1}, {@code backoffBase} is negative, or {@code jitterFactor} is outside
         *             {@code [0.0, 1.0]}
         * @throws NullPointerException
         *             if {@code backoffBase} or the retryable-exceptions set is {@code null}
         */
        public LlmRetryPolicy build() {
            return new LlmRetryPolicy(this);
        }
    }
}
