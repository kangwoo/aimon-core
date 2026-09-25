package at.aimon.session.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;

/**
 * PostgreSQL-backed {@link SessionLogSegmentStore}: one row per sealed segment in {@code session_log_segment}
 * ({@code db/postgres/V2__session_log_segment.sql}).
 *
 * <p>
 * The primary key is {@code (session_id, segment_id)}, so {@link #put} is a plain {@code INSERT} and a duplicate id is
 * rejected rather than overwritten, and every read and delete is scoped by session through the key's leading column.
 * The payload is {@code text} for the reason the record's transcript is: model output may contain NUL, which
 * {@code jsonb} rejects. Every statement is a single auto-committed one, so a {@code get} after a {@code put} sees it.
 *
 * <p>
 * Not fenced, like the record store: deletes are fenced when reached through {@code SessionStore.segments(...)}.
 */
public final class PostgresSessionLogSegmentStore implements SessionLogSegmentStore {

    private static final String SQL_PUT = "INSERT INTO session_log_segment "
            + "(session_id, segment_id, from_seq, to_seq, entry_count, payload, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_GET = "SELECT from_seq, to_seq, entry_count, payload, created_at "
            + "FROM session_log_segment WHERE session_id = ? AND segment_id = ?";

    private static final String SQL_LIST = "SELECT segment_id, created_at FROM session_log_segment "
            + "WHERE session_id = ?";

    // The primary key's leading column serves both the range on session_id and the order; the cursor is the last id.
    private static final String SQL_SCAN = "SELECT DISTINCT session_id FROM session_log_segment "
            + "WHERE created_at < ? AND session_id > ? ORDER BY session_id LIMIT ?";

    private static final String SQL_DELETE = "DELETE FROM session_log_segment WHERE session_id = ? AND segment_id = ?";

    private static final String SQL_DELETE_ALL = "DELETE FROM session_log_segment WHERE session_id = ?";

    private final DataSource dataSource;

    /**
     * @param dataSource
     *            the pool, pointed at a database V2 has been applied to (must not be null)
     */
    public PostgresSessionLogSegmentStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void put(SessionLogSegment segment) {
        Objects.requireNonNull(segment, "segment must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_PUT)) {
            ps.setString(1, segment.getSessionId().value());
            ps.setString(2, segment.getId().value());
            ps.setLong(3, segment.getFromSeq());
            ps.setLong(4, segment.getToSeq());
            ps.setInt(5, segment.getEntryCount());
            ps.setString(6, segment.getPayload());
            ps.setTimestamp(7, Timestamp.from(segment.getCreatedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("put", segment.getSessionId(), e);
        }
    }

    @Override
    public Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_GET)) {
            ps.setString(1, sessionId.value());
            ps.setString(2, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(SessionLogSegment.builder().sessionId(sessionId).id(id).fromSeq(rs.getLong(1))
                        .toSeq(rs.getLong(2)).entryCount(rs.getInt(3)).payload(rs.getString(4))
                        .createdAt(rs.getTimestamp(5).toInstant()).build());
            }
        } catch (SQLException e) {
            throw failure("get", sessionId, e);
        }
    }

    @Override
    public List<SegmentInfo> list(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final List<SegmentInfo> infos = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_LIST)) {
            ps.setString(1, sessionId.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    infos.add(SegmentInfo.of(SegmentId.of(rs.getString(1)), rs.getTimestamp(2).toInstant()));
                }
            }
        } catch (SQLException e) {
            throw failure("list", sessionId, e);
        }
        return infos;
    }

    /**
     * Exact: only sessions with a segment older than {@code createdBefore}, in ascending id order, each once per pass.
     * The cursor is the last session id of the previous page; a fresh pass starts after the empty string, which sorts
     * before every id.
     */
    @Override
    public SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit) {
        Objects.requireNonNull(createdBefore, "createdBefore must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        final List<SessionId> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_SCAN)) {
            ps.setTimestamp(1, Timestamp.from(createdBefore));
            ps.setString(2, cursor == null ? "" : cursor);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(SessionId.of(rs.getString(1)));
                }
            }
        } catch (SQLException e) {
            throw new SessionLogSegmentStoreException("Postgres error during scanSessions", e);
        }
        return SegmentScanPage.of(ids, ids.size() < limit ? null : ids.get(ids.size() - 1).value());
    }

    @Override
    public void delete(SessionId sessionId, SegmentId id) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_DELETE)) {
            ps.setString(1, sessionId.value());
            ps.setString(2, id.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("delete", sessionId, e);
        }
    }

    @Override
    public void deleteAll(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_DELETE_ALL)) {
            ps.setString(1, sessionId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("deleteAll", sessionId, e);
        }
    }

    private static SessionLogSegmentStoreException failure(String operation, SessionId sessionId, Exception cause) {
        return new SessionLogSegmentStoreException("Postgres error during " + operation + " for " + sessionId, cause);
    }
}
