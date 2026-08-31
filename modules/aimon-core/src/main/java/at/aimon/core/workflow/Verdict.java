package at.aimon.core.workflow;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of an {@link WorkflowPatterns#adversarialVerify adversarial-verify} pass — a majority vote over N
 * independent skeptics that each tried to refute a finding (design §6.4). Immutable.
 *
 * <p>
 * Only skeptics that produced a machine-readable {@code refuted} verdict count as votes; a null result (an isolated
 * thunk failure) or an empty/unparseable structured output is a <em>non-vote</em> (abstention) and shrinks neither the
 * numerator nor the denominator. When too few valid votes remain ({@code nValid < quorum}) the verdict is
 * {@link #isInconclusive() inconclusive} rather than a defaulted survive/kill. A decisive verdict survives iff the
 * refutations are strictly fewer than half of the valid votes; an exact tie does NOT survive (fail-closed).
 */
public final class Verdict {

    private final boolean survived;
    private final boolean inconclusive;
    private final int refutations;
    private final int validations;
    private final int total;
    private final int quorum;
    private final List<Boolean> votes;

    private Verdict(boolean survived, boolean inconclusive, int refutations, int validations, int total, int quorum,
            List<Boolean> votes) {
        this.survived = survived;
        this.inconclusive = inconclusive;
        this.refutations = refutations;
        this.validations = validations;
        this.total = total;
        this.quorum = quorum;
        this.votes = List.copyOf(votes);
    }

    /**
     * Tallies the skeptic votes into a verdict.
     *
     * @param votes
     *            the valid votes ({@code true} = the skeptic refuted the finding), non-votes already excluded (must not
     *            be null)
     * @param total
     *            the number of skeptics dispatched (must be &gt;= {@code votes.size()})
     * @param quorum
     *            the minimum valid votes required for a decisive verdict (must be &gt;= 1)
     * @return the verdict — inconclusive when {@code votes.size() < quorum}, else survived iff the refutations are
     *         strictly fewer than half of the valid votes; an exact tie does NOT survive (fail-closed)
     * @throws IllegalArgumentException
     *             if {@code quorum < 1} or {@code total < votes.size()}
     */
    public static Verdict tally(List<Boolean> votes, int total, int quorum) {
        Objects.requireNonNull(votes, "votes cannot be null");
        if (quorum < 1) {
            throw new IllegalArgumentException("quorum must be >= 1, got: " + quorum);
        }
        if (total < votes.size()) {
            throw new IllegalArgumentException("total must be >= votes.size() (" + votes.size() + "), got: " + total);
        }
        int refutations = 0;
        for (final Boolean v : votes) {
            if (Boolean.TRUE.equals(v)) {
                refutations++;
            }
        }
        final int nValid = votes.size();
        final int validations = nValid - refutations;
        // Defence in depth: zero valid votes must never be decisive, even if quorum handling ever regresses.
        final boolean inconclusive = nValid < Math.max(1, quorum);
        // Survives iff the refutations are strictly fewer than half of the valid votes; an exact tie does NOT
        // survive (fail-closed).
        final boolean survived = !inconclusive && refutations * 2 < nValid;
        return new Verdict(survived, inconclusive, refutations, validations, total, quorum, votes);
    }

    /**
     * @return {@code true} if the finding survived, i.e. the refutations are strictly fewer than half of the valid
     *         votes (an exact tie does NOT survive: fail-closed); {@code false} if refuted or inconclusive
     */
    public boolean isSurvived() {
        return survived;
    }

    /** @return {@code true} if too few skeptics produced a valid vote to decide ({@code nValid < quorum}) */
    public boolean isInconclusive() {
        return inconclusive;
    }

    /** @return the number of skeptics that refuted the finding */
    public int getRefutations() {
        return refutations;
    }

    /** @return the number of skeptics that validated (did not refute) the finding */
    public int getValidations() {
        return validations;
    }

    /** @return the number of valid votes ({@code refutations + validations}) */
    public int getValidVotes() {
        return refutations + validations;
    }

    /** @return the number of skeptics dispatched (valid votes plus abstentions) */
    public int getTotal() {
        return total;
    }

    /** @return the quorum threshold applied */
    public int getQuorum() {
        return quorum;
    }

    /** @return the valid votes in dispatch order ({@code true} = refuted); never null */
    public List<Boolean> getVotes() {
        return votes;
    }

    @Override
    public String toString() {
        return "Verdict{survived=" + survived + ", inconclusive=" + inconclusive + ", refutations=" + refutations
                + ", validations=" + validations + ", total=" + total + ", quorum=" + quorum + '}';
    }
}
