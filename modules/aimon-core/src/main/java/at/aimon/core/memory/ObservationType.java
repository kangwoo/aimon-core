package at.aimon.core.memory;

/**
 * Classification of how an {@link Observation} was derived from source messages.
 *
 * <p>
 * Used both for downstream consumers (UI, reasoning) and for the default
 * confidence base score computed by the Deriver.
 *
 * <p>
 * <b>Four values, because two lost information.</b> The vocabulary a memory
 * backend can hand back has four distinctions in it, and mapping those onto two
 * values collapsed half of them into {@link #DEDUCTIVE} — an inference from a
 * pattern and a recorded conflict would both have been filed as "inferred from
 * messages". The widening is AIMON's own vocabulary catching up, not a schema
 * being matched to somebody else's: the names coincide because both systems
 * name the same four things.
 *
 * <p>
 * IMPORTANT: adding values <b>breaks downgrade</b>. Once an
 * {@link #INDUCTIVE} or {@link #CONTRADICTION} observation has been written to a
 * file, Mongo or Postgres, an older jar reading it back throws from
 * {@code valueOf}. Neither mitigation is worth taking: folding the new values
 * down on write gives up the distinction the widening exists for, and a lenient
 * {@code valueOf} would have to be added to the jar that is already released.
 * Recorded in {@code CHANGELOG.md} instead.
 *
 * <p>
 * The in-tree Deriver still produces only {@link #EXPLICIT} and
 * {@link #DEDUCTIVE}; the two new values exist for backends whose own
 * classification is finer.
 */
public enum ObservationType {

    /**
     * The observation is a verbatim restatement of something the subject said.
     * Higher confidence base score.
     */
    EXPLICIT(0.9d),

    /**
     * The observation is inferred from messages but not directly stated.
     * Lower confidence base score.
     */
    DEDUCTIVE(0.6d),

    /**
     * The observation generalises from repeated evidence rather than following
     * from any one message — "usually deploys on Fridays" from having seen it
     * happen. Weaker than a deduction, because the next instance can break it.
     */
    INDUCTIVE(0.4d),

    /**
     * The observation records that two pieces of evidence disagree. It is worth
     * keeping and the least safe of the four to act on, so it carries the lowest
     * base score.
     */
    CONTRADICTION(0.3d);

    private final double baseConfidence;

    ObservationType(double baseConfidence) {
        this.baseConfidence = baseConfidence;
    }

    /**
     * The design doc §4.3 base confidence score for this type ({@code EXPLICIT=0.9},
     * {@code DEDUCTIVE=0.6}, {@code INDUCTIVE=0.4}, {@code CONTRADICTION=0.3}). The Deriver computes an observation's
     * confidence from this base plus reinforcement/contradiction adjustments — it is never self-reported by the LLM.
     *
     * @return the base score in {@code [0, 1]}
     */
    public double baseConfidence() {
        return baseConfidence;
    }
}
