/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.memory.reconciler;

import java.util.Objects;

import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;

/**
 * Outcome of a {@link Reconciler#evaluate} call — what should happen when a
 * candidate observation meets a set of pre-existing conflicts (design doc
 * §6.4).
 *
 * <p>
 * Sealed hierarchy with four final-class variants. The contract is exhaustive:
 * a reconciler must return exactly one of {@link Accept}, {@link Replace},
 * {@link Merge}, {@link Reject}. {@code record} is intentionally not used so
 * the codebase keeps a single value-object pattern (see
 * {@code .claude/rules/code-style.md}).
 *
 * <p>
 * Variant semantics:
 *
 * <ul>
 * <li>{@link Accept} — keep the candidate as-is, no existing observation is
 * touched.</li>
 * <li>{@link Replace} — the candidate supersedes a single existing
 * observation; callers should mark {@code supersededId} as merged into the
 * candidate.</li>
 * <li>{@link Merge} — produce a new merged observation that subsumes the
 * candidate and {@code otherId}; callers should write {@code merged} and drop
 * both originals.</li>
 * <li>{@link Reject} — discard the candidate; the existing store stays
 * untouched and {@code reason} is logged for audit.</li>
 * </ul>
 */
public sealed interface ReconcileDecision
        permits ReconcileDecision.Accept, ReconcileDecision.Replace, ReconcileDecision.Merge, ReconcileDecision.Reject {

    /** Singleton accept — accept the candidate, leave existing observations alone. */
    final class Accept implements ReconcileDecision {
        private static final Accept INSTANCE = new Accept();

        private Accept() {
        }

        public static Accept instance() {
            return INSTANCE;
        }

        @Override
        public String toString() {
            return "Accept";
        }
    }

    /** Candidate replaces a single existing observation identified by {@link #getSupersededId()}. */
    final class Replace implements ReconcileDecision {
        private final ObservationId supersededId;

        public Replace(ObservationId supersededId) {
            this.supersededId = Objects.requireNonNull(supersededId, "supersededId cannot be null");
        }

        public ObservationId getSupersededId() {
            return supersededId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Replace other)) {
                return false;
            }
            return supersededId.equals(other.supersededId);
        }

        @Override
        public int hashCode() {
            return supersededId.hashCode();
        }

        @Override
        public String toString() {
            return "Replace{supersededId=" + supersededId + "}";
        }
    }

    /**
     * Candidate and {@link #getOtherId()} are folded into {@link #getMerged()}.
     * Both original observations should be dropped after the merged one is
     * persisted.
     */
    final class Merge implements ReconcileDecision {
        private final ObservationId otherId;
        private final Observation merged;

        public Merge(ObservationId otherId, Observation merged) {
            this.otherId = Objects.requireNonNull(otherId, "otherId cannot be null");
            this.merged = Objects.requireNonNull(merged, "merged cannot be null");
        }

        public ObservationId getOtherId() {
            return otherId;
        }

        public Observation getMerged() {
            return merged;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Merge other)) {
                return false;
            }
            return otherId.equals(other.otherId) && merged.equals(other.merged);
        }

        @Override
        public int hashCode() {
            return 31 * otherId.hashCode() + merged.hashCode();
        }

        @Override
        public String toString() {
            return "Merge{otherId=" + otherId + ", merged=" + merged + "}";
        }
    }

    /** Candidate is rejected; {@link #getReason()} is a short audit string. */
    final class Reject implements ReconcileDecision {
        private final String reason;

        public Reject(String reason) {
            this.reason = Objects.requireNonNull(reason, "reason cannot be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason cannot be blank");
            }
        }

        public String getReason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Reject other)) {
                return false;
            }
            return reason.equals(other.reason);
        }

        @Override
        public int hashCode() {
            return reason.hashCode();
        }

        @Override
        public String toString() {
            return "Reject{reason=" + reason + "}";
        }
    }
}
