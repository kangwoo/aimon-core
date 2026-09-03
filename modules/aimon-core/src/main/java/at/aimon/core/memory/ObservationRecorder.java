package at.aimon.core.memory;

/**
 * {@link MemoryCapability#OBSERVE} — records a single fact directly, without going through derivation.
 *
 * <p>
 * Implementations must be thread-safe.
 */
public interface ObservationRecorder {

    /**
     * Records {@code draft} and returns it as stored, with the identity the backend assigned.
     *
     * @param draft
     *            the fact to record, already redacted (must not be null)
     * @return the stored observation, never null
     * @throws NullPointerException
     *             if {@code draft} is null
     */
    Observation observe(ObservationDraft draft);

    /**
     * Returns whether this backend actually stores {@link ObservationDraft#getConfidence()}.
     *
     * <p>
     * A question, not a query — which is why it takes no argument. It matters because confidence is <em>shown to the
     * model</em>: a backend that drops the value and hands back a placeholder gives the model a plausible false number
     * and leaves it believing the value it chose was kept. Callers read this before offering one, and the observe tool
     * removes {@code confidence} from its input schema entirely when the answer is {@code false} — a parameter the
     * model cannot send is a round trip that cannot lose anything.
     *
     * @return {@code true} when the offered confidence survives the write
     */
    boolean storesConfidence();
}
