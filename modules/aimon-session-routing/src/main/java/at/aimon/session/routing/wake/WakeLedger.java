package at.aimon.session.routing.wake;

import at.aimon.core.agent.session.SessionId;

/**
 * Where an {@link IdleSessionWaker} keeps its per-session counters — the part that could be shared between nodes so a
 * session's caps hold across all of them (design §4.3, open item W-2). The default {@link InMemoryWakeLedger} counts
 * per node.
 *
 * <p>
 * Implementations must be thread-safe. A method that fails throws an unchecked exception; the waker then does not wake
 * that session this time.
 */
public interface WakeLedger {

    /**
     * @param sessionId
     *            the session (must not be null)
     * @return its state, or {@link WakeState#empty()} if none is kept (never null)
     */
    WakeState get(SessionId sessionId);

    /**
     * Replaces a session's state.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param state
     *            the new state (must not be null)
     */
    void put(SessionId sessionId, WakeState state);

    /**
     * Forgets a session — it was deleted.
     *
     * @param sessionId
     *            the session (must not be null)
     */
    void remove(SessionId sessionId);
}
