package at.aimon.session.mongodb;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.bson.BsonDocument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoChangeStreamException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionSignalBusException;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.mongodb.internal.ResumeTokenStore;
import at.aimon.session.mongodb.internal.SessionSignalCodec;

/**
 * MongoDB Change Streams-backed {@link SessionSignalBus} per design §4.2.
 *
 * <p>
 * Publishes signals as {@code insertOne} documents in the capped {@code conversation_signals} collection. A single
 * background watcher thread per bus instance opens one change-stream cursor with a static
 * {@code $match: { operationType: "insert" }} pipeline; the dispatcher routes incoming inserts to subscribers by
 * looking up the {@code sessionId} field in an in-memory subscriber map.
 *
 * <p>
 * <strong>Implementation note (deviation from design §4.2 "Multiplexing"):</strong> single persistent cursor with
 * in-memory routing; the design's "rotate cursor on every subscribe" is deferred until subscriber-count scale demands
 * server-side {@code $match} narrowing. Bandwidth on the cursor is bounded by (holders on this node) × (signals per
 * turn) which is small for v1 deployments.
 *
 * <h2>Replica set required</h2>
 *
 * <p>
 * Change Streams require a replica set — even a single-node {@code rs.initiate()} is fine, but standalone MongoDB is
 * unsupported. Operators should run {@code init.js} (which prints a warning when {@code replSetGetStatus} reports the
 * deployment is not a replica set). The watcher thread surfaces the underlying {@code MongoCommandException} on
 * {@code watch()} when this prerequisite is missing.
 *
 * <h2>Self-broadcast dedup</h2>
 *
 * <p>
 * The change stream sees this node's own inserts. The dispatcher filters using
 * {@code signal.getOriginNodeId().equals(this.nodeId)} so SPI handlers never observe the publishing node's own publish.
 *
 * <h2>Resume tokens</h2>
 *
 * <p>
 * The watcher updates {@link ResumeTokenStore} after every dispatched event and on idle polls. On reconnect (after a
 * primary fail-over or a transient error) the watcher resumes from the last token. If the server refuses that token —
 * it has aged past the oplog window ({@code ChangeStreamHistoryLost}), or the stream no longer contains it — the
 * refusal arrives as a {@code MongoCommandException}; the watcher logs a warning, clears the token, and reopens without
 * resume. Signals published in the gap are lost (best-effort, matches the SPI's at-least-once semantics).
 *
 * <p>
 * The same happens when the server closes the cursor because the collection was dropped or recreated: the insert-only
 * pipeline hides the invalidate event, so the watcher notices the server cursor is gone and reopens.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * The watcher thread starts lazily on the first {@link #subscribe} call; calling {@link #close()} stops the thread and
 * unwinds the cursor. The {@link MongoDatabase} reference is owned by the caller — closing the bus does not close the
 * underlying {@code MongoClient}.
 */
public final class MongoSessionSignalBus implements SessionSignalBus, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoSessionSignalBus.class);

    private static final long WATCHER_RESTART_BACKOFF_MS = 500L;

    private final MongoCollection<Document> collection;
    private final SessionSignalCodec codec;
    private final String nodeId;
    private final ResumeTokenStore resumeTokenStore;

    // @formatter:off
    private final ConcurrentMap<SessionId, List<Consumer<SessionSignal>>> handlers
            = new ConcurrentHashMap<>();
    // @formatter:on
    private final AtomicBoolean watcherStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Thread watcherThread;
    private volatile MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor;

    public MongoSessionSignalBus(MongoDatabase database, String nodeId) {
        this(database, DocumentKeys.COLL_SIGNALS, nodeId);
    }

    public MongoSessionSignalBus(MongoDatabase database, String collectionName, String nodeId) {
        this(database, collectionName, nodeId, new ResumeTokenStore());
    }

    /** Test seam: lets a test start the watcher from a resume token of its choosing. */
    MongoSessionSignalBus(MongoDatabase database, String collectionName, String nodeId,
            ResumeTokenStore resumeTokenStore) {
        this.resumeTokenStore = Objects.requireNonNull(resumeTokenStore, "resumeTokenStore must not be null");
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.codec = new SessionSignalCodec();
    }

    @Override
    public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        if (closed.get()) {
            throw new IllegalStateException("Bus is closed");
        }
        handlers.compute(id, (k, list) -> {
            if (list == null) {
                final List<Consumer<SessionSignal>> created = new CopyOnWriteArrayList<>();
                created.add(handler);
                return created;
            }
            list.add(handler);
            return list;
        });
        ensureWatcherStarted();
        return () -> unsubscribeOne(id, handler);
    }

    @Override
    public void publish(SessionSignal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        if (closed.get()) {
            throw new IllegalStateException("Bus is closed");
        }
        try {
            final Document doc = codec.encode(signal);
            collection.insertOne(doc);
        } catch (MongoException e) {
            throw new SessionSignalBusException(
                    "Mongo error publishing " + signal.getKind() + " for " + signal.getSessionId(), e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        handlers.clear();
        final MongoChangeStreamCursor<ChangeStreamDocument<Document>> active = cursor;
        if (active != null) {
            try {
                active.close();
            } catch (Exception ignored) {
                // bus shutdown — ignore
            }
        }
        final Thread t = watcherThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return whether the watcher thread has been started and has not ended */
    boolean isWatcherAlive() {
        final Thread t = watcherThread;
        return t != null && t.isAlive();
    }

    private void unsubscribeOne(SessionId id, Consumer<SessionSignal> handler) {
        handlers.computeIfPresent(id, (k, list) -> {
            list.remove(handler);
            return list.isEmpty() ? null : list;
        });
    }

    private void ensureWatcherStarted() {
        if (!watcherStarted.compareAndSet(false, true)) {
            return;
        }
        final Thread t = new Thread(this::runWatcher, "mongo-signal-bus-" + nodeId);
        t.setDaemon(true);
        watcherThread = t;
        t.start();
    }

    private void runWatcher() {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            try {
                pumpOnce();
            } catch (MongoChangeStreamException e) {
                log.warn("Change stream history lost for node {} ({}); reopening without resume token", nodeId,
                        e.toString());
                resumeTokenStore.clear();
                sleepBackoff();
            } catch (MongoInterruptedException e) {
                if (closed.get()) {
                    return;
                }
                log.debug("Mongo cursor interrupted on node {} (close pending)", nodeId);
            } catch (MongoCommandException e) {
                if (closed.get()) {
                    return;
                }
                if (resumeTokenStore.last().isPresent()) {
                    // The server refused a command while this watcher was resuming from a stored token. This, not
                    // MongoChangeStreamException, is how "the token is older than the oplog" (ChangeStreamHistoryLost)
                    // and "the stream no longer contains it" arrive. Retrying the same token cannot succeed.
                    log.warn(
                            "Change stream watcher on node {} could not resume from its stored token ({}); starting"
                                    + " over without it — signals published in the gap are not delivered here",
                            nodeId, e.toString());
                    resumeTokenStore.clear();
                } else {
                    log.warn("Change stream watcher hit a server error on node {}: {}", nodeId, e.toString());
                }
                sleepBackoff();
            } catch (MongoException e) {
                if (closed.get()) {
                    return;
                }
                log.warn("Change stream watcher hit Mongo error on node {}: {}", nodeId, e.toString());
                sleepBackoff();
            } catch (RuntimeException e) {
                if (closed.get()) {
                    return;
                }
                log.warn("Change stream watcher hit unexpected error on node {}: {}", nodeId, e.toString());
                sleepBackoff();
            }
        }
    }

    private void pumpOnce() {
        final ChangeStreamIterable<Document> iterable = collection
                .watch(List.of(Aggregates.match(Filters.eq("operationType", "insert"))))
                .fullDocument(FullDocument.UPDATE_LOOKUP);
        resumeTokenStore.last().ifPresent(iterable::resumeAfter);
        try (MongoChangeStreamCursor<ChangeStreamDocument<Document>> active = iterable.cursor()) {
            cursor = active;
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                final ChangeStreamDocument<Document> change;
                try {
                    change = active.tryNext();
                } catch (MongoInterruptedException e) {
                    return;
                }
                if (change == null) {
                    if (active.getServerCursor() == null) {
                        // The server closed the cursor: the collection was dropped or renamed. The insert-only
                        // pipeline filters out the invalidate event that would have said so, and tryNext() on a dead
                        // cursor returns null for ever rather than throwing. A token from before an invalidate cannot
                        // be resumed from, so it goes too.
                        log.warn("Signal change stream on node {} was closed by the server (collection dropped or"
                                + " recreated?); reopening — signals published in the gap are not delivered here",
                                nodeId);
                        resumeTokenStore.clear();
                        sleepBackoff();
                        return;
                    }
                    // Keep the token moving while the channel is quiet, so that a reconnect after a long idle stretch
                    // does not resume from a token the oplog has already rolled past.
                    resumeTokenStore.update(active.getResumeToken());
                    sleepShort();
                    continue;
                }
                handle(change);
            }
        } finally {
            cursor = null;
        }
    }

    private void handle(ChangeStreamDocument<Document> change) {
        final BsonDocument token = change.getResumeToken();
        final Document full = change.getFullDocument();
        if (full == null) {
            // No fullDocument (rare for inserts on capped collections — typically present); update token and skip.
            resumeTokenStore.update(token);
            return;
        }
        final SessionSignal signal;
        try {
            signal = codec.decode(full);
        } catch (RuntimeException e) {
            log.warn("Failed to decode signal for node {} ({}): {}", nodeId, change.getDocumentKey(), e.toString());
            resumeTokenStore.update(token);
            return;
        }
        if (nodeId.equals(signal.getOriginNodeId())) {
            // Self-broadcast — drop, otherwise SPI handlers see their own publish.
            resumeTokenStore.update(token);
            return;
        }
        final List<Consumer<SessionSignal>> list = handlers.get(signal.getSessionId());
        if (list != null && !list.isEmpty()) {
            for (Consumer<SessionSignal> handler : list) {
                try {
                    handler.accept(signal);
                } catch (Exception | Error e) {
                    // Error too: this is the only thread that delivers signals to this node, and nothing restarts it.
                    log.warn("Signal handler threw for {} on node {}: {}", signal.getSessionId(), nodeId, e.toString());
                }
            }
        }
        resumeTokenStore.update(token);
    }

    private static void sleepShort() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepBackoff() {
        try {
            Thread.sleep(WATCHER_RESTART_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
