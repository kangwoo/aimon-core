package at.aimon.session.routing.wake;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

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
    public WakeState update(SessionId sessionId, UnaryOperator<WakeState> change) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(change, "change must not be null");
        return states.compute(sessionId, (id, current) -> Objects
                .requireNonNull(change.apply(current != null ? current : WakeState.empty()), "change returned null"));
    }

    @Override
    public void remove(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        states.remove(sessionId);
    }
}
