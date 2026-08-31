package at.aimon.core.agent.session.store;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionNotHeldException;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * The composite {@link SessionStore}: a shared {@link SessionLeaseStore} for election, a shared
 * {@link SessionRecordStore} for the durable record, and a node-local map of the leases this node currently holds.
 *
 * <h2>Node-scoped, over application-scoped backends</h2>
 *
 * <p>
 * The two backends are shared by every node in a deployment. This composite is not: the {@code held} map is what lets
 * {@link #records()} fence a write without every caller down the ReAct call chain threading a fencing token through its
 * signature. Build <b>one store per session manager</b>. Two managers in one JVM — the multi-node test harnesses, which
 * simulate two nodes in-process — must construct two stores over the same two backends, otherwise each would see the
 * other's leases as its own and fencing would pass when it should fail.
 *
 * <h2>Why claim is safe without a transaction</h2>
 *
 * <p>
 * {@link #claim} wins the lease first, then touches the record. Election is atomic and fenced at the shared authority,
 * so
 * a node that loses never reaches the record at all — the record work that follows is single-threaded by construction.
 * Any failure after the lease is won returns it before propagating, so a half-finished claim does not pin the
 * session for the rest of the lease period.
 */
public final class DefaultSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(DefaultSessionStore.class);

    private final SessionLeaseStore leaseStore;
    private final SessionRecordStore repository;
    private final ConcurrentMap<SessionId, SessionLease> held = new ConcurrentHashMap<>();
    private final SessionRecordStore fencedRecords = new FencedRecords();

    /**
     * @param leaseStore
     *            the shared election backend (must not be null)
     * @param repository
     *            the shared durable record backend (must not be null)
     */
    public DefaultSessionStore(SessionLeaseStore leaseStore, SessionRecordStore repository) {
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public ClaimResult claim(SessionId sessionId, String agentRef, String holderId, Duration lease) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(agentRef, "agentRef must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        Objects.requireNonNull(lease, "lease must not be null");

        final Optional<SessionLease> won = elect(sessionId, holderId, lease);
        if (won.isEmpty()) {
            // Observed after the fact, so it may already be gone — HeldElsewhere reports that honestly as empty
            // rather than naming a holder we did not actually see.
            return new ClaimResult.HeldElsewhere(leaseStore.findHolder(sessionId).orElse(null));
        }

        final SessionLease acquired = won.get();
        try {
            // The record work below goes to the raw repository, not through records(): the lease was won atomically
            // one statement ago, so re-proving it here would buy nothing but a round trip.
            //
            // One call does all three things this used to need three for: provision the record, bind it to agentRef if
            // it is not bound yet, and report the binding that is actually there. The returned binding — never the one
            // we asked for — decides whether this is a conflict, so an already-bound session is detected without
            // ever having been written to.
            final SessionRecordView record = repository.provision(sessionId, agentRef);
            final String boundAgentRef = record.getAgentRef()
                    .orElseThrow(() -> new IllegalStateException("provision(" + sessionId.value() + ", " + agentRef
                            + ") returned an unbound record; the repository is not honouring the primitive."));
            if (!boundAgentRef.equals(agentRef)) {
                release(acquired);
                return new ClaimResult.AgentConflict(boundAgentRef, agentRef);
            }
            return new ClaimResult.Acquired(acquired, record);
        } catch (RuntimeException e) {
            release(acquired);
            throw e;
        }
    }

    @Override
    public Optional<SessionLease> acquire(SessionId sessionId, String holderId, Duration lease) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        Objects.requireNonNull(lease, "lease must not be null");

        final Optional<SessionLease> won = elect(sessionId, holderId, lease);
        if (won.isEmpty()) {
            return Optional.empty();
        }
        final SessionLease acquired = won.get();
        try {
            // Without an agentRef: this caller holds the session in order to find out which agent it belongs to,
            // so it must not be the one to answer that question.
            repository.provision(sessionId);
        } catch (RuntimeException e) {
            release(acquired);
            throw e;
        }
        return Optional.of(acquired);
    }

    /**
     * Wins the election and registers local holdership — the part {@link #claim} and {@link #acquire} share.
     *
     * <p>
     * Registration happens before either caller touches the record, because provisioning goes to the raw repository and
     * a
     * caller's very next act may be a fenced write. Both callers release on any failure that follows, which
     * un-registers.
     */
    private Optional<SessionLease> elect(SessionId sessionId, String holderId, Duration lease) {
        final Optional<SessionLease> won = leaseStore.tryAcquire(sessionId, holderId, lease);
        won.ifPresent(acquired -> held.put(sessionId, acquired));
        return won;
    }

    @Override
    public Optional<LeaseHolder> findHolder(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return leaseStore.findHolder(sessionId);
    }

    @Override
    public boolean renew(SessionLease lease, Duration duration) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(duration, "duration must not be null");

        final boolean extended = leaseStore.extend(lease, duration);
        if (!extended) {
            // Somebody else holds the session now. Forget the lease immediately so the very next fenced write
            // fails locally instead of taking a round trip to learn the same thing.
            forget(lease);
            log.debug("Lease no longer current for session {} (token={}); dropping local holdership",
                    lease.getSessionId().value(), lease.getFencingToken());
        }
        return extended;
    }

    @Override
    public void release(SessionLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        // Forget locally first: from this point a fenced write is rejected without waiting on the backend.
        forget(lease);
        leaseStore.release(lease);
    }

    @Override
    public Optional<SessionRecordView> load(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return repository.load(sessionId);
    }

    @Override
    public SessionRecordStore records() {
        return fencedRecords;
    }

    /**
     * Drops local holdership of {@code lease}, matched on the fencing token rather than on object identity so a
     * reconstructed-but-equivalent lease still works. A newer lease for the same session is left alone.
     */
    private void forget(SessionLease lease) {
        held.computeIfPresent(lease.getSessionId(),
                (id, current) -> current.getFencingToken() == lease.getFencingToken() ? null : current);
    }

    /**
     * The fenced write path returned by {@link #records()}.
     *
     * <p>
     * Every mutator re-proves holdership at the lease authority before delegating. Reads pass straight through — a read
     * cannot corrupt history, and callers such as a status projection legitimately inspect sessions they do not
     * hold.
     *
     * <p>
     * Every mutator delegates rather than reimplementing, and that is what keeps the backend's atomicity intact: each
     * write on {@link SessionRecordStore} is a partial write that must preserve the fields it does not own, and the
     * backing implementation does so with an atomic primitive. Fencing and then delegating adds holdership to that
     * guarantee; expressing any of these as a read followed by a write here would spend it.
     */
    private final class FencedRecords implements SessionRecordStore {

        @Override
        public void mergeFromSnapshot(SessionSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            requireHeld(snapshot.getSessionId(), "mergeFromSnapshot");
            repository.mergeFromSnapshot(snapshot);
        }

        @Override
        public SessionRecordView provision(SessionId sessionId, String agentRef) {
            requireHeld(sessionId, "provision");
            return repository.provision(sessionId, agentRef);
        }

        @Override
        public void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals,
                ExecutionBudget budgetOverride) {
            requireHeld(sessionId, "setTotalsAndBudgetOverride");
            repository.setTotalsAndBudgetOverride(sessionId, totals, budgetOverride);
        }

        @Override
        public int incrementCompactionFailureCount(SessionId sessionId) {
            requireHeld(sessionId, "incrementCompactionFailureCount");
            return repository.incrementCompactionFailureCount(sessionId);
        }

        @Override
        public void resetCompactionFailureCount(SessionId sessionId) {
            requireHeld(sessionId, "resetCompactionFailureCount");
            repository.resetCompactionFailureCount(sessionId);
        }

        @Override
        public void delete(SessionId sessionId) {
            requireHeld(sessionId, "delete");
            repository.delete(sessionId);
        }

        @Override
        public Optional<SessionRecordView> load(SessionId sessionId) {
            return repository.load(sessionId);
        }

        @Override
        public List<SessionId> listSessionIds() {
            return repository.listSessionIds();
        }

        @Override
        public boolean exists(SessionId sessionId) {
            return repository.exists(sessionId);
        }

        /**
         * Always rejected. There is no session to prove holdership of — the operation is defined over every record
         * at once, including ones held by other nodes. A caller that genuinely wants to wipe the store holds the raw
         * {@link SessionRecordStore} and can say so there.
         */
        @Override
        public void clear() {
            throw new UnsupportedOperationException(
                    "clear() is not available on the fenced record view: holdership cannot be proven for all "
                            + "sessions at once. Use the underlying SessionRecordStore directly.");
        }

        private void requireHeld(SessionId sessionId, String operation) {
            Objects.requireNonNull(sessionId, "sessionId must not be null");

            final SessionLease lease = held.get(sessionId);
            if (lease == null) {
                throw new SessionNotHeldException("Refusing " + operation + " for session " + sessionId.value()
                        + ": this node holds no lease for it.");
            }
            final Optional<LeaseHolder> holder = leaseStore.findHolder(sessionId);
            if (holder.isEmpty() || holder.get().getFencingToken() != lease.getFencingToken()
                    || !holder.get().getHolderId().equals(lease.getHolderId())) {
                forget(lease);
                throw new SessionNotHeldException("Refusing " + operation + " for session " + sessionId.value()
                        + ": lease (token=" + lease.getFencingToken() + ") is no longer current, observed holder is "
                        + holder.map(LeaseHolder::toString).orElse("none") + '.');
            }
        }
    }
}
