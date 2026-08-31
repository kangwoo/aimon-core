package at.aimon.core.memory;

/**
 * Classification of how an {@link Observation} was derived from source messages.
 *
 * <p>
 * Used both for downstream consumers (UI, reasoning) and for the default
 * confidence base score computed by the Deriver.
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
    DEDUCTIVE(0.6d);

    private final double baseConfidence;

    ObservationType(double baseConfidence) {
        this.baseConfidence = baseConfidence;
    }

    /**
     * The design doc §4.3 base confidence score for this type ({@code EXPLICIT=0.9},
     * {@code DEDUCTIVE=0.6}). The Deriver computes an observation's confidence from this base plus
     * reinforcement/contradiction adjustments — it is never self-reported by the LLM.
     */
    public double baseConfidence() {
        return baseConfidence;
    }
}
