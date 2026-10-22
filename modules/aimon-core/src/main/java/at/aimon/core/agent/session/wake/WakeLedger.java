package at.aimon.core.agent.session.wake;

import java.util.function.UnaryOperator;

import at.aimon.core.agent.session.SessionId;

/**
 * Where an {@code IdleSessionWaker} keeps its per-session counters — the part that could be shared between nodes so a
 * session's caps hold across all of them (design §4.3, open item W-2). The default {@link InMemoryWakeLedger} counts
 * per node.
 *
 * <p>
 * Changes go through {@link #update(SessionId, UnaryOperator)}, which must be atomic per session: the waker changes a
 * session's state from more than one place (a wake, the end of its turn), and a shared implementation has other nodes
 * doing the same. A plain read-then-write would lose counts.
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
     * Atomically replaces a session's state with what {@code change} makes of it.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param change
     *            maps the current state ({@link WakeState#empty()} if none) to the new one; may be called more than
     *            once by an implementation that retries, so it must be side-effect free (must not be null)
     * @return the new state
     */
    WakeState update(SessionId sessionId, UnaryOperator<WakeState> change);

    /**
     * Forgets a session — it was deleted.
     *
     * @param sessionId
     *            the session (must not be null)
     */
    void remove(SessionId sessionId);
}
