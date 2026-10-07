package at.aimon.session.postgres.internal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;

/**
 * Owns the dedicated LISTEN connection for {@code conversation_signal_doorbell}.
 *
 * <p>
 * The LISTEN connection lives outside HikariCP because LISTEN state is connection-bound and cannot survive being
 * returned to a pool — see design §6.2. A second short-lived {@link DataSource} (typically the bus's signal pool) is
 * used for the row-fetch SELECTs so the dedicated LISTEN connection only ever blocks on
 * {@code PGConnection.getNotifications()}.
 *
 * <p>
 * Reconnect semantics (design §6.2 / §8 #2): on any {@code SQLException} the dispatcher closes the listen connection,
 * sleeps 500 ms, opens a new one, re-issues {@code LISTEN}, runs a backlog query starting at the highest id seen so
 * far, and resumes. No signal loss because the rows live in the table independent of NOTIFY delivery.
 *
 * <p>
 * <b>Rows that commit late.</b> An id is taken at insert and the row is visible at commit, so with two publishers a
 * fetch can return a later id before an earlier one exists. A bare high-water mark would then never see the earlier
 * row. The ids a fetch steps over are therefore kept by a {@link SignalGapTracker} for {@link #GAP_GRACE_MILLIS} —
 * within that tracker's bounds — and asked for in the same statement as the new rows on each pass until they appear.
 * One statement, so one snapshot: a recovered row and the rows its publisher sent after it are seen together and
 * delivered in id order. A row recovered this way is still delivered after rows with higher ids that an earlier pass
 * had already delivered: order holds within one publisher's sequential publishes — which is what a session's event
 * stream is — and not across publishers. A transaction that stays open past the grace period still loses its rows
 * here.
 *
 * <p>
 * Backstop self-poll (design §4.2): every {@link #selfPollMillis} ms the dispatcher re-runs the fetch query without
 * waiting for a doorbell, catching any rows that arrived while a NOTIFY was dropped (e.g. backend crash, network
 * blip).
 */
public final class ListenDispatcher implements AutoCloseable {

    /**
     * The {@code LISTEN} / {@code pg_notify} channel both ends of the doorbell agree on.
     *
     * <p>
     * <b>FROZEN WIRE NAME — do not rename alongside the Java identifier.</b> Unlike the table and column names this
     * one is not in the DDL, so {@code PostgresSchemaFreezeTest} structurally cannot see it, and both ends route
     * through this single constant — a rename moves the {@code LISTEN} below and the {@code pg_notify} in
     * {@code PostgresSessionSignalBus} in lockstep, leaving every tier green including {@code @Tag("docker")}.
     *
     * <p>
     * What breaks is a rolling deploy specifically: an un-upgraded node listens on the old channel while an upgraded
     * one notifies on the new, so doorbells are missed and cross-node signalling silently degrades to the
     * {@link #selfPollMillis} backstop — correct, but seconds late instead of immediate. Pinned by
     * {@code ListenDispatcherChannelFreezeTest}.
     */
    public static final String CHANNEL = "conversation_signal_doorbell";

    private static final Logger log = LoggerFactory.getLogger(ListenDispatcher.class);

    private static final String SQL_FETCH = "SELECT id, conversation_id, kind, origin_node_id, payload::text "
            + "FROM conversation_signal WHERE id > ? ORDER BY id LIMIT 10000";

    /** {@link #SQL_FETCH} widened to the ids a previous fetch stepped over — one statement, so one snapshot. */
    private static final String SQL_FETCH_WITH_GAPS = "SELECT id, conversation_id, kind, origin_node_id, "
            + "payload::text FROM conversation_signal WHERE id > ? OR id = ANY(?) ORDER BY id LIMIT 10000";

    /**
     * How long an id that a fetch stepped over is still asked for, counted from when it was first stepped over. Far
     * longer than a publish transaction takes — an insert, a notify, a commit — above the 30 s
     * {@code idle_in_transaction_session_timeout} the deployment notes recommend, and short enough that the ids of
     * rolled-back transactions do not pile up.
     */
    public static final long GAP_GRACE_MILLIS = 60_000L;

    /** At most one warning per this long about holes there was no room to track. */
    private static final long UNTRACKED_WARN_INTERVAL_NANOS = 10_000_000_000L;

    private static final String SQL_MAX_ID = "SELECT COALESCE(MAX(id), 0) AS max_id FROM conversation_signal";

    private final String jdbcUrl;
    private final Properties connectionProps;
    private final DataSource fetchDataSource;
    private final SessionSignalRowCodec codec;
    private final long pollMillis;
    private final long selfPollMillis;
    private final long reconnectBackoffMillis;

    private final ConcurrentMap<SessionId, Consumer<SessionSignal>> handlers = new ConcurrentHashMap<>();
    private final AtomicLong lastSeenId = new AtomicLong(0);
    /** Touched only by the listener thread, like the fetch that feeds it. */
    private final SignalGapTracker gaps = new SignalGapTracker(GAP_GRACE_MILLIS);
    private long lastUntrackedWarnNanos = System.nanoTime() - UNTRACKED_WARN_INTERVAL_NANOS;

    private volatile Thread thread;
    private volatile boolean running;
    private volatile Connection listenConnection;

    public ListenDispatcher(String jdbcUrl, Properties connectionProps, DataSource fetchDataSource,
            SessionSignalRowCodec codec) {
        this(jdbcUrl, connectionProps, fetchDataSource, codec, 500L, 5_000L, 500L);
    }

    public ListenDispatcher(String jdbcUrl, Properties connectionProps, DataSource fetchDataSource,
            SessionSignalRowCodec codec, long pollMillis, long selfPollMillis, long reconnectBackoffMillis) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.connectionProps = Objects.requireNonNull(connectionProps, "connectionProps must not be null");
        this.fetchDataSource = Objects.requireNonNull(fetchDataSource, "fetchDataSource must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.pollMillis = pollMillis;
        this.selfPollMillis = selfPollMillis;
        this.reconnectBackoffMillis = reconnectBackoffMillis;
    }

    /** Starts the listener thread. Idempotent. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        // Seed lastSeenId from current MAX(id) so freshly attached subscribers don't replay the entire backlog.
        try (Connection c = fetchDataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(SQL_MAX_ID)) {
            if (rs.next()) {
                lastSeenId.set(rs.getLong("max_id"));
            }
        } catch (SQLException e) {
            log.warn("Failed to seed lastSeenId; will replay from 0: {}", e.toString());
        }
        thread = new Thread(this::runLoop, "aimon-postgres-listen");
        thread.setDaemon(true);
        thread.start();
    }

    public void track(SessionId id, Consumer<SessionSignal> handler) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        handlers.put(id, handler);
    }

    public void untrack(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        handlers.remove(id);
    }

    @Override
    public synchronized void close() {
        running = false;
        final Connection c = listenConnection;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                /* best-effort */
            }
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        handlers.clear();
    }

    private void runLoop() {
        while (running) {
            try {
                openAndListen();
                pumpNotifications();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (SQLException e) {
                if (!running) {
                    return;
                }
                log.warn("LISTEN connection error, reconnecting: {}", e.toString());
            } catch (RuntimeException e) {
                // Anything else that escapes a pass — a row this build cannot decode, say — would otherwise end the
                // thread for good. Reconnect instead; the same row may fail again, which is a warning per backoff
                // rather than a node that has silently stopped listening.
                if (!running) {
                    return;
                }
                log.warn("LISTEN loop failed, reconnecting: {}", e.toString());
            } finally {
                closeListenConnection();
            }
            if (!running) {
                return;
            }
            try {
                Thread.sleep(reconnectBackoffMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void openAndListen() throws SQLException {
        final Connection c = DriverManager.getConnection(jdbcUrl, connectionProps);
        c.setAutoCommit(true);
        try (Statement s = c.createStatement()) {
            s.execute("LISTEN " + CHANNEL);
        }
        listenConnection = c;
        // After (re)connecting always replay anything we missed during the gap.
        fetchAndDispatch();
    }

    private void pumpNotifications() throws SQLException, InterruptedException {
        long lastSelfPoll = System.currentTimeMillis();
        final PGConnection pgc = listenConnection.unwrap(PGConnection.class);
        while (running) {
            final PGNotification[] notifications = pgc.getNotifications((int) pollMillis);
            if (notifications != null && notifications.length > 0) {
                fetchAndDispatch();
            }
            final long now = System.currentTimeMillis();
            if (now - lastSelfPoll >= selfPollMillis) {
                fetchAndDispatch();
                lastSelfPoll = now;
            }
            // Detect closed connection eagerly.
            if (listenConnection.isClosed()) {
                throw new SQLException("LISTEN connection closed");
            }
        }
    }

    private void fetchAndDispatch() {
        final long since = lastSeenId.get();
        final Long[] missing = gaps.outstanding();
        try (Connection c = fetchDataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(missing.length == 0 ? SQL_FETCH : SQL_FETCH_WITH_GAPS)) {
            ps.setLong(1, since);
            if (missing.length > 0) {
                ps.setArray(2, c.createArrayOf("bigint", missing));
            }
            try (ResultSet rs = ps.executeQuery()) {
                long maxSeen = since;
                long untracked = 0;
                while (rs.next()) {
                    final long id = dispatchRow(rs);
                    if (id <= since) {
                        // One of the ids asked for by name: it has turned up.
                        gaps.resolved(id);
                        continue;
                    }
                    untracked += gaps.noteHole(maxSeen + 1, id, System.nanoTime());
                    maxSeen = id;
                }
                if (untracked > 0) {
                    warnUntracked(untracked);
                }
                // CAS-style monotonic update: the mark never moves backwards.
                long current;
                do {
                    current = lastSeenId.get();
                    if (maxSeen <= current) {
                        break;
                    }
                } while (!lastSeenId.compareAndSet(current, maxSeen));
            }
            // After they have been asked for, never before — see SignalGapTracker#expire.
            gaps.expire(System.nanoTime());
        } catch (SQLException e) {
            log.warn("conversation_signal fetch failed: {}", e.toString());
        }
    }

    private void warnUntracked(long untracked) {
        final long now = System.nanoTime();
        if (now - lastUntrackedWarnNanos >= UNTRACKED_WARN_INTERVAL_NANOS) {
            lastUntrackedWarnNanos = now;
            log.warn("No room to track {} skipped conversation_signal id(s) — a row among them that commits late will"
                    + " not be delivered on this node", untracked);
        } else {
            log.debug("No room to track {} skipped conversation_signal id(s)", untracked);
        }
    }

    /**
     * Hands the current row to the handler tracking its session, if any.
     *
     * @return the row's id
     */
    private long dispatchRow(ResultSet rs) throws SQLException {
        final long id = rs.getLong("id");
        final SessionId convId = SessionId.of(rs.getString("conversation_id"));
        final SignalKind kind;
        try {
            kind = SignalKind.valueOf(rs.getString("kind"));
        } catch (IllegalArgumentException e) {
            log.warn("Skipping conversation_signal id={} with unknown kind={}", id, rs.getString("kind"));
            return id;
        }
        final String originNodeId = rs.getString("origin_node_id");
        final Map<String, Object> payload = codec.decodePayload(rs.getString("payload"));
        final Consumer<SessionSignal> handler = handlers.get(convId);
        if (handler != null) {
            try {
                handler.accept(SessionSignal.builder().sessionId(convId).kind(kind).originNodeId(originNodeId)
                        .payload(payload).build());
            } catch (RuntimeException | Error ex) {
                // Error too: this thread is the only one that delivers signals to this node, and nothing
                // restarts it. One handler's AssertionError or LinkageError must not end it.
                log.warn("Signal handler threw for {}: {}", convId, ex.toString());
            }
        }
        return id;
    }

    private void closeListenConnection() {
        final Connection c = listenConnection;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                /* best-effort */
            }
            listenConnection = null;
        }
    }
}
