package at.aimon.session.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLeaseException;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;

/**
 * Postgres-backed {@link SessionLeaseStore} per design §4.1.
 *
 * <p>
 * Row-level lease with a separate fencing-token counter — no {@code pg_advisory_lock}. The
 * {@code conversation_lock_fence} table issues a monotonically increasing token per session; the
 * {@code conversation_lock} row is upserted with {@code ON CONFLICT DO UPDATE WHERE lease_expires_at < now()} so a
 * stale lease gets stomped while a live lease is left alone. {@code RETURNING fencing_token} after the upsert tells the
 * caller whether they won the race (token matches the freshly-reserved one) or another holder still owns the row
 * (return value differs / nothing returned).
 *
 * <p>
 * Failure semantics:
 * <ul>
 * <li>Lock held by another holder &rarr; empty {@link Optional} from {@link #tryAcquire}.
 * <li>JDBC failure &rarr; {@link SessionLeaseException}.
 * </ul>
 *
 * <p>
 * The class name predates the {@code ConversationLock} &rarr; {@code SessionLeaseStore} rename and is kept on
 * purpose: {@code PostgresSchemaFreezeTest} pins the DDL to this type, and the {@code conversation_lock} /
 * {@code conversation_lock_fence} tables it reads are already deployed.
 */
public final class PostgresSessionLeaseStore implements SessionLeaseStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresSessionLeaseStore.class);

    private static final String SQL_RESERVE_FENCE = "INSERT INTO conversation_lock_fence (conversation_id, next_token) "
            + "VALUES (?, 2) ON CONFLICT (conversation_id) DO UPDATE "
            + "SET next_token = conversation_lock_fence.next_token + 1 " + "RETURNING next_token - 1 AS issued_token";

    private static final String SQL_UPSERT_LOCK = "INSERT INTO conversation_lock "
            + "(conversation_id, holder_id, fencing_token, acquired_at, lease_expires_at) " + "VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (conversation_id) DO UPDATE SET " + "  holder_id = EXCLUDED.holder_id, "
            + "  fencing_token = EXCLUDED.fencing_token, " + "  acquired_at = EXCLUDED.acquired_at, "
            + "  lease_expires_at = EXCLUDED.lease_expires_at "
            + "  WHERE conversation_lock.lease_expires_at < EXCLUDED.acquired_at " + "RETURNING fencing_token";

    private static final String SQL_EXTEND = "UPDATE conversation_lock SET lease_expires_at = ? "
            + "WHERE conversation_id = ? AND holder_id = ? AND fencing_token = ?";

    private static final String SQL_RELEASE = "DELETE FROM conversation_lock "
            + "WHERE conversation_id = ? AND fencing_token = ?";

    // Liveness is evaluated against the caller's clock, exactly as the acquire upsert compares against
    // EXCLUDED.acquired_at rather than now(). Using the server clock here instead would let findHolder and tryAcquire
    // disagree about whether a lease has lapsed, which is precisely the disagreement fencing must not have.
    private static final String SQL_FIND_HOLDER = "SELECT holder_id, fencing_token, lease_expires_at "
            + "FROM conversation_lock WHERE conversation_id = ? AND lease_expires_at > ?";

    private final DataSource dataSource;
    private final Clock clock;

    public PostgresSessionLeaseStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public PostgresSessionLeaseStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<SessionLease> tryAcquire(SessionId id, String holderId, Duration lease) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                final long token = reserveFencingToken(c, id);
                final Instant acquiredAt = clock.instant();
                final Instant expiresAt = acquiredAt.plus(lease);
                final Optional<Long> stamped = upsertLockRow(c, id, holderId, token, acquiredAt, expiresAt);
                c.commit();
                if (stamped.isEmpty() || stamped.get() != token) {
                    return Optional.empty();
                }
                return Optional.of(SessionLease.builder().sessionId(id).holderId(holderId).fencingToken(token)
                        .acquiredAt(acquiredAt).lease(lease).build());
            } catch (SQLException e) {
                safeRollback(c);
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new SessionLeaseException("Postgres error during tryAcquire for " + id, e);
        }
    }

    @Override
    public Optional<LeaseHolder> findHolder(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_FIND_HOLDER)) {
            ps.setString(1, id.value());
            ps.setTimestamp(2, Timestamp.from(clock.instant()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(LeaseHolder.builder().holderId(rs.getString("holder_id"))
                        .fencingToken(rs.getLong("fencing_token"))
                        .expiresAt(rs.getTimestamp("lease_expires_at").toInstant()).build());
            }
        } catch (SQLException e) {
            throw new SessionLeaseException("Postgres error during findHolder for " + id, e);
        }
    }

    @Override
    public boolean extend(SessionLease lease, Duration duration) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive: " + duration);
        }
        final Instant expiresAt = clock.instant().plus(duration);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_EXTEND)) {
            ps.setTimestamp(1, Timestamp.from(expiresAt));
            ps.setString(2, lease.getSessionId().value());
            ps.setString(3, lease.getHolderId());
            ps.setLong(4, lease.getFencingToken());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new SessionLeaseException("Postgres error during extend for " + lease.getSessionId(), e);
        }
    }

    @Override
    public void release(SessionLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_RELEASE)) {
            ps.setString(1, lease.getSessionId().value());
            ps.setLong(2, lease.getFencingToken());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Best-effort release for {} failed: {}", lease.getSessionId(), e.toString());
        }
    }

    private long reserveFencingToken(Connection c, SessionId id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_RESERVE_FENCE)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("conversation_lock_fence upsert returned no row for " + id);
                }
                return rs.getLong("issued_token");
            }
        }
    }

    private Optional<Long> upsertLockRow(Connection c, SessionId id, String holderId, long token, Instant acquiredAt,
            Instant expiresAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_UPSERT_LOCK)) {
            ps.setString(1, id.value());
            ps.setString(2, holderId);
            ps.setLong(3, token);
            ps.setTimestamp(4, Timestamp.from(acquiredAt));
            ps.setTimestamp(5, Timestamp.from(expiresAt));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(rs.getLong("fencing_token"));
            }
        }
    }

    private static void safeRollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            /* best-effort rollback */
        }
    }
}
