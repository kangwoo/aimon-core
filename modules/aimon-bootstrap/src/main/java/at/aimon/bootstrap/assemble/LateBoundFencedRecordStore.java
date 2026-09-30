package at.aimon.bootstrap.assemble;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * The record store the stack's transcript manager and live sessions write through, fenced by a session store that does
 * not exist yet when they are built (session-log §12.3).
 *
 * <p>
 * The record-side twin of {@link LateBoundFencedSegmentStore}, for the same ordering reason: the session store that
 * owns the fenced write view ({@code SessionStore.records(fence)}) is composed inside the router's build, and the
 * router
 * is built over the executor, which is built over the transcript manager. So the manager is handed this, and
 * {@link #bind} points it at the router's view once the router exists. Reads go to the raw store from the start — a
 * read cannot corrupt history, and the reader of a sealed log reads sessions it does not hold.
 *
 * <p>
 * A mutation before {@link #bind} fails with an {@link IllegalStateException} rather than falling back to the raw
 * store: no turn can run before the router exists, so reaching it is a wiring bug, and an unfenced write is exactly
 * what
 * this class exists to prevent. {@link #clear()} goes to the fenced view, which refuses it.
 *
 * <p>
 * Thread-safe.
 */
public final class LateBoundFencedRecordStore implements SessionRecordStore {

    private final SessionRecordStore raw;
    private final AtomicReference<SessionRecordStore> fenced = new AtomicReference<>();

    /**
     * @param raw
     *            the application's record store, which the fenced view is built over too (must not be null)
     */
    public LateBoundFencedRecordStore(SessionRecordStore raw) {
        this.raw = Objects.requireNonNull(raw, "raw cannot be null");
    }

    /**
     * Points writes at the fenced view. Once only.
     *
     * @param fencedView
     *            the view the router's session store built over the same raw store (must not be null)
     * @throws IllegalStateException
     *             if already bound
     */
    public void bind(SessionRecordStore fencedView) {
        Objects.requireNonNull(fencedView, "fencedView cannot be null");
        if (!fenced.compareAndSet(null, fencedView)) {
            throw new IllegalStateException("The fenced record view is already bound");
        }
    }

    /**
     * @return whether {@link #bind} has run
     */
    public boolean isBound() {
        return fenced.get() != null;
    }

    @Override
    public void mergeFromSnapshot(SessionSnapshot snapshot) {
        requireBound().mergeFromSnapshot(snapshot);
    }

    @Override
    public SessionRecordView provision(SessionId sessionId, String agentRef) {
        return requireBound().provision(sessionId, agentRef);
    }

    @Override
    public SessionRecordView provision(SessionId sessionId) {
        return requireBound().provision(sessionId);
    }

    @Override
    public void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals, ExecutionBudget budgetOverride) {
        requireBound().setTotalsAndBudgetOverride(sessionId, totals, budgetOverride);
    }

    @Override
    public int incrementCompactionFailureCount(SessionId sessionId) {
        return requireBound().incrementCompactionFailureCount(sessionId);
    }

    @Override
    public void resetCompactionFailureCount(SessionId sessionId) {
        requireBound().resetCompactionFailureCount(sessionId);
    }

    @Override
    public Optional<SessionRecordView> load(SessionId sessionId) {
        return raw.load(sessionId);
    }

    @Override
    public void delete(SessionId sessionId) {
        requireBound().delete(sessionId);
    }

    @Override
    public List<SessionId> listSessionIds() {
        return raw.listSessionIds();
    }

    @Override
    public boolean exists(SessionId sessionId) {
        return raw.exists(sessionId);
    }

    @Override
    public void clear() {
        requireBound().clear();
    }

    private SessionRecordStore requireBound() {
        final SessionRecordStore view = fenced.get();
        if (view == null) {
            throw new IllegalStateException(
                    "Record write before the session router was built; the fenced record view is not bound yet");
        }
        return view;
    }
}
