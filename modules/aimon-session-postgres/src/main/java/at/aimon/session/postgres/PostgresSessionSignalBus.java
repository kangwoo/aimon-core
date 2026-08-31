package at.aimon.session.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionSignalBusException;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.session.postgres.internal.ListenDispatcher;
import at.aimon.session.postgres.internal.SessionSignalRowCodec;

/**
 * Postgres-backed {@link SessionSignalBus} per design §4.2.
 *
 * <p>
 * Hybrid model:
 * <ul>
 * <li>{@link #publish} inserts the signal envelope into {@code conversation_signal} (jsonb payload) inside a
 * transaction, then issues {@code pg_notify(conversation_signal_doorbell, '<id>')}. The NOTIFY payload is just the row
 * id — never approaches the 8 KB Postgres limit no matter how large the actual signal payload is.</li>
 * <li>{@link #subscribe} registers an in-memory handler and asks the {@link ListenDispatcher} to track this
 * session. The dispatcher owns one dedicated long-lived connection running {@code LISTEN
 * conversation_signal_doorbell}; on each notification (or on a periodic 5 s self-poll backstop) it fetches new rows
 * from {@code conversation_signal} via the supplied {@code fetchDataSource}, decodes them, and dispatches to
 * registered handlers.</li>
 * </ul>
 *
 * <p>
 * Connection topology (design §6):
 * <ul>
 * <li>{@code publishDataSource} — Hikari main pool (short transactions for INSERT + NOTIFY).</li>
 * <li>{@code fetchDataSource} — Hikari signal pool (recommended {@code min=1, max=2}); used by the dispatcher.</li>
 * <li>The dispatcher's LISTEN connection — opened directly via {@code DriverManager}, outside Hikari.</li>
 * </ul>
 *
 * <p>
 * {@code dropSelfBroadcast} (default {@code true}) skips delivery when a signal's {@code originNodeId} equals this
 * bus's {@code nodeId}, matching the §5.3 fan-out diagram.
 */
public final class PostgresSessionSignalBus implements SessionSignalBus, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresSessionSignalBus.class);

    private static final String SQL_INSERT_SIGNAL = "INSERT INTO conversation_signal "
            + "(conversation_id, kind, origin_node_id, payload) VALUES (?, ?, ?, ?::jsonb) RETURNING id";

    private static final String SQL_NOTIFY = "SELECT pg_notify(?, ?)";

    private static final String SQL_DELETE_OLD = "DELETE FROM conversation_signal WHERE created_at < ?";

    private final DataSource publishDataSource;
    private final ListenDispatcher dispatcher;
    private final SessionSignalRowCodec codec;
    private final String nodeId;
    private final boolean dropSelfBroadcast;

    // @formatter:off
    private final ConcurrentMap<SessionId, List<Consumer<SessionSignal>>> handlers
            = new ConcurrentHashMap<>();
    // @formatter:on

    public PostgresSessionSignalBus(DataSource publishDataSource, DataSource fetchDataSource, String jdbcUrl,
            Properties listenConnectionProps, String nodeId) {
        this(publishDataSource, fetchDataSource, jdbcUrl, listenConnectionProps, nodeId, defaultMapper(), true);
    }

    public PostgresSessionSignalBus(DataSource publishDataSource, DataSource fetchDataSource, String jdbcUrl,
            Properties listenConnectionProps, String nodeId, ObjectMapper mapper, boolean dropSelfBroadcast) {
        this.publishDataSource = Objects.requireNonNull(publishDataSource, "publishDataSource must not be null");
        Objects.requireNonNull(fetchDataSource, "fetchDataSource must not be null");
        Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        Objects.requireNonNull(listenConnectionProps, "listenConnectionProps must not be null");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.codec = new SessionSignalRowCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
        this.dropSelfBroadcast = dropSelfBroadcast;
        this.dispatcher = new ListenDispatcher(jdbcUrl, listenConnectionProps, fetchDataSource, codec);
        this.dispatcher.start();
    }

    @Override
    public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        final boolean[] firstSubscriber = {false};
        handlers.compute(id, (k, list) -> {
            if (list == null) {
                firstSubscriber[0] = true;
                final List<Consumer<SessionSignal>> created = new CopyOnWriteArrayList<>();
                created.add(handler);
                return created;
            }
            list.add(handler);
            return list;
        });
        if (firstSubscriber[0]) {
            dispatcher.track(id, this::dispatch);
        }
        return () -> unsubscribeOne(id, handler);
    }

    @Override
    public void publish(SessionSignal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        final String payloadJson = codec.encodePayload(signal.getPayload());
        try (Connection c = publishDataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                final long id;
                try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_SIGNAL)) {
                    ps.setString(1, signal.getSessionId().value());
                    ps.setString(2, signal.getKind().name());
                    ps.setString(3, signal.getOriginNodeId());
                    ps.setString(4, payloadJson);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("conversation_signal insert returned no id");
                        }
                        id = rs.getLong(1);
                    }
                }
                try (PreparedStatement notify = c.prepareStatement(SQL_NOTIFY)) {
                    notify.setString(1, ListenDispatcher.CHANNEL);
                    notify.setString(2, Long.toString(id));
                    notify.execute();
                }
                c.commit();
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    /* best-effort rollback */
                }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new SessionSignalBusException(
                    "Postgres error publishing " + signal.getKind() + " for " + signal.getSessionId(), e);
        }
    }

    /**
     * Reaps {@code conversation_signal} rows older than {@code cutoff}. Called by the manager's scheduled cleanup
     * (design §4.2).
     *
     * @param cutoff
     *            rows with {@code created_at < cutoff} are deleted
     * @return number of rows deleted
     */
    public int sweepOlderThan(java.time.Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        try (Connection c = publishDataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_DELETE_OLD)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(cutoff));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new SessionSignalBusException("Postgres error during conversation_signal sweep", e);
        }
    }

    @Override
    public void close() {
        dispatcher.close();
        handlers.clear();
    }

    private void unsubscribeOne(SessionId id, Consumer<SessionSignal> handler) {
        final boolean[] lastSubscriber = {false};
        handlers.computeIfPresent(id, (k, list) -> {
            list.remove(handler);
            if (list.isEmpty()) {
                lastSubscriber[0] = true;
                return null;
            }
            return list;
        });
        if (lastSubscriber[0]) {
            dispatcher.untrack(id);
        }
    }

    private void dispatch(SessionSignal signal) {
        if (dropSelfBroadcast && nodeId.equals(signal.getOriginNodeId())) {
            return;
        }
        final List<Consumer<SessionSignal>> list = handlers.get(signal.getSessionId());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Consumer<SessionSignal> h : list) {
            try {
                h.accept(signal);
            } catch (RuntimeException ex) {
                log.warn("Signal handler threw for {}: {}", signal.getSessionId(), ex.toString());
            }
        }
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
