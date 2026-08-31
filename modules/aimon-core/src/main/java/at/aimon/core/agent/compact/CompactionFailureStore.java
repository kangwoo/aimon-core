package at.aimon.core.agent.compact;

import at.aimon.core.agent.session.SessionId;

/**
 * Storage abstraction for the per-session consecutive AUTO compaction failure counter consumed by
 * {@link DefaultCompactionGuard}'s circuit breaker.
 *
 * <p>
 * The contract is intentionally narrow:
 * <ul>
 * <li>{@link #get(SessionId)} returns the current counter value, or {@code 0} if no record exists.
 * <li>{@link #recordFailure(SessionId)} atomically increments the counter and returns the new value.
 * <li>{@link #reset(SessionId)} clears the counter back to {@code 0}.
 * </ul>
 *
 * <h2>Two implementations ship, and the choice is a deployment's</h2>
 *
 * <p>
 * The default {@link InMemoryCompactionFailureStore} keeps state in a per-process LRU map, so each instance breaks its
 * own circuit independently. That is the right answer for a single-instance deployment and the wrong one for a session
 * whose turns land on different nodes: three consecutive failures spread over three nodes look like one failure each,
 * and the breaker never trips.
 *
 * <p>
 * {@link SessionRecordCompactionFailureStore} is the shared alternative. It counts in
 * {@code SessionRecord.compactionFailureCount} through the two {@code SessionRecordStore} primitives built for it —
 * {@code incrementCompactionFailureCount}, which is a <em>delta</em> precisely because no node holds a copy of the
 * streak to restate, and {@code resetCompactionFailureCount}. Hand it {@code SessionStore.records()} so the writes are
 * fenced against the lease this node holds.
 *
 * <p>
 * A deployment with a durable {@code SessionRecordStore} therefore gets a cluster-wide breaker without writing any
 * storage code; one with the bundled in-memory record store gets the same semantics scoped to the process, which is
 * the same reach the in-memory default has. Either way the counter is not this interface's problem — implementations
 * bringing their own storage remain perfectly legitimate.
 *
 * <h2>Zero means "not counted"</h2>
 *
 * <p>
 * {@link #recordFailure(SessionId)} may return {@code 0} when the store has nowhere to put the increment — the
 * record-backed implementation does so for a run that has no session record of its own, such as a subagent fork
 * identified by its transcript label. Such a run simply never trips the shared breaker. Callers must not read {@code 0}
 * as "the first failure"; the value is a count, and {@code 0} means none has been recorded.
 *
 * <p>
 * Implementations must be thread-safe, and must not throw out of any of the three methods for reasons that are not
 * their caller's business: the guard calls {@link #recordFailure(SessionId)} while handling a failed compaction, and a
 * failure to <em>record</em> a compaction failure is not itself one.
 */
public interface CompactionFailureStore {

    /**
     * Returns the current consecutive failure count for the given session, or {@code 0} if none recorded.
     *
     * @param sessionId
     *            The session ID (must not be null)
     * @return Current counter value (always {@code >= 0})
     */
    int get(SessionId sessionId);

    /**
     * Atomically increments the failure counter for the given session by one and returns the new value, or {@code 0}
     * if this store has nowhere to count it (see "Zero means not counted" above).
     *
     * @param sessionId
     *            The session ID (must not be null)
     * @return The new counter value, or {@code 0} if nothing was counted (always {@code >= 0})
     */
    int recordFailure(SessionId sessionId);

    /**
     * Resets the failure counter for the given session to zero. No-op if no record exists.
     *
     * @param sessionId
     *            The session ID (must not be null)
     */
    void reset(SessionId sessionId);
}
