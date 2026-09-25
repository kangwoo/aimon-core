package at.aimon.bootstrap.assemble;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;

/**
 * The segment store the stack's transcript manager deletes through, fenced by a session store that does not exist yet
 * when the manager is built.
 *
 * <p>
 * The stack builds the transcript manager, then the executor over it, then the router over the executor — and the
 * session store that owns the fenced delete view ({@code SessionStore.segments(...)}) is composed inside the router's
 * build. So the manager cannot be handed the view directly. It is handed this instead, and {@link #bind} points it at
 * the router's view once the router exists. Reads, writes and scans go to the raw store from the start: only deletes
 * are fenced (session-log §5.6).
 *
 * <p>
 * A delete before {@link #bind} fails with a {@link SessionLogSegmentStoreException} rather than falling back to the
 * raw store — no turn can run before the router exists, so reaching it would be a wiring bug, and an unfenced delete is
 * exactly what this class exists to prevent. The callers (garbage collection, {@code /clear}) already treat a failed
 * delete as "leave it for later".
 *
 * <p>
 * Thread-safe.
 */
public final class LateBoundFencedSegmentStore implements SessionLogSegmentStore {

    private final SessionLogSegmentStore raw;
    private final AtomicReference<SessionLogSegmentStore> fenced = new AtomicReference<>();

    /**
     * @param raw
     *            the application's segment store (must not be null)
     */
    public LateBoundFencedSegmentStore(SessionLogSegmentStore raw) {
        this.raw = Objects.requireNonNull(raw, "raw cannot be null");
    }

    /**
     * Points deletes at the fenced view. Once only.
     *
     * @param fencedView
     *            the view the router's session store built over the same raw store (must not be null)
     * @throws IllegalStateException
     *             if already bound
     */
    public void bind(SessionLogSegmentStore fencedView) {
        Objects.requireNonNull(fencedView, "fencedView cannot be null");
        if (!fenced.compareAndSet(null, fencedView)) {
            throw new IllegalStateException("The fenced segment view is already bound");
        }
    }

    /**
     * @return whether {@link #bind} has run
     */
    public boolean isBound() {
        return fenced.get() != null;
    }

    @Override
    public void put(SessionLogSegment segment) {
        raw.put(segment);
    }

    @Override
    public Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id) {
        return raw.get(sessionId, id);
    }

    @Override
    public List<SegmentInfo> list(SessionId sessionId) {
        return raw.list(sessionId);
    }

    @Override
    public SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit) {
        return raw.scanSessions(createdBefore, cursor, limit);
    }

    @Override
    public void delete(SessionId sessionId, SegmentId id) {
        requireBound().delete(sessionId, id);
    }

    @Override
    public void deleteAll(SessionId sessionId) {
        requireBound().deleteAll(sessionId);
    }

    private SessionLogSegmentStore requireBound() {
        final SessionLogSegmentStore view = fenced.get();
        if (view == null) {
            throw new SessionLogSegmentStoreException(
                    "Segment delete before the session router was built; the fenced delete view is not bound yet");
        }
        return view;
    }
}
