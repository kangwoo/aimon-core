package at.aimon.session.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionRecordStoreException;
import at.aimon.core.agent.session.store.SessionRecordCodec;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.store.StoredSessionRecord;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * Postgres-backed {@link SessionRecordStore}: the transcript half of the session, which until now only the in-memory
 * store held.
 *
 * <p>
 * Everything else about a session was already distributed — the lease, the signal bus, the inbox, the idempotency
 * ledger — so a fleet could hand a session from one node to another and lose the conversation while keeping the
 * bookkeeping about it. This is the store that closes that gap.
 *
 * <h2>Row layout</h2>
 *
 * <p>
 * One row per session in {@code session_record}: {@code transcript} (text), {@code agent_ref} (text),
 * {@code compaction_failure_count} (integer, {@code NOT NULL DEFAULT 0}), {@code session_totals} and
 * {@code budget_override} (jsonb), {@code updated_at} (timestamptz).
 *
 * <p>
 * <b>NULL is not a separate state.</b> Every nullable column decodes to the same default the in-memory store reports
 * for a record that has one — no transcript, no totals, no override — so the first turn of a session, which runs
 * against a row the claim path provisioned and nothing has written to yet, behaves identically on both.
 *
 * <p>
 * {@code transcript} is {@code text} rather than {@code jsonb}, and that is forced rather than chosen: a tool use's
 * input is an arbitrary model-supplied map, so a string in it may contain the NUL character (U+0000), which
 * {@code jsonb} rejects inside a string value while {@code text} stores the escaped form without complaint. The side
 * fields have fixed, numeric shapes and no such exposure, so they go in as {@code jsonb} and stay queryable from
 * {@code psql}. {@code SessionRecordCodec} owns both encodings for all three backends.
 *
 * <h2>Every write is partial, and each is one statement</h2>
 *
 * <p>
 * {@link SessionRecordStore} has no full-record write because four writers own four fields, and the {@code @implSpec}
 * on each method asks for one atomic operation rather than a read-modify-write. No transaction spans two statements
 * here; each maps to a single one:
 *
 * <ul>
 * <li>{@link #provision(SessionId, String) provision} — {@code INSERT … ON CONFLICT DO UPDATE SET agent_ref =
 * COALESCE(session_record.agent_ref, EXCLUDED.agent_ref) … RETURNING}, so "create if missing", "bind if unbound" and
 * "tell me who owns this" settle in one round trip and an existing binding is never overwritten
 * <li>{@link #mergeFromSnapshot(SessionSnapshot) mergeFromSnapshot} — an upsert naming the transcript alone; the side
 * columns are not in the statement, so a concurrent writer of any of them cannot lose its write here
 * <li>{@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget) setTotalsAndBudgetOverride} — one
 * plain {@code UPDATE} carrying both columns, so the pair moves together and a missing row stays missing
 * <li>{@link #incrementCompactionFailureCount(SessionId) incrementCompactionFailureCount} —
 * {@code SET n = n + 1 … RETURNING n}, so two nodes incrementing concurrently get two different numbers
 * <li>{@link #resetCompactionFailureCount(SessionId) resetCompactionFailureCount} — an {@code UPDATE} of that column
 * alone
 * </ul>
 *
 * <p>
 * {@code updated_at} is stamped with the server's {@code now()} rather than an application clock. Nothing reads it — it
 * exists for operator triage — but a per-node clock would make it lie about ordering in exactly the situation an
 * operator consults it.
 *
 * <h2>Fencing</h2>
 *
 * <p>
 * None here, deliberately. This class implements the plain SPI; writes are fenced against the node's lease when the
 * store is reached through {@code SessionStore.records()}, which is where the lease lives. An assembler that hands this
 * store around directly gets last-write-wins between nodes on the same session — the same bargain the in-memory store
 * offers within one JVM.
 */
public final class PostgresSessionRecordStore implements SessionRecordStore {

    private static final String COLUMNS = "transcript, agent_ref, compaction_failure_count, "
            + "session_totals, budget_override";

    private static final String SQL_MERGE = "INSERT INTO session_record (session_id, transcript, updated_at) "
            + "VALUES (?, ?, now()) " + "ON CONFLICT (session_id) DO UPDATE SET "
            + "  transcript = EXCLUDED.transcript, updated_at = now()";

    // DO UPDATE rather than DO NOTHING, so RETURNING always yields the row: DO NOTHING returns nothing on conflict and
    // would force a second, non-atomic SELECT. COALESCE is the binding rule -- an existing agent_ref wins, so a node
    // that wanted a different agent learns so from the returned row instead of having already stolen the session.
    private static final String SQL_PROVISION = "INSERT INTO session_record (session_id, agent_ref, updated_at) "
            + "VALUES (?, ?, now()) " + "ON CONFLICT (session_id) DO UPDATE SET "
            + "  agent_ref = COALESCE(session_record.agent_ref, EXCLUDED.agent_ref), updated_at = now() " + "RETURNING "
            + COLUMNS;

    // No upsert: this write follows provisioning, and a row that is not there yet is a no-op, not a create. A null
    // budget_override does not mean "leave it alone" -- it clears one that was set, so the next open falls back to the
    // opener's default.
    private static final String SQL_SET_TOTALS = "UPDATE session_record SET session_totals = ?::jsonb, "
            + "budget_override = ?::jsonb, updated_at = now() WHERE session_id = ?";

    private static final String SQL_INCREMENT = "UPDATE session_record SET "
            + "compaction_failure_count = compaction_failure_count + 1, updated_at = now() "
            + "WHERE session_id = ? RETURNING compaction_failure_count";

    private static final String SQL_RESET = "UPDATE session_record SET compaction_failure_count = 0, "
            + "updated_at = now() WHERE session_id = ?";

    private static final String SQL_LOAD = "SELECT " + COLUMNS + " FROM session_record WHERE session_id = ?";

    private static final String SQL_DELETE = "DELETE FROM session_record WHERE session_id = ?";

    private static final String SQL_LIST = "SELECT session_id FROM session_record";

    private static final String SQL_EXISTS = "SELECT 1 FROM session_record WHERE session_id = ?";

    private static final String SQL_CLEAR = "DELETE FROM session_record";

    private final DataSource dataSource;

    /**
     * Creates a store over {@code session_record}.
     *
     * @param dataSource
     *            the pooled data source (must not be null)
     */
    public PostgresSessionRecordStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void mergeFromSnapshot(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        final SessionId id = snapshot.getSessionId();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_MERGE)) {
            ps.setString(1, id.value());
            ps.setString(2, SessionRecordCodec.encodeTranscript(snapshot));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("mergeFromSnapshot", id, e);
        }
    }

    @Override
    public SessionRecordView provision(SessionId sessionId, String agentRef) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_PROVISION)) {
            ps.setString(1, sessionId.value());
            ps.setString(2, agentRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return toRecord(sessionId, rs);
                }
            }
        } catch (SQLException e) {
            throw failure("provision", sessionId, e);
        }
        // Unreachable in practice: DO UPDATE fires on conflict, so the statement always returns its row. Reporting the
        // defaults rather than null keeps the SPI's "never null" promise if a future rewrite makes it reachable.
        return StoredSessionRecord.empty(sessionId, agentRef);
    }

    @Override
    public void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals, ExecutionBudget budgetOverride) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(totals, "totals must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_SET_TOTALS)) {
            ps.setString(1, SessionRecordCodec.encodeTotals(totals));
            ps.setString(2, SessionRecordCodec.encodeBudgetOverride(budgetOverride));
            ps.setString(3, sessionId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("setTotalsAndBudgetOverride", sessionId, e);
        }
    }

    @Override
    public int incrementCompactionFailureCount(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_INCREMENT)) {
            ps.setString(1, sessionId.value());
            try (ResultSet rs = ps.executeQuery()) {
                // RETURNING inside the updating statement, not a read after it: a read-after-write would let another
                // node's increment land in between and hand both callers the same number -- the one miscount a circuit
                // breaker must not make. No row means no record, which reports as "nothing has been counted".
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw failure("incrementCompactionFailureCount", sessionId, e);
        }
    }

    @Override
    public void resetCompactionFailureCount(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_RESET)) {
            ps.setString(1, sessionId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("resetCompactionFailureCount", sessionId, e);
        }
    }

    @Override
    public Optional<SessionRecordView> load(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_LOAD)) {
            ps.setString(1, sessionId.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toRecord(sessionId, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("load", sessionId, e);
        }
    }

    @Override
    public void delete(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_DELETE)) {
            ps.setString(1, sessionId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("delete", sessionId, e);
        }
    }

    @Override
    public List<SessionId> listSessionIds() {
        final List<SessionId> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_LIST);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(SessionId.of(rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new SessionRecordStoreException("Postgres error during listSessionIds", e);
        }
        return ids;
    }

    @Override
    public boolean exists(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_EXISTS)) {
            ps.setString(1, sessionId.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw failure("exists", sessionId, e);
        }
    }

    @Override
    public void clear() {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_CLEAR)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SessionRecordStoreException("Postgres error during clear", e);
        }
    }

    private StoredSessionRecord toRecord(SessionId sessionId, ResultSet rs) throws SQLException {
        final String transcript = rs.getString("transcript");
        final String agentRef = rs.getString("agent_ref");
        final int failures = rs.getInt("compaction_failure_count");
        final String totals = rs.getString("session_totals");
        final String override = rs.getString("budget_override");
        try {
            return StoredSessionRecord.builder(sessionId)
                    .transcript(SessionRecordCodec.decodeTranscript(sessionId, transcript)).agentRef(agentRef)
                    .compactionFailureCount(failures).sessionTotals(SessionRecordCodec.decodeTotals(totals))
                    .budgetOverride(SessionRecordCodec.decodeBudgetOverride(override)).build();
        } catch (RuntimeException e) {
            // A row that will not decode is an infrastructure failure from the caller's side, not a missing one:
            // reporting it as absent would let a session silently resume with an empty history.
            throw new SessionRecordStoreException("Failed to decode stored session record for " + sessionId, e);
        }
    }

    private static SessionRecordStoreException failure(String operation, SessionId sessionId, Exception cause) {
        return new SessionRecordStoreException("Postgres error during " + operation + " for " + sessionId, cause);
    }
}
