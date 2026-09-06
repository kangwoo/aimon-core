package at.aimon.session.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionInboxException;
import at.aimon.core.agent.session.inbox.CollectedBatch;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.inbox.UnreadableEntry;
import at.aimon.session.postgres.internal.InboundMessageRowCodec;

/**
 * Postgres-backed {@link SessionInbox} per design §4.3.
 *
 * <p>
 * One row per delivered message in {@code conversation_inbox}. Drain is a single SQL statement —
 * {@code DELETE ... USING (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1024) RETURNING} — that preserves priority-then-FIFO
 * ordering via {@code ORDER BY priority, id} on the inner select.
 *
 * <p>
 * {@code SKIP LOCKED} ensures concurrent drains never block each other (defense in depth: the manager invariant is that
 * only the lock holder calls {@code collect}, but the SPI shouldn't deadlock on a misbehaving caller).
 */
public final class PostgresSessionInbox implements SessionInbox {

    /** Maximum messages a single {@code collect} call removes; matches design §4.3. */
    public static final int DEFAULT_COLLECT_LIMIT = 1024;

    private static final Logger log = LoggerFactory.getLogger(PostgresSessionInbox.class);

    private static final String SQL_DELIVER = "INSERT INTO conversation_inbox "
            + "(conversation_id, agent_ref, priority, payload, delivered_at) "
            + "VALUES (?, ?, ?, ?::jsonb, ?) RETURNING id";

    private static final String SQL_COLLECT = "DELETE FROM conversation_inbox "
            + "WHERE id IN (SELECT id FROM conversation_inbox " + "WHERE conversation_id = ? AND priority <= ? "
            + "ORDER BY priority, id FOR UPDATE SKIP LOCKED LIMIT ?) " + "RETURNING id, priority, payload::text";

    private static final String SQL_IS_EMPTY = "SELECT 1 FROM conversation_inbox WHERE conversation_id = ? LIMIT 1";

    private static final String SQL_PURGE = "DELETE FROM conversation_inbox WHERE conversation_id = ?";

    private final DataSource dataSource;
    private final InboundMessageRowCodec codec;
    private final int collectLimit;

    public PostgresSessionInbox(DataSource dataSource) {
        this(dataSource, defaultMapper(), DEFAULT_COLLECT_LIMIT);
    }

    public PostgresSessionInbox(DataSource dataSource, ObjectMapper mapper, int collectLimit) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.codec = new InboundMessageRowCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
        if (collectLimit < 1) {
            throw new IllegalArgumentException("collectLimit must be >= 1: " + collectLimit);
        }
        this.collectLimit = collectLimit;
    }

    @Override
    public InboundMessageId deliver(InboundMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        final String payload = codec.encode(message);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_DELIVER)) {
            ps.setString(1, message.getSessionId().value());
            ps.setString(2, message.getAgentRef());
            ps.setShort(3, (short) message.getPriority().ordinal());
            ps.setString(4, payload);
            ps.setTimestamp(5, Timestamp.from(message.getDeliveredAt()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SessionInboxException(
                            "INSERT into conversation_inbox returned no id for " + message.getSessionId());
                }
                final long id = rs.getLong(1);
                log.debug("Delivered to conversation_inbox (convId={}, id={})", message.getSessionId(), id);
                return InboundMessageId.of(Long.toString(id));
            }
        } catch (SQLException e) {
            throw new SessionInboxException("Postgres error delivering to " + message.getSessionId(), e);
        }
    }

    @Override
    public CollectedBatch collect(SessionId id, QueuedInputPriority maxPriority) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(maxPriority, "maxPriority must not be null");
        final List<RowAndPriority> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            // FOR UPDATE SKIP LOCKED requires an explicit transaction so the locks survive across the DELETE.
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(SQL_COLLECT)) {
                ps.setString(1, id.value());
                ps.setShort(2, (short) maxPriority.ordinal());
                ps.setInt(3, collectLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final long rowId = rs.getLong("id");
                        final short pri = rs.getShort("priority");
                        final String payload = rs.getString("payload");
                        rows.add(new RowAndPriority(rowId, pri, payload));
                    }
                }
            }
            c.commit();
            c.setAutoCommit(true);
        } catch (SQLException e) {
            throw new SessionInboxException("Postgres error collecting from " + id, e);
        }
        // RETURNING does not honor the inner ORDER BY, so re-sort by (priority, id) before handing back.
        rows.sort((a, b) -> {
            final int p = Short.compare(a.priority, b.priority);
            return p != 0 ? p : Long.compare(a.id, b.id);
        });
        final List<InboundMessage> out = new ArrayList<>(rows.size());
        final List<UnreadableEntry> unreadable = new ArrayList<>();
        for (RowAndPriority r : rows) {
            final String rowId = Long.toString(r.id);
            try {
                out.add(codec.decode(r.payload, rowId));
            } catch (RuntimeException e) {
                // The boundary is the entry, not the exception type. The DELETE ... RETURNING above is already
                // committed, so letting one row's failure out of here destroys every message the call collected —
                // and the type it arrives as is not this codec's to predict: Principal.Type.valueOf throws a plain
                // IllegalArgumentException, and the next value object to grow a rule will throw something else again.
                unreadable.add(codec.recoverAddress(r.payload, rowId, e));
                log.warn(
                        "Dropping an inbox entry this build cannot decode [session={}, entryId={}]: {}."
                                + " It is already out of the inbox, so its turn will not run anywhere.",
                        id, rowId, e.toString());
            }
        }
        return CollectedBatch.of(out, unreadable);
    }

    @Override
    public boolean isEmpty(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_IS_EMPTY)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        } catch (SQLException e) {
            throw new SessionInboxException("Postgres error sizing " + id, e);
        }
    }

    @Override
    public void purge(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_PURGE)) {
            ps.setString(1, id.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SessionInboxException("Postgres error purging " + id, e);
        }
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static final class RowAndPriority {
        final long id;
        final short priority;
        final String payload;

        RowAndPriority(long id, short priority, String payload) {
            this.id = id;
            this.priority = priority;
            this.payload = payload;
        }
    }
}
