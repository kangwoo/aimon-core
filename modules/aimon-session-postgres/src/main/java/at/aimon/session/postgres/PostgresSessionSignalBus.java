package at.aimon.session.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
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
 * <li>{@link #publishAll} sends a list as one multi-row {@code INSERT} that also rings the doorbell once, in one
 * transaction: two round trips and one commit for the list, not three round trips and a commit per signal. That is
 * what keeps a turn's event stream — a signal per text delta — from costing a commit per delta. One doorbell is enough
 * because the dispatcher never looks at the id a notification carries: it fetches every row past its high-water mark,
 * in id order, and the insert reads its rows in list order.</li>
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

    /**
     * One statement text whatever the size of the list: the rows travel as four arrays. A {@code VALUES} list would be
     * a different text for every row count, and the driver keeps a server-prepared statement — and the server a cached
     * plan — for each text it has seen a few times on a connection; with batches of 1 to 256 rows that is hundreds of
     * plans on every pooled connection. {@code ORDER BY ord} is what makes the ids follow list order by statement
     * rather than by accident of how the rows happen to be read.
     */
    private static final String SQL_INSERT_BATCH = "WITH ins AS (INSERT INTO conversation_signal "
            + "(conversation_id, kind, origin_node_id, payload) "
            + "SELECT c, k, o, p::jsonb FROM unnest(?::text[], ?::text[], ?::text[], ?::text[]) "
            + "WITH ORDINALITY AS t(c, k, o, p, ord) ORDER BY ord RETURNING id) "
            + "SELECT pg_notify(?, max(id)::text) FROM ins";

    /**
     * Rows per batch statement. A transaction holds the ids it has taken until it commits, and the statement takes
     * longer the more rows it inserts, so a long list goes out as several short transactions rather than one long one
     * — see the note on the high-water mark on {@link #publishAll}. Sized to the largest list the event relay sends.
     */
    static final int MAX_BATCH_ROWS = 256;

    /** SQLSTATE class 08 — connection exception. */
    private static final String SQLSTATE_CONNECTION_CLASS = "08";

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
     * Publishes the list with one multi-row {@code INSERT} and one {@code pg_notify}, in one transaction on one
     * connection. A list longer than {@link #MAX_BATCH_ROWS} goes out as several such transactions, in order.
     *
     * <p>
     * <b>Order.</b> The insert reads its rows in list order ({@code ORDER BY} the position in the list), so they take
     * their ids in list order, and the dispatcher on every node reads {@code conversation_signal} in id order.
     *
     * <p>
     * <b>One doorbell.</b> The dispatcher does not use the id a notification carries; any notification makes it fetch
     * every row past its high-water mark. So the batch notifies once, with its highest id — the payload an older
     * node's {@link #publish} would have sent for the same row.
     *
     * <p>
     * <b>A signal that cannot be published does not take the rest with it.</b> Payloads are encoded before a
     * connection is taken, so one that cannot be encoded costs only itself. A row the server rejects aborts the
     * statement without saying which row it was; the transaction is rolled back — nothing of the batch was published —
     * and the batch is published again one signal at a time through {@link #publish}, in order, where the one at fault
     * fails alone. The statement's own error is logged and, if anything is thrown, attached to it as suppressed: a
     * statement that fails for a reason that is not a row (a timeout, a read-only standby) looks the same from here,
     * and the log is where that shows. Savepoints would find the row too, but at a round trip per row inside a
     * transaction that holds
     * every id it has taken until it commits; see the note on the high-water mark below.
     *
     * <p>
     * <b>Nothing is published twice.</b> The fallback runs only when the statement failed, which is before a commit
     * was asked for. A failed {@code COMMIT} is different: its outcome is not known, so it is thrown as it is and the
     * rest of the list is not attempted.
     *
     * <p>
     * <b>The transport.</b> Failing to obtain a connection is thrown straight away, and so is a connection failure
     * while publishing one at a time: against a database that is not there, each further attempt would wait out the
     * pool's connection timeout. A batch statement that fails on a broken connection still falls back once, because a
     * stale pooled connection is the usual cause and the next checkout is a fresh one.
     *
     * <p>
     * <b>The high-water mark.</b> {@code ListenDispatcher} fetches {@code id > lastSeen}. Ids are taken at insert and
     * become visible at commit, so a row whose transaction commits after a later id has been fetched is never
     * delivered to that node. This method does not create that. Its ids are held for one statement and a commit —
     * which is not always less than {@link #publish} holds its one id for: the statement takes longer the more rows
     * and the larger the payloads, where {@code publish} has a fixed two round trips. What falls is the number of such
     * windows, one per list rather than one per signal, and that is why the exposure falls. What rises is the size of
     * a loss when one happens: a whole batch, which may include a turn's terminal frame, rather than one signal.
     *
     * @param signals
     *            the signals to publish, in delivery order (must not be null; may be empty)
     * @throws SessionSignalBusException
     *             once every signal has been attempted, if any could not be published; or straight away on a transport
     *             failure or a commit whose outcome is unknown
     */
    @Override
    public void publishAll(List<SessionSignal> signals) {
        Objects.requireNonNull(signals, "signals must not be null");
        if (signals.isEmpty()) {
            return;
        }
        final List<SessionSignal> encodable = new ArrayList<>(signals.size());
        final List<String> payloads = new ArrayList<>(signals.size());
        RuntimeException failure = null;
        final List<SQLException> batchErrors = new ArrayList<>();
        for (SessionSignal signal : signals) {
            try {
                Objects.requireNonNull(signal, "signal must not be null");
                payloads.add(codec.encodePayload(signal.getPayload()));
                encodable.add(signal);
            } catch (RuntimeException e) {
                failure = keepFirst(failure, e);
            }
        }
        for (int from = 0; from < encodable.size(); from += MAX_BATCH_ROWS) {
            final int to = Math.min(from + MAX_BATCH_ROWS, encodable.size());
            final List<SessionSignal> chunk = encodable.subList(from, to);
            try {
                final SQLException batchError = insertBatch(chunk, payloads.subList(from, to));
                if (batchError != null) {
                    batchErrors.add(batchError);
                    failure = keepFirst(failure, publishEach(chunk));
                }
            } catch (SessionSignalBusException fatal) {
                if (failure != null && failure != fatal) {
                    fatal.addSuppressed(failure);
                }
                batchErrors.forEach(fatal::addSuppressed);
                throw fatal;
            }
        }
        if (failure != null) {
            final SessionSignalBusException thrown = new SessionSignalBusException("Postgres could not publish every"
                    + " one of " + signals.size() + " signals; first failure: " + failure.getMessage(), failure);
            batchErrors.forEach(thrown::addSuppressed);
            throw thrown;
        }
    }

    /**
     * Inserts the rows and rings the doorbell in one transaction.
     *
     * @return {@code null} when the transaction committed; the statement's error when it failed and the transaction
     *         was rolled back, so that none of the rows was published
     * @throws SessionSignalBusException
     *             when no connection could be obtained, or the commit itself failed and its outcome is unknown
     */
    private SQLException insertBatch(List<SessionSignal> signals, List<String> payloads) {
        final int n = signals.size();
        final String[] sessionIds = new String[n];
        final String[] kinds = new String[n];
        final String[] origins = new String[n];
        for (int i = 0; i < n; i++) {
            final SessionSignal signal = signals.get(i);
            sessionIds[i] = signal.getSessionId().value();
            kinds[i] = signal.getKind().name();
            origins[i] = signal.getOriginNodeId();
        }
        try (Connection c = publishDataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_BATCH)) {
                    ps.setArray(1, c.createArrayOf("text", sessionIds));
                    ps.setArray(2, c.createArrayOf("text", kinds));
                    ps.setArray(3, c.createArrayOf("text", origins));
                    ps.setArray(4, c.createArrayOf("text", payloads.toArray(new String[0])));
                    ps.setString(5, ListenDispatcher.CHANNEL);
                    ps.execute();
                } catch (SQLException e) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        /* best-effort rollback; no commit was asked for, so nothing was published either way */
                    }
                    log.warn("Batch insert of {} signals failed, publishing them one at a time: {}", n, e.toString());
                    return e;
                }
                c.commit();
                return null;
            } finally {
                restoreAutoCommit(c);
            }
        } catch (SQLException e) {
            throw new SessionSignalBusException("Postgres error publishing a batch of " + n + " signals", e);
        }
    }

    /**
     * Best-effort: by now the batch has either committed or been rolled back, and a connection too broken to take this
     * must not turn either outcome into a different one.
     */
    private static void restoreAutoCommit(Connection c) {
        try {
            c.setAutoCommit(true);
        } catch (SQLException e) {
            log.debug("Could not restore autocommit after a batch publish: {}", e.toString());
        }
    }

    /**
     * Publishes each signal on its own, in order, and returns the first failure with the later ones suppressed — or
     * {@code null} when every one was published.
     *
     * @throws SessionSignalBusException
     *             straight away when a publish fails on the connection rather than on the signal
     */
    private RuntimeException publishEach(List<SessionSignal> signals) {
        RuntimeException failure = null;
        for (SessionSignal signal : signals) {
            try {
                publish(signal);
            } catch (SessionSignalBusException e) {
                if (isConnectionFailure(e.getCause())) {
                    if (failure != null) {
                        e.addSuppressed(failure);
                    }
                    throw e;
                }
                failure = keepFirst(failure, e);
            } catch (RuntimeException e) {
                failure = keepFirst(failure, e);
            }
        }
        return failure;
    }

    private static boolean isConnectionFailure(Throwable cause) {
        if (cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException) {
            return true;
        }
        return cause instanceof SQLException sql && sql.getSQLState() != null
                && sql.getSQLState().startsWith(SQLSTATE_CONNECTION_CLASS);
    }

    private static RuntimeException keepFirst(RuntimeException first, RuntimeException next) {
        if (next == null) {
            return first;
        }
        if (first == null) {
            return next;
        }
        if (next != first) {
            first.addSuppressed(next);
        }
        return first;
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
            } catch (RuntimeException | Error ex) {
                // Error too — see ListenDispatcher: this runs on the listen thread, which nothing restarts.
                log.warn("Signal handler threw for {}: {}", signal.getSessionId(), ex.toString());
            }
        }
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
