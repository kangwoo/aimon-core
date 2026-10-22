package at.aimon.session.routing.wake;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.agent.session.SessionId;

/**
 * The default {@link WakeLedger}: this process's memory. Each node counts its own wakes; a restart starts the counts
 * again, which only ever errs toward waking.
 */
public final class InMemoryWakeLedger implements WakeLedger {

    private final Map<SessionId, WakeState> states = new ConcurrentHashMap<>();

    @Override
    public WakeState get(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return states.getOrDefault(sessionId, WakeState.empty());
    }

    @Override
    public void put(SessionId sessionId, WakeState state) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        states.put(sessionId, Objects.requireNonNull(state, "state must not be null"));
    }

    @Override
    public void remove(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        states.remove(sessionId);
    }
}
