package at.aimon.core.llm.retry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;

/**
 * Immutable policy describing an ordered chain of {@link LlmModel} configurations the caller should try when the
 * current model fails with an <em>activating</em> exception.
 *
 * <p>
 * Typical use case: when the primary model returns {@link at.aimon.core.llm.exception.LlmOverloadedException} or
 * {@link at.aimon.core.llm.exception.LlmPromptTooLongException}, the caller escalates to a larger / alternative model
 * before surfacing the error to the user.
 *
 * <h2>Semantics</h2>
 * <ul>
 * <li>The chain is ordered: {@link #nextModel(LlmModel, LlmClientException)} returns the model immediately following
 * the supplied {@code current} model. Advancement is always by exactly one position.
 * <li>If {@code current} is not present in the chain, advancement is not possible and {@link Optional#empty()} is
 * returned. This keeps the policy side-effect-free and prevents accidentally "resetting" to the head.
 * <li>If the exception class does not match any activating class (including subclass matches), {@link Optional#empty()}
 * is returned. The caller is expected to rethrow the exception.
 * <li>If {@code current} is the last element of the chain, {@link Optional#empty()} is returned — the caller has
 * exhausted the fallback options.
 * </ul>
 *
 * <h2>Consecutive-failure threshold</h2>
 *
 * <p>
 * {@link #getConsecutiveFailureThreshold()} is a <em>hint</em> for the consuming gateway: the number of consecutive
 * activating failures that must accumulate on the <em>same</em> model before the gateway should escalate to the next
 * model. The default is {@code 1} — escalate on the first activating failure, preserving the historical "switch
 * immediately" behavior. A value {@code > 1} lets the gateway ride out transient blips (e.g. a single 503) by retrying
 * the same model first, only crossing over once the model looks durably unhealthy. The policy itself stays stateless:
 * it merely reports the threshold and whether a given exception {@link #isActivating(LlmClientException) activates};
 * the gateway owns the counter.
 *
 * <h2>Scope</h2>
 *
 * <p>
 * This class is a pure model: it does not invoke {@link at.aimon.core.llm.LlmClient}, record metrics, or maintain
 * per-call state. The gateway that consumes this policy (RETRY-03) is responsible for iterating and actually issuing
 * the calls.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * Instances are immutable and thread-safe.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * {@code
 * LlmFallbackPolicy policy = LlmFallbackPolicy.builder()
 *         .fallbackChain(List.of(primary, secondary, tertiary))
 *         .activatingExceptions(Set.of(LlmOverloadedException.class))
 *         .build();
 *
 * Optional<LlmModel> next = policy.nextModel(primary, ex);
 * }
 * </pre>
 */
public final class LlmFallbackPolicy {

    private static final LlmFallbackPolicy NONE = new LlmFallbackPolicy(new Builder());

    private final List<LlmModel> fallbackChain;
    private final Set<Class<? extends LlmClientException>> activatingExceptions;
    private final int consecutiveFailureThreshold;

    private LlmFallbackPolicy(Builder builder) {
        Objects.requireNonNull(builder.fallbackChain, "fallbackChain must not be null");
        Objects.requireNonNull(builder.activatingExceptions, "activatingExceptions must not be null");
        final Set<LlmModel> seenModels = new HashSet<>();
        for (LlmModel model : builder.fallbackChain) {
            Objects.requireNonNull(model, "fallbackChain must not contain null entries");
            // Reject duplicate/cyclic chains. nextModel() advances via indexOf(current) (first match), so a repeated
            // model (e.g. [A, A] or [A, B, A]) would make the consuming gateway — which resets its attempt/failure
            // counters on each escalation — cycle back to an already-tried model and issue paid provider calls in an
            // unbounded loop. Requiring distinct entries keeps advancement strictly monotonic and chain-bounded.
            if (!seenModels.add(model)) {
                throw new IllegalArgumentException(
                        "fallbackChain must not contain duplicate models (a cyclic chain would cause an unbounded "
                                + "fallback loop): " + model.getName().orElse("<unnamed>"));
            }
        }
        for (Class<? extends LlmClientException> cls : builder.activatingExceptions) {
            Objects.requireNonNull(cls, "activatingExceptions must not contain null entries");
        }
        if (builder.consecutiveFailureThreshold < 1) {
            throw new IllegalArgumentException(
                    "consecutiveFailureThreshold must be >= 1, got: " + builder.consecutiveFailureThreshold);
        }

        this.fallbackChain = List.copyOf(builder.fallbackChain);
        this.activatingExceptions = Set.copyOf(builder.activatingExceptions);
        this.consecutiveFailureThreshold = builder.consecutiveFailureThreshold;
    }

    /**
     * Returns a no-op policy that has an empty chain and never advances.
     *
     * <p>
     * This is a safe default when the caller wants to opt out of model fallback without special-casing {@code null}
     * policies.
     *
     * @return a shared no-op policy (never {@code null})
     */
    public static LlmFallbackPolicy none() {
        return NONE;
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
     * Returns the ordered, unmodifiable fallback chain.
     *
     * @return an immutable list (never {@code null}, possibly empty)
     */
    public List<LlmModel> getFallbackChain() {
        return fallbackChain;
    }

    /**
     * Returns the unmodifiable set of exception classes that activate model advancement.
     *
     * <p>
     * Matching is by {@link Class#isAssignableFrom(Class)}, so registering a supertype implicitly includes every
     * subclass.
     *
     * @return an immutable set (never {@code null}, possibly empty)
     */
    public Set<Class<? extends LlmClientException>> getActivatingExceptions() {
        return activatingExceptions;
    }

    /**
     * Returns the number of consecutive activating failures that must accumulate on the same model before the gateway
     * should escalate to the next model. Defaults to {@code 1} (escalate on the first activating failure).
     *
     * <p>
     * This is advisory: the consuming gateway owns the actual counter and interprets this value. The policy remains
     * stateless.
     *
     * @return the threshold (always {@code >= 1})
     */
    public int getConsecutiveFailureThreshold() {
        return consecutiveFailureThreshold;
    }

    /**
     * Returns the next {@link LlmModel} the caller should try, if any.
     *
     * <p>
     * Advancement occurs iff <strong>all</strong> of the following hold:
     * <ol>
     * <li>{@code ex} is an instance of at least one class in {@link #getActivatingExceptions()}.
     * <li>{@code current} is present in {@link #getFallbackChain()}.
     * <li>{@code current} is not the last element of the chain.
     * </ol>
     *
     * @param current
     *            the model the failing call used (must not be {@code null})
     * @param ex
     *            the exception that terminated the failing call (must not be {@code null})
     * @return the next model if advancement is permitted; {@link Optional#empty()} otherwise
     * @throws NullPointerException
     *             if either argument is {@code null}
     */
    public Optional<LlmModel> nextModel(LlmModel current, LlmClientException ex) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(ex, "ex must not be null");

        if (!isActivating(ex)) {
            return Optional.empty();
        }
        final int idx = fallbackChain.indexOf(current);
        if (idx < 0 || idx >= fallbackChain.size() - 1) {
            return Optional.empty();
        }
        return Optional.of(fallbackChain.get(idx + 1));
    }

    /**
     * Reports whether the given exception activates model fallback under this policy — i.e. whether {@code ex} is an
     * instance of at least one class in {@link #getActivatingExceptions()} (subclass matches included).
     *
     * <p>
     * Exposed so a gateway can decide whether a failure counts toward the {@link #getConsecutiveFailureThreshold()
     * consecutive-failure} counter independently of whether a next model actually exists in the chain.
     *
     * <p>
     * {@link LlmCallCancelledException} never activates fallback: it is a terminal, caller-initiated abort —
     * categorically not a provider-health signal — so this method returns {@code false} for it unconditionally, even
     * when a supertype that would otherwise match is registered. Consequently
     * {@link #nextModel(LlmModel, LlmClientException)} never advances on a cancelled call. This is defense-in-depth
     * alongside the gateway's terminal handling of cancellation.
     *
     * @param ex
     *            the exception that terminated a failing call (must not be {@code null})
     * @return {@code true} if the exception activates fallback; {@code false} otherwise
     * @throws NullPointerException
     *             if {@code ex} is {@code null}
     */
    public boolean isActivating(LlmClientException ex) {
        Objects.requireNonNull(ex, "ex must not be null");
        if (ex instanceof LlmCallCancelledException) {
            return false;
        }
        for (Class<? extends LlmClientException> candidate : activatingExceptions) {
            if (candidate.isAssignableFrom(ex.getClass())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmFallbackPolicy that = (LlmFallbackPolicy) o;
        return consecutiveFailureThreshold == that.consecutiveFailureThreshold
                && fallbackChain.equals(that.fallbackChain) && activatingExceptions.equals(that.activatingExceptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fallbackChain, activatingExceptions, consecutiveFailureThreshold);
    }

    @Override
    public String toString() {
        return "LlmFallbackPolicy{" + "fallbackChain=" + fallbackChain + ", activatingExceptions="
                + activatingExceptions + ", consecutiveFailureThreshold=" + consecutiveFailureThreshold + '}';
    }

    /**
     * Mutable builder for {@link LlmFallbackPolicy}. Not thread-safe.
     */
    public static final class Builder {
        private List<LlmModel> fallbackChain = new ArrayList<>();
        private Set<Class<? extends LlmClientException>> activatingExceptions = new HashSet<>();
        private int consecutiveFailureThreshold = 1;

        private Builder() {
        }

        /**
         * Replaces the current fallback chain.
         *
         * @param fallbackChain
         *            the ordered list of fallback models (must not be {@code null} or contain {@code null})
         * @return this builder
         * @throws NullPointerException
         *             if {@code fallbackChain} is {@code null}
         */
        public Builder fallbackChain(List<LlmModel> fallbackChain) {
            Objects.requireNonNull(fallbackChain, "fallbackChain must not be null");
            this.fallbackChain = new ArrayList<>(fallbackChain);
            return this;
        }

        /**
         * Appends a model to the fallback chain.
         *
         * @param model
         *            the model to append (must not be {@code null})
         * @return this builder
         * @throws NullPointerException
         *             if {@code model} is {@code null}
         */
        public Builder addFallback(LlmModel model) {
            Objects.requireNonNull(model, "model must not be null");
            this.fallbackChain.add(model);
            return this;
        }

        /**
         * Replaces the set of activating exception classes.
         *
         * @param activatingExceptions
         *            the set of exception classes that trigger advancement (must not be {@code null}; may be empty)
         * @return this builder
         * @throws NullPointerException
         *             if {@code activatingExceptions} is {@code null}
         */
        public Builder activatingExceptions(Set<Class<? extends LlmClientException>> activatingExceptions) {
            Objects.requireNonNull(activatingExceptions, "activatingExceptions must not be null");
            this.activatingExceptions = new HashSet<>(activatingExceptions);
            return this;
        }

        /**
         * Appends an activating exception class to the set.
         *
         * @param activating
         *            the exception class (must not be {@code null})
         * @return this builder
         * @throws NullPointerException
         *             if {@code activating} is {@code null}
         */
        public Builder addActivatingException(Class<? extends LlmClientException> activating) {
            Objects.requireNonNull(activating, "activating must not be null");
            this.activatingExceptions.add(activating);
            return this;
        }

        /**
         * Sets the number of consecutive activating failures required on the same model before the gateway escalates to
         * the next model. Defaults to {@code 1}.
         *
         * @param consecutiveFailureThreshold
         *            the threshold (must be {@code >= 1})
         * @return this builder
         */
        public Builder consecutiveFailureThreshold(int consecutiveFailureThreshold) {
            this.consecutiveFailureThreshold = consecutiveFailureThreshold;
            return this;
        }

        /**
         * Builds the {@link LlmFallbackPolicy}.
         *
         * @return a new immutable policy (never {@code null})
         * @throws NullPointerException
         *             if either configuration set contains {@code null} entries
         * @throws IllegalArgumentException
         *             if {@code consecutiveFailureThreshold < 1}
         */
        public LlmFallbackPolicy build() {
            return new LlmFallbackPolicy(this);
        }
    }
}
