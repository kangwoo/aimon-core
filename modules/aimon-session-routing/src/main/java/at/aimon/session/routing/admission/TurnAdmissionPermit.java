package at.aimon.session.routing.admission;

/**
 * Permission granted by a {@link TurnAdmission} to run one pass on this node.
 *
 * <p>
 * The router calls {@link #release()} exactly once per permit it was granted, on every exit — success, failure, and
 * abandonment before the first turn starts. Implementations do not have to guard against a second call from the router;
 * a release that throws is logged and does not affect the turn.
 */
@FunctionalInterface
public interface TurnAdmissionPermit {

    /** Gives the capacity this permit represents back to the gate. */
    void release();

    /**
     * A permit whose release does nothing, for gates that do not count.
     *
     * @return the no-op permit
     */
    static TurnAdmissionPermit noop() {
        return AlwaysAdmit.NOOP_PERMIT;
    }
}
