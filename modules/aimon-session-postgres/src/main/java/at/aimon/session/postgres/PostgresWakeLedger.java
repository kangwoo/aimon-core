package at.aimon.session.postgres;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.wake.WakeLedger;
import at.aimon.core.agent.session.wake.WakeState;

/**
 * Postgres-backed {@link WakeLedger}: the idle-session waker's caps shared by every node (idle-wake design §4.3).
 *
 * <p>
 * One row per session in {@code wake_ledger} ({@code db/postgres/V4__wake_ledger.sql}). {@link #update} runs in one
 * transaction: it inserts the empty state if the row is missing ({@code ON CONFLICT DO NOTHING}), locks the row with
 * {@code SELECT ... FOR UPDATE}, applies the change in Java and writes the result — so two nodes updating one session
 * serialize on the row lock and neither loses the other's count. Wake times are stored as epoch milliseconds.
 *
 * <p>
 * All backend errors surface as {@link IllegalStateException} — the JDBC {@link SQLException} never crosses the module
 * boundary.
 */
public final class PostgresWakeLedger implements WakeLedger {

    private static final Logger log = LoggerFactory.getLogger(PostgresWakeLedger.class);

    private static final String COLUMNS = "recent_wakes, consecutive, turn_count_after_wake, outstanding";

    private static final String SQL_FIND = "SELECT " + COLUMNS + " FROM wake_ledger WHERE session_id = ?";

    private static final String SQL_ENSURE = "INSERT INTO wake_ledger (session_id, " + COLUMNS + ", updated_at) "
            + "VALUES (?, '{}', 0, NULL, 0, ?) ON CONFLICT (session_id) DO NOTHING";

    private static final String SQL_LOCK = "SELECT " + COLUMNS + " FROM wake_ledger WHERE session_id = ? FOR UPDATE";

    private static final String SQL_WRITE = "UPDATE wake_ledger SET recent_wakes = ?, consecutive = ?, "
            + "turn_count_after_wake = ?, outstanding = ?, updated_at = ? WHERE session_id = ?";

    private static final String SQL_REMOVE = "DELETE FROM wake_ledger WHERE session_id = ?";

    private final DataSource dataSource;
    private final Clock clock;

    /**
     * Creates a ledger with the system UTC clock.
     *
     * @param dataSource
     *            the JDBC data source (must not be null; owned by the caller)
     */
    public PostgresWakeLedger(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    /**
     * Creates a ledger with an explicit clock, which stamps {@code updated_at}.
     *
     * @param dataSource
     *            the JDBC data source (must not be null; owned by the caller)
     * @param clock
     *            the clock (must not be null)
     */
    public PostgresWakeLedger(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public WakeState get(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_FIND)) {
            ps.setString(1, sessionId.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : WakeState.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres error reading the wake ledger of " + sessionId, e);
        }
    }

    @Override
    public WakeState update(SessionId sessionId, UnaryOperator<WakeState> change) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(change, "change must not be null");
        try (Connection c = dataSource.getConnection()) {
            final boolean autoCommit = c.getAutoCommit();
            final int isolation = c.getTransactionIsolation();
            // The row lock is what serializes two nodes, and that holds under READ COMMITTED: a stricter level would
            // turn the wait into a serialization failure, whatever the pool is configured with.
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                final Timestamp now = Timestamp.from(clock.instant());
                try (PreparedStatement ps = c.prepareStatement(SQL_ENSURE)) {
                    ps.setString(1, sessionId.value());
                    ps.setTimestamp(2, now);
                    ps.executeUpdate();
                }
                final WakeState current;
                try (PreparedStatement ps = c.prepareStatement(SQL_LOCK)) {
                    ps.setString(1, sessionId.value());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException(
                                    "wake_ledger row of " + sessionId + " was removed while being updated");
                        }
                        current = read(rs);
                    }
                }
                final WakeState next = Objects.requireNonNull(change.apply(current), "change returned null");
                try (PreparedStatement ps = c.prepareStatement(SQL_WRITE)) {
                    ps.setArray(1, millisArray(c, next.getRecentWakes()));
                    ps.setInt(2, next.getConsecutive());
                    if (next.getTurnCountAfterWake().isPresent()) {
                        ps.setInt(3, next.getTurnCountAfterWake().getAsInt());
                    } else {
                        ps.setNull(3, Types.INTEGER);
                    }
                    ps.setInt(4, next.getOutstanding());
                    ps.setTimestamp(5, now);
                    ps.setString(6, sessionId.value());
                    ps.executeUpdate();
                }
                c.commit();
                return next;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(autoCommit);
                    c.setTransactionIsolation(isolation);
                } catch (SQLException restore) {
                    log.debug("Could not restore the connection after a wake ledger update: {}", restore.toString());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres error updating the wake ledger of " + sessionId, e);
        }
    }

    @Override
    public void remove(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_REMOVE)) {
            ps.setString(1, sessionId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres error removing the wake ledger of " + sessionId, e);
        }
    }

    private static WakeState read(ResultSet rs) throws SQLException {
        final List<Instant> recent = new ArrayList<>();
        final Array array = rs.getArray("recent_wakes");
        if (array != null) {
            for (Object millis : (Object[]) array.getArray()) {
                recent.add(Instant.ofEpochMilli(((Number) millis).longValue()));
            }
        }
        final int turnCount = rs.getInt("turn_count_after_wake");
        final Integer turnCountAfterWake = rs.wasNull() ? null : turnCount;
        return WakeState.of(recent, rs.getInt("consecutive"), turnCountAfterWake, rs.getInt("outstanding"));
    }

    private static Array millisArray(Connection c, List<Instant> instants) throws SQLException {
        final Long[] millis = new Long[instants.size()];
        for (int i = 0; i < millis.length; i++) {
            millis[i] = instants.get(i).toEpochMilli();
        }
        return c.createArrayOf("bigint", millis);
    }
}
