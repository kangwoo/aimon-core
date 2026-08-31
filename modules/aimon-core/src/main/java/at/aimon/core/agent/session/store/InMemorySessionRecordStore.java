package at.aimon.core.agent.session.store;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.SessionTranscript;

/**
 * In-memory implementation of SessionRecordStore.
 *
 * <p>
 * Stores session records in memory using a thread-safe concurrent map. Data is lost when the application terminates.
 *
 * <p>
 * Thread-safe for concurrent access.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
 *
 *     // Put whole records in place — a test/dev shortcut, not part of the SPI
 *     repository.save(new SessionRecord(SessionId.of("user-1"), null, List.of(Message.user("Hello"))));
 *     repository.save(new SessionRecord(SessionId.of("user-2"), null, List.of(Message.user("Hi"))));
 *
 *     // List every session on record
 *     List<SessionId> ids = repository.listSessionIds();
 *     System.out.println("Total sessions: " + ids.size());
 * }
 * </pre>
 */
public class InMemorySessionRecordStore implements SessionRecordStore {

    private final Map<SessionId, SessionRecord> storage;

    /** Creates a new empty in-memory session record store. */
    public InMemorySessionRecordStore() {
        this.storage = new ConcurrentHashMap<>();
    }

    /**
     * Stores {@code record} whole, replacing any record under the same id.
     *
     * <p>
     * <b>Not part of {@link SessionRecordStore}</b>, deliberately. A full-record write cannot be offered to
     * production callers because no writer there owns every field. It survives on this class alone, where the caller is
     * a test arranging a starting state or single-threaded dev code, and "replace everything" is what it actually
     * means. Production writes go through {@link #mergeFromSnapshot(SessionSnapshot)},
     * {@link #provision(SessionId, String)} and
     * {@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget)}, each of which preserves
     * the fields it does not own.
     *
     * @param record
     *            the session record to store (must not be null); its id selects the slot
     * @throws NullPointerException
     *             if {@code record} is null
     */
    public void save(SessionRecord record) {
        Objects.requireNonNull(record, "Record cannot be null");

        SessionId sessionId = record.getId();
        // Create a deep copy to prevent external modification
        SessionRecord copy = copySessionRecord(record);
        storage.put(sessionId, copy);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Uses {@link java.util.concurrent.ConcurrentHashMap#compute} so the read + merge + write happens under the bucket
     * lock and is mutually exclusive against any other write targeting the same {@link SessionId}. This is the
     * atomicity the interface requires and cannot default: a load-then-store version would lose the side fields written
     * in between by {@link #provision(SessionId, String)} or
     * {@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget)}.
     */
    @Override
    public void mergeFromSnapshot(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        storage.compute(snapshot.getSessionId(), (id, existing) -> {
            final SessionRecord merged = SessionRecord.fromSnapshot(snapshot);
            if (existing != null) {
                merged.setCompactionFailureCount(existing.getCompactionFailureCount());
                if (existing.getAgentRef().isPresent()) {
                    merged.setAgentRef(existing.getAgentRef().get());
                }
                merged.setSessionTotals(existing.getSessionTotals());
                if (existing.getBudgetOverride().isPresent()) {
                    merged.setBudgetOverride(existing.getBudgetOverride().get());
                }
            }
            return copySessionRecord(merged);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Uses {@link ConcurrentHashMap#compute} so "create if missing" and "bind if unbound" happen under the bucket lock
     * as one step: two callers provisioning the same id concurrently cannot both see it as absent, and a concurrent
     * {@link #mergeFromSnapshot(SessionSnapshot)} can neither drop the new binding nor have its messages
     * clobbered.
     *
     * <p>
     * When there is nothing to change — the record exists and either already carries a binding or none was asked for —
     * the stored instance is returned unchanged rather than rewritten with an equal copy.
     */
    @Override
    public SessionRecordView provision(SessionId sessionId, String agentRef) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        final SessionRecord stored = storage.compute(sessionId, (id, existing) -> {
            if (existing == null) {
                final SessionRecord created = new SessionRecord(id);
                if (agentRef != null) {
                    created.setAgentRef(agentRef);
                }
                return created;
            }
            if (agentRef == null || existing.getAgentRef().isPresent()) {
                return existing;
            }
            final SessionRecord bound = copySessionRecord(existing);
            bound.setAgentRef(agentRef);
            return bound;
        });
        // Copy on the way out for the same reason load() does: callers must not hold the stored instance.
        return copySessionRecord(stored);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Uses {@link ConcurrentHashMap#computeIfPresent} so the pair write is mutually exclusive against any other write
     * to the same {@link SessionId}. The message body is read from the in-storage record (not from a stale view),
     * so a concurrent merge cannot be overwritten and the new values cannot be clobbered.
     */
    @Override
    public void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals, ExecutionBudget budgetOverride) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(totals, "totals cannot be null");
        storage.computeIfPresent(sessionId, (id, existing) -> {
            final SessionRecord mutable = copySessionRecord(existing);
            mutable.setSessionTotals(totals);
            mutable.setBudgetOverride(budgetOverride);
            return mutable;
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The increment reads its base from the in-storage record inside
     * {@link ConcurrentHashMap#computeIfPresent}, never from a view the caller loaded earlier, so two threads
     * incrementing at once produce two, not one. The value returned is the one this call installed —
     * {@code computeIfPresent} hands back the new mapping — so no separate read can slip between the write and the
     * answer.
     */
    @Override
    public int incrementCompactionFailureCount(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        final SessionRecord updated = storage.computeIfPresent(sessionId, (id, existing) -> {
            final SessionRecord mutable = copySessionRecord(existing);
            mutable.setCompactionFailureCount(existing.getCompactionFailureCount() + 1);
            return mutable;
        });
        return updated == null ? 0 : updated.getCompactionFailureCount();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Returns the stored record unchanged when the counter is already zero. That is not just an allocation saving: a
     * reset is issued after every successful compaction, so the common case is a no-change call, and handing back the
     * same instance keeps it from publishing a fresh copy that a concurrent
     * {@link #mergeFromSnapshot(at.aimon.core.agent.session.transcript.SessionSnapshot)} would have to be ordered
     * against for no reason.
     */
    @Override
    public void resetCompactionFailureCount(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        storage.computeIfPresent(sessionId, (id, existing) -> {
            if (existing.getCompactionFailureCount() == 0) {
                return existing;
            }
            final SessionRecord mutable = copySessionRecord(existing);
            mutable.setCompactionFailureCount(0);
            return mutable;
        });
    }

    @Override
    public Optional<SessionRecordView> load(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "Session id cannot be null");

        SessionRecord record = storage.get(sessionId);
        if (record == null) {
            return Optional.empty();
        }

        // Return a deep copy to prevent external modification
        return Optional.of(copySessionRecord(record));
    }

    @Override
    public void delete(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "Session id cannot be null");
        storage.remove(sessionId);
    }

    @Override
    public List<SessionId> listSessionIds() {
        return List.copyOf(storage.keySet());
    }

    @Override
    public boolean exists(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "Session id cannot be null");
        return storage.containsKey(sessionId);
    }

    @Override
    public void clear() {
        storage.clear();
    }

    /**
     * Gets the number of session records stored here.
     *
     * @return The number of session records
     */
    public int size() {
        return storage.size();
    }

    /**
     * Creates an independent copy of a session record.
     *
     * <p>
     * This ensures that external modifications to the record do not affect the stored version, and vice versa.
     * Preserves all persisted state including {@code compactionFailureCount}, {@code agentRef},
     * {@code sessionTotals}, and {@code budgetOverride} so that side-field writers (e.g.
     * {@link #provision(SessionId, String)} and
     * {@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget)}) see a stable view
     * across load/save cycles.
     *
     * <p>
     * Independence comes from {@link SessionTranscript} being immutable, not from duplicating the message list:
     * {@link SessionRecord#copyOf(SessionRecordView)} hands the transcript over by reference, and appends on either
     * side swap that side's reference. Every entry point on this repository funnels through here, so an O(n) list
     * copy would otherwise be paid on every {@code save}, {@code load}, and side-field update.
     *
     * @param source
     *            The source record to copy
     * @return A new SessionRecord with the same ID, transcript, and all preserved side fields
     */
    private SessionRecord copySessionRecord(SessionRecord source) {
        return SessionRecord.copyOf(source);
    }

    @Override
    public String toString() {
        return "InMemorySessionRecordStore{" + "size=" + storage.size() + ", ids=" + storage.keySet() + "}";
    }
}
