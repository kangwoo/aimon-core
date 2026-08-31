package at.aimon.session.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.IdempotencyStoreException;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.PutResult;
import at.aimon.session.postgres.internal.IdempotencyEntryRowCodec;

/**
 * Postgres-backed {@link IdempotencyStore} per design §4.4.
 *
 * <p>
 * Each idempotency key maps to one row in {@code idempotency_entry}. {@code putIfAbsent} uses
 * {@code INSERT ... ON CONFLICT DO UPDATE} with a predicate that reclaims a row whose TTL has lapsed and spares every
 * live one, so a lapsed entry reads as absent even though nothing in Postgres removed it; the four atomic transitions
 * ({@code markDone}, {@code touch}, {@code compareAndReset}, {@code findStaleInFlight}) are single-statement SQL
 * guarded by composite {@code WHERE key = ? AND holder_id = ? AND status = 'IN_FLIGHT'} predicates.
 *
 * <p>
 * {@code result_blob} is stored as JSONB; the rest of the entry lives in its own typed columns so the sweeper indexes
 * stay efficient.
 */
public final class PostgresIdempotencyStore implements IdempotencyStore {

    /** Primary TTL applied when an entry transitions to {@link IdempotencyEntry.Status#DONE}. */
    public static final Duration DEFAULT_DONE_TTL = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(PostgresIdempotencyStore.class);

    // DO UPDATE ... WHERE expires_at <= now, not DO NOTHING: an entry whose TTL lapsed must behave as if it were never
    // there (design §9.2 — "secondary TTL 만료 → entry는 마치 없는 것처럼 동작"), which is what Mongo's TTL index and
    // Redis' PX give for free and what find() below already reports. Postgres expires nothing on its own, so the lapsed
    // row is still physically present and DO NOTHING would answer "already in flight" for a turn that died hours ago —
    // poisoning that idempotency key until the sweeper happens to run. The predicate is re-evaluated against the latest
    // row version under the row lock, so two acquirers reclaiming the same lapsed row still produce exactly one winner:
    // the loser sees the winner's fresh expires_at, updates nothing, and falls through to the read below.
    private static final String SQL_INSERT_IF_ABSENT = "INSERT INTO idempotency_entry "
            + "(key, conversation_id, input_hash, status, holder_id, result_blob, "
            + " created_at, last_touched_at, expires_at) " + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) "
            + "ON CONFLICT (key) DO UPDATE SET "
            + "conversation_id = excluded.conversation_id, input_hash = excluded.input_hash, "
            + "status = excluded.status, holder_id = excluded.holder_id, result_blob = excluded.result_blob, "
            + "created_at = excluded.created_at, last_touched_at = excluded.last_touched_at, "
            + "expires_at = excluded.expires_at " + "WHERE idempotency_entry.expires_at <= ?";

    private static final String SQL_FIND_LIVE = "SELECT key, conversation_id, input_hash, status, holder_id, "
            + "result_blob::text, created_at, last_touched_at, expires_at " + "FROM idempotency_entry "
            + "WHERE key = ? AND expires_at > ?";

    private static final String SQL_FIND_RAW = "SELECT key, conversation_id, input_hash, status, holder_id, "
            + "result_blob::text, created_at, last_touched_at, expires_at " + "FROM idempotency_entry WHERE key = ?";

    private static final String SQL_MARK_DONE = "UPDATE idempotency_entry SET "
            + "status = 'DONE', result_blob = ?::jsonb, holder_id = NULL, " + "last_touched_at = ?, expires_at = ? "
            + "WHERE key = ?";

    // touch takes no duration, so the span the row already carries (expires_at - last_touched_at, both pre-update
    // values on the right-hand side) IS the secondary TTL its caller configured; re-arming that span from now is how
    // the in-memory and Redis backends implement "refresh the secondary TTL". A literal interval here instead silently
    // clamps every deployment to the design's nominal 30s — including the ones the deployment guide tells to raise
    // idempotencySecondaryTtl to >= 2 x lockLease, whose entries would then expire mid-turn under a healthy holder.
    private static final String SQL_TOUCH = "UPDATE idempotency_entry SET last_touched_at = ?, "
            + "expires_at = ?::timestamptz + (expires_at - last_touched_at) "
            + "WHERE key = ? AND holder_id = ? AND status = 'IN_FLIGHT'";

    private static final String SQL_COMPARE_AND_RESET = "DELETE FROM idempotency_entry "
            + "WHERE key = ? AND holder_id = ? AND status = 'IN_FLIGHT'";

    // holder_id IS NULL is what SQL_COMPARE_AND_RESET's holder_id = ? can never match: the reservation left behind by
    // SQL_RELEASE_HOLDER, whose turn has since failed for good.
    private static final String SQL_DISCARD_RESERVATION = "DELETE FROM idempotency_entry "
            + "WHERE key = ? AND holder_id IS NULL AND status = 'IN_FLIGHT'";

    private static final String SQL_RELEASE_HOLDER = "UPDATE idempotency_entry SET "
            + "holder_id = NULL, last_touched_at = ?, expires_at = ? "
            + "WHERE key = ? AND holder_id = ? AND status = 'IN_FLIGHT'";

    // holder_id IS NOT NULL excludes reserved-but-unclaimed entries: nobody is executing them, so nobody touches them,
    // and they are not evidence of holder loss. Filtering here also stops them from consuming the LIMIT.
    private static final String SQL_FIND_STALE = "SELECT key, conversation_id, input_hash, status, holder_id, "
            + "result_blob::text, created_at, last_touched_at, expires_at " + "FROM idempotency_entry "
            + "WHERE status = 'IN_FLIGHT' AND holder_id IS NOT NULL AND last_touched_at < ? "
            + "ORDER BY last_touched_at LIMIT 256 FOR UPDATE SKIP LOCKED";

    private static final String SQL_DELETE_EXPIRED = "DELETE FROM idempotency_entry WHERE expires_at < ?";

    private final DataSource dataSource;
    private final IdempotencyEntryRowCodec codec;
    private final Duration doneTtl;
    private final Clock clock;

    public PostgresIdempotencyStore(DataSource dataSource) {
        this(dataSource, defaultMapper(), DEFAULT_DONE_TTL, Clock.systemUTC());
    }

    public PostgresIdempotencyStore(DataSource dataSource, ObjectMapper mapper, Duration doneTtl, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.codec = new IdempotencyEntryRowCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
        this.doneTtl = Objects.requireNonNull(doneTtl, "doneTtl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public PutResult putIfAbsent(String key, IdempotencyEntry entry, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        final Instant now = clock.instant();
        final Instant expiresAt = now.plus(ttl);
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_IF_ABSENT)) {
                ps.setString(1, key);
                ps.setString(2, entry.getSessionId().value());
                ps.setString(3, entry.getInputHash());
                ps.setString(4, entry.getStatus().name());
                ps.setString(5, entry.getHolderId().orElse(null));
                ps.setString(6, entry.getResult().map(codec::encodeResult).orElse(null));
                ps.setTimestamp(7, Timestamp.from(entry.getCreatedAt()));
                ps.setTimestamp(8, Timestamp.from(entry.getLastTouchedAt()));
                ps.setTimestamp(9, Timestamp.from(expiresAt));
                ps.setTimestamp(10, Timestamp.from(now));
                final int updated = ps.executeUpdate();
                if (updated == 1) {
                    // Either the insert landed or it reclaimed a lapsed row. Both mean this caller now owns the key.
                    return PutResult.inserted();
                }
            }
            // Fall through: a live row already owns the key — the takeover predicate spares exactly those. Read it raw
            // rather than live-only: it was unexpired a statement ago, and a row that a concurrent compareAndReset
            // deleted in that gap leaves nothing to report as existing (same fallback as the Redis backend).
            final Optional<IdempotencyEntry> existing = loadByKey(c, key, false, now);
            return existing.<PutResult>map(PutResult::existing).orElseGet(PutResult::inserted);
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Postgres error during putIfAbsent for key " + key, e);
        }
    }

    @Override
    public void markDone(String key, AgentExecutionResult result) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(result, "result must not be null");
        final Instant now = clock.instant();
        final Instant expiresAt = now.plus(doneTtl);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_MARK_DONE)) {
            ps.setString(1, codec.encodeResult(result));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            ps.setString(4, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Postgres error during markDone for key " + key, e);
        }
    }

    @Override
    public Optional<IdempotencyEntry> find(String key) {
        Objects.requireNonNull(key, "key must not be null");
        final Instant now = clock.instant();
        try (Connection c = dataSource.getConnection()) {
            return loadByKey(c, key, true, now);
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Postgres error during find for key " + key, e);
        }
    }

    @Override
    public boolean touch(String key, String holderId) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        final Instant now = clock.instant();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_TOUCH)) {
            // Same instant twice: the new lastTouchedAt, and the base the row's own TTL span slides forward from.
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setString(3, key);
            ps.setString(4, holderId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Postgres error during touch for key " + key, e);
        }
    }

    @Override
    public boolean releaseHolder(String key, String expectedHolderId, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(expectedHolderId, "expectedHolderId must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        final Instant now = clock.instant();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_RELEASE_HOLDER)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setTimestamp(2, Timestamp.from(now.plus(ttl)));
            ps.setString(3, key);
            ps.setString(4, expectedHolderId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Postgres error during releaseHolder for key " + key, e);
        }
    }

    @Override
    public boolean discardReservation(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_DISCARD_RESERVATION)) {
            ps.setString(1, key);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Postgres error during discardReservation for key " + key, e);
        }
    }

    @Override
    public boolean compareAndReset(String key, String expectedHolderId) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(expectedHolderId, "expectedHolderId must not be null");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_COMPARE_AND_RESET)) {
            ps.setString(1, key);
            ps.setString(2, expectedHolderId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Postgres error during compareAndReset for key " + key, e);
        }
    }

    @Override
    public List<IdempotencyEntry> findStaleInFlight(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        final List<IdempotencyEntry> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            // FOR UPDATE SKIP LOCKED requires an explicit transaction.
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(SQL_FIND_STALE)) {
                ps.setTimestamp(1, Timestamp.from(cutoff));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(buildEntry(rs));
                    }
                }
                c.commit();
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Postgres error during findStaleInFlight", e);
        }
        return out;
    }

    /**
     * Reaps DONE rows whose primary TTL expired. Called by the manager's scheduled cleanup (design §4.4).
     *
     * @param now
     *            wall clock against which {@code expires_at} is compared
     * @return number of rows deleted
     */
    public int sweepExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_DELETE_EXPIRED)) {
            ps.setTimestamp(1, Timestamp.from(now));
            final int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.debug("sweepExpired removed {} idempotency rows", deleted);
            }
            return deleted;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Postgres error during sweepExpired", e);
        }
    }

    private Optional<IdempotencyEntry> loadByKey(Connection c, String key, boolean liveOnly, Instant now)
            throws SQLException {
        final String sql = liveOnly ? SQL_FIND_LIVE : SQL_FIND_RAW;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            if (liveOnly) {
                ps.setTimestamp(2, Timestamp.from(now));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(buildEntry(rs));
            }
        }
    }

    private IdempotencyEntry buildEntry(ResultSet rs) throws SQLException {
        final IdempotencyEntry.Builder b = IdempotencyEntry.builder().key(rs.getString("key"))
                .sessionId(SessionId.of(rs.getString("conversation_id"))).inputHash(rs.getString("input_hash"))
                .status(IdempotencyEntry.Status.valueOf(rs.getString("status")))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .lastTouchedAt(rs.getTimestamp("last_touched_at").toInstant());
        final String holder = rs.getString("holder_id");
        if (holder != null) {
            b.holderId(holder);
        }
        final String resultBlob = rs.getString("result_blob");
        if (resultBlob != null) {
            codec.decodeResult(resultBlob).ifPresent(b::result);
        }
        return b.build();
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
