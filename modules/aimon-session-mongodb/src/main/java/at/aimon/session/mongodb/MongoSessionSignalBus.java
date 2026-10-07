package at.aimon.session.mongodb;

import java.util.ArrayList;
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

import com.mongodb.MongoBulkWriteException;
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
import com.mongodb.client.model.InsertManyOptions;
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
 * Publishes signals as documents inserted into the capped {@code conversation_signals} collection: {@link #publish}
 * with one {@code insertOne}, {@link #publishAll} with one ordered {@code insertMany} for the whole list, which is what
 * keeps a turn's event stream from costing a round trip per text delta. A single
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
 * primary fail-over or a transient error) the watcher resumes from the last token, and replays what it missed. If the
 * server refuses that token — it has aged past the oplog window ({@code ChangeStreamHistoryLost}), or the stream no
 * longer contains it — the refusal arrives as a {@code MongoCommandException} carrying the
 * {@code NonResumableChangeStreamError} label; the watcher logs a warning, clears the token, and reopens without
 * resume. Signals published in that gap are lost: delivery on this bus is best-effort. Any other command error keeps
 * the token.
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

    /** The label the server puts on an error that means the change stream cannot be resumed from the given token. */
    private static final String NON_RESUMABLE_CHANGE_STREAM_ERROR = "NonResumableChangeStreamError";

    /** Server error code {@code InvalidResumeToken}. */
    private static final int INVALID_RESUME_TOKEN = 260;

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

    /**
     * Publishes the list as one ordered {@code insertMany}: one round trip rather than one per signal, and the oplog —
     * hence every change stream — sees the documents in list order.
     *
     * <p>
     * A single unpublishable signal costs itself and not the tail of the batch — the tail is where a turn's terminal
     * frame is. An ordered insert stops at the first document the server refuses; that document is skipped and the
     * insert resumes with the ones after it. A document the driver refuses before sending (an oversized payload) fails
     * the call without naming the document, and the batch is then inserted one document at a time.
     */
    @Override
    public void publishAll(List<SessionSignal> signals) {
        Objects.requireNonNull(signals, "signals must not be null");
        if (closed.get()) {
            throw new IllegalStateException("Bus is closed");
        }
        if (signals.isEmpty()) {
            return;
        }
        final InsertManyOptions ordered = new InsertManyOptions().ordered(true);
        final List<Document> docs = new ArrayList<>(signals.size());
        RuntimeException failure = null;
        for (SessionSignal signal : signals) {
            try {
                docs.add(codec.encode(Objects.requireNonNull(signal, "signal must not be null")));
            } catch (RuntimeException e) {
                failure = keepFirst(failure, e);
            }
        }
        int from = 0;
        while (from < docs.size()) {
            try {
                collection.insertMany(docs.subList(from, docs.size()), ordered);
                from = docs.size();
            } catch (MongoBulkWriteException e) {
                if (e.getWriteErrors().isEmpty()) {
                    // A write-concern error: the documents were written, only the acknowledgement fell short.
                    failure = keepFirst(failure, e);
                    from = docs.size();
                } else {
                    // Ordered, so there is exactly one write error and everything before it went in.
                    failure = keepFirst(failure, e);
                    from += e.getWriteErrors().get(0).getIndex() + 1;
                }
            } catch (MongoException e) {
                // The transport, not a document: nothing after this would fare better.
                final SessionSignalBusException fatal = new SessionSignalBusException(
                        "Mongo error publishing " + (docs.size() - from) + " of " + signals.size() + " signals", e);
                if (failure != null) {
                    fatal.addSuppressed(failure);
                }
                throw fatal;
            } catch (RuntimeException e) {
                // Not the server and not the transport: the driver refused to encode a document — one past the 16 MB
                // limit is turned away here, before anything is sent. It does not say which, so the rest go one at a
                // time and the one at fault fails alone.
                failure = keepFirst(failure, insertEach(docs.subList(from, docs.size())));
                from = docs.size();
            }
        }
        if (failure != null) {
            throw new SessionSignalBusException("Mongo could not publish every one of " + signals.size() + " signals",
                    failure);
        }
    }

    /**
     * Inserts each document on its own, in order, and returns the first failure with the later ones suppressed — or
     * {@code null} when every insert succeeded.
     */
    private RuntimeException insertEach(List<Document> docs) {
        RuntimeException failure = null;
        for (Document doc : docs) {
            try {
                collection.insertOne(doc);
            } catch (RuntimeException e) {
                failure = keepFirst(failure, e);
            }
        }
        return failure;
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
                // The driver's own complaint about an event it cannot take a resume token from. It is not how a
                // history-lost token arrives (that is the labelled command error below), but the remedy is the same.
                if (closed.get()) {
                    return;
                }
                log.warn("Change stream on node {} produced an event without a resume token ({}); reopening without"
                        + " one", nodeId, e.toString());
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
                if (resumeTokenStore.last().isPresent() && isResumeRefused(e)) {
                    // The server says this stream cannot be resumed from the stored token: it is older than the oplog
                    // (ChangeStreamHistoryLost), or the stream no longer contains it. Retrying the same token cannot
                    // succeed, so it is dropped and the stream starts over from now.
                    log.warn(
                            "Change stream watcher on node {} could not resume from its stored token ({}); starting"
                                    + " over without it — signals published in the gap are not delivered here",
                            nodeId, e.toString());
                    resumeTokenStore.clear();
                } else {
                    // Any other command error — a killed operation, a step-down, an authorization failure — says
                    // nothing about the token. It is kept, so the reopen resumes and replays what was missed. Dropping
                    // it here would turn every transient server error into lost signals.
                    log.warn("Change stream watcher hit a server error on node {}; resuming: {}", nodeId, e.toString());
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

    /**
     * Whether a command error means "this stream cannot be resumed from that token", as opposed to any other failure
     * of a command. The server labels the former; 260 is what a resume from an invalidate token returns.
     */
    private static boolean isResumeRefused(MongoCommandException e) {
        return e.hasErrorLabel(NON_RESUMABLE_CHANGE_STREAM_ERROR) || e.getErrorCode() == INVALID_RESUME_TOKEN;
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
