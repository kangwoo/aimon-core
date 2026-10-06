package at.aimon.session.mongodb;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;
import at.aimon.core.scheduling.exception.SchedulingException;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.mongodb.internal.ResumeTokenStore;
import at.aimon.session.mongodb.internal.ScheduledTaskInterruptCodec;

/**
 * MongoDB Change Streams-backed {@link ScheduledTaskInterruptBus}: a stop request entered on one node reaches the
 * node that is actually running the routine.
 *
 * <p>
 * The same mechanism as {@link MongoSessionSignalBus}, on a collection of its own. A request is one {@code insertOne}
 * into the capped {@code scheduled_task_interrupts} collection; a single background watcher per bus instance holds one
 * change-stream cursor over inserts and hands each request to every listener registered on this instance. There is no
 * routing step — every node hears every request and its {@code RoutineExecutor} decides whether it holds a run of
 * that task, which is the fan-out the SPI describes.
 *
 * <p>
 * It does not share the session signal collection because the join key is a {@code ScheduledTaskId} and a scheduled
 * routine has no session; see the SPI's javadoc for why that distinction is kept.
 *
 * <h2>Prerequisites</h2>
 *
 * <p>
 * A replica set (Change Streams need one — a single-node {@code rs.initiate()} is enough), and the collection created
 * capped by {@code db/mongodb/init.js} <b>before the first publish</b>. The runtime never runs DDL: publishing into a
 * collection that does not exist makes Mongo create an ordinary, uncapped one, which works but grows by one small
 * document per stop request for ever.
 *
 * <h2>Delivery</h2>
 *
 * <p>
 * At-least-once to nodes that are watching, best-effort overall. The publishing node's own requests are filtered out
 * by origin — the caller has already interrupted its local runs before it publishes — which the SPI permits but does
 * not require. A request whose reason this build does not know — a newer node's, during a rolling upgrade — is still
 * honoured (see {@link ScheduledTaskInterruptCodec}).
 *
 * <p>
 * A request published while a node's watcher has no cursor and no usable resume token is not delivered to that node,
 * and nothing redelivers it. There are two such windows: before the cursor opened after the first {@link #subscribe},
 * which logs nothing; and while the watcher starts over because the server refused its resume token (older than the
 * oplog) or closed its cursor (the collection was dropped or recreated), which is logged at WARN. Any other
 * interruption — a killed operation, a step-down, an outage the oplog still covers — keeps the token, and the watcher
 * resumes and replays what it missed. <b>A run whose node missed the request is not stopped at all</b> — it runs its
 * remaining steps, and only its write-back is suppressed if the task was deleted. That is the behaviour without a
 * bus, which is what best-effort falls back to.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * The watcher thread starts on the first {@link #subscribe} and {@link #subscribe} returns without waiting for its
 * cursor to open. {@link #close()} stops the thread; the {@link MongoDatabase} belongs to the caller and is not
 * closed. This bus is application-scoped, like the {@code SchedulingEngine} it is given to.
 */
public final class MongoScheduledTaskInterruptBus implements ScheduledTaskInterruptBus, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoScheduledTaskInterruptBus.class);

    private static final long WATCHER_RESTART_BACKOFF_MS = 500L;
    private static final long IDLE_POLL_MS = 50L;

    /** The label the server puts on an error that means the change stream cannot be resumed from the given token. */
    private static final String NON_RESUMABLE_CHANGE_STREAM_ERROR = "NonResumableChangeStreamError";

    /** Server error code {@code InvalidResumeToken}. */
    private static final int INVALID_RESUME_TOKEN = 260;

    private final MongoCollection<Document> collection;
    private final ScheduledTaskInterruptCodec codec = new ScheduledTaskInterruptCodec();
    private final String nodeId;
    private final ResumeTokenStore resumeTokenStore;

    /** Copy-on-write for the reason the in-memory bus gives: subscriptions are handles, not set members. */
    private final List<InterruptListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean watcherStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Thread watcherThread;
    private volatile MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor;

    /**
     * Creates a bus on the default collection.
     *
     * @param database
     *            the database holding the collection (must not be null; owned by the caller)
     * @param nodeId
     *            this node's id, unique in the fleet — two nodes sharing one would each drop the other's requests as
     *            their own (must not be null)
     */
    public MongoScheduledTaskInterruptBus(MongoDatabase database, String nodeId) {
        this(database, DocumentKeys.COLL_SCHEDULED_TASK_INTERRUPTS, nodeId);
    }

    /**
     * Creates a bus on a named collection.
     *
     * @param database
     *            the database holding the collection (must not be null; owned by the caller)
     * @param collectionName
     *            the capped collection to publish to and watch (must not be null)
     * @param nodeId
     *            this node's id, unique in the fleet (must not be null)
     */
    public MongoScheduledTaskInterruptBus(MongoDatabase database, String collectionName, String nodeId) {
        this(database, collectionName, nodeId, new ResumeTokenStore());
    }

    /** Test seam: lets a test start the watcher from a resume token of its choosing. */
    MongoScheduledTaskInterruptBus(MongoDatabase database, String collectionName, String nodeId,
            ResumeTokenStore resumeTokenStore) {
        this.resumeTokenStore = Objects.requireNonNull(resumeTokenStore, "resumeTokenStore must not be null");
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
    }

    @Override
    public void publish(ScheduledTaskId taskId, InterruptReason reason) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");
        if (closed.get()) {
            throw new IllegalStateException("Bus is closed");
        }
        try {
            collection.insertOne(codec.encode(taskId, reason, nodeId));
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error publishing stop request for task '" + taskId + "'", e);
        }
    }

    @Override
    public Subscription subscribe(InterruptListener listener) {
        Objects.requireNonNull(listener, "Listener cannot be null");
        if (closed.get()) {
            throw new IllegalStateException("Bus is closed");
        }
        listeners.add(listener);
        ensureWatcherStarted();
        // remove(Object) drops the first occurrence, so closing one of two identical subscriptions leaves the other —
        // but only if each handle removes once. Unguarded, a second close of this handle would take its twin.
        final AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (open.compareAndSet(true, false)) {
                listeners.remove(listener);
            }
        };
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        listeners.clear();
        final MongoChangeStreamCursor<ChangeStreamDocument<Document>> active = cursor;
        if (active != null) {
            try {
                active.close();
            } catch (RuntimeException e) {
                log.debug("Ignoring cursor close failure during bus shutdown on node {}: {}", nodeId, e.toString());
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

    /**
     * @return whether a publish made now would be seen by this bus's listeners — true once the watcher's cursor is
     *         open, and trivially true before any {@link #subscribe}, when there is nobody to miss it
     */
    boolean isWatching() {
        return !watcherStarted.get() || cursor != null;
    }

    /** @return whether the watcher thread has been started and has not ended */
    boolean isWatcherAlive() {
        final Thread t = watcherThread;
        return t != null && t.isAlive();
    }

    private void ensureWatcherStarted() {
        if (!watcherStarted.compareAndSet(false, true)) {
            return;
        }
        final Thread t = new Thread(this::runWatcher, "mongo-task-interrupt-bus-" + nodeId);
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
                log.warn("Stop-request change stream on node {} produced an event without a resume token ({});"
                        + " reopening without one", nodeId, e.toString());
                resumeTokenStore.clear();
                sleep(WATCHER_RESTART_BACKOFF_MS);
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
                            "Stop-request watcher on node {} could not resume from its stored token ({}); starting"
                                    + " over without it — stop requests published in the gap are not delivered here",
                            nodeId, e.toString());
                    resumeTokenStore.clear();
                } else {
                    // Any other command error — a killed operation, a step-down, an authorization failure — says
                    // nothing about the token. It is kept, so the reopen resumes and replays what was missed. Dropping
                    // it here would turn every transient server error into lost stop requests.
                    log.warn("Stop-request watcher hit a server error on node {}; resuming: {}", nodeId, e.toString());
                }
                sleep(WATCHER_RESTART_BACKOFF_MS);
            } catch (RuntimeException e) {
                if (closed.get()) {
                    return;
                }
                log.warn("Stop-request watcher hit an error on node {}; retrying: {}", nodeId, e.toString());
                sleep(WATCHER_RESTART_BACKOFF_MS);
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
                        log.warn("Stop-request change stream on node {} was closed by the server (collection dropped"
                                + " or recreated?); reopening — stop requests published in the gap are not delivered"
                                + " here", nodeId);
                        resumeTokenStore.clear();
                        sleep(WATCHER_RESTART_BACKOFF_MS);
                        return;
                    }
                    // Keep the token moving while nothing is published. Stop requests are rare, and a token that only
                    // advanced on delivery would be days old — past the oplog — by the time a reconnect needed it.
                    resumeTokenStore.update(active.getResumeToken());
                    sleep(IDLE_POLL_MS);
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
        try {
            final Document full = change.getFullDocument();
            if (full == null) {
                return;
            }
            final ScheduledTaskInterruptCodec.Decoded request;
            try {
                request = codec.decode(full);
            } catch (RuntimeException e) {
                log.warn("Dropping an unreadable stop request on node {} ({}): {}", nodeId, change.getDocumentKey(),
                        e.toString());
                return;
            }
            if (nodeId.equals(request.getOriginNodeId())) {
                // This node's own request: the caller interrupted its local runs before publishing.
                return;
            }
            for (InterruptListener listener : listeners) {
                try {
                    listener.onInterruptRequested(request.getTaskId(), request.getReason());
                } catch (RuntimeException | Error e) {
                    // Error too: this is the only thread that will ever deliver a stop request to this node, and
                    // nothing restarts it. A listener's AssertionError or LinkageError must not end it.
                    log.warn("Interrupt listener failed for task '{}' ({}) on node {}; continuing the fan-out",
                            request.getTaskId(), request.getReason(), nodeId, e);
                }
            }
        } finally {
            // Advanced whatever happened to the event: a request that could not be read or handled once will not
            // read or handle better on redelivery, and holding the token back would replay it on every reconnect.
            resumeTokenStore.update(token);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
