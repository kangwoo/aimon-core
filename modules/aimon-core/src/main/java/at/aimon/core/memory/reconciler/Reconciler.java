/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.memory.reconciler;

import java.util.List;

import at.aimon.core.memory.Observation;

/**
 * Single-source-of-truth for conflict resolution between a freshly produced
 * observation and any pre-existing observations that overlap with it
 * (design doc §6.4).
 *
 * <p>
 * Both the deriver (immediately after an LLM produces an observation) and the
 * dreamer (when the random-walk strategy collects a redundant cluster) call
 * this interface; keeping the policy in one place avoids two subtly different
 * conflict heuristics drifting apart.
 *
 * <p>
 * Implementations must be deterministic for a given input and free of
 * side-effects — they decide, they do not write. The caller is responsible for
 * acting on the {@link ReconcileDecision} (persisting a merge, dropping a
 * loser, etc.).
 */
public interface Reconciler {

    /**
     * Decide what should happen to {@code candidate} given the list of
     * pre-existing observations that conflict with it.
     *
     * <p>
     * {@code conflicts} is allowed to be empty; in that case implementations
     * should return {@link ReconcileDecision.Accept}. The returned decision
     * never references observations outside {@code candidate ∪ conflicts}.
     *
     * @param candidate
     *            the new observation under consideration (must not be null)
     * @param conflicts
     *            existing observations that the deriver / dreamer judged to
     *            collide with {@code candidate} (must not be null; may be
     *            empty)
     * @return the reconciliation outcome (never null)
     */
    ReconcileDecision evaluate(Observation candidate, List<Observation> conflicts);
}
