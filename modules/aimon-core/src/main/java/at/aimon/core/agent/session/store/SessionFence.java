package at.aimon.core.agent.session.store;

/**
 * Which writes a fenced view of the session stores lets through (session-log §5.4, §12.3).
 *
 * <p>
 * Both policies re-prove the answer at the lease authority ({@link SessionLeaseStore#findHolder}) before every
 * mutation, and both leave the same sub-millisecond window between that re-proof and the delegated write that
 * {@link SessionStore} documents. They differ in what they do with a session <em>nobody</em> holds.
 */
public enum SessionFence {

    /**
     * A write lands only for a session this node holds a current lease on. The distributed policy: every live session
     * is opened through the router, which claims the session first, so a write for an unheld session is either a
     * coordination bug or a node that lost the session and has not noticed yet — both refused.
     */
    HOLDER_ONLY,

    /**
     * A write lands unless the lease authority names a holder other than this node's current lease. A session held by
     * this node passes, and so does a session nobody holds; one held by anybody else is refused.
     *
     * <p>
     * The single-node policy for a stack that has a durable lease store. There a live session may be opened outside the
     * router — the CLI's — and never hold a lease, so {@link #HOLDER_ONLY} would refuse all of its writes. What this
     * policy still stops is the case a durable lease store makes possible: a second node taking a session this one ran,
     * and this node's late save or delete landing behind it. What it does not stop is a late write for a session whose
     * lease lapsed and that nobody holds at the moment of the re-proof: a node that claims the session right after can
     * still read the record before that write lands.
     */
    UNLESS_HELD_ELSEWHERE
}
