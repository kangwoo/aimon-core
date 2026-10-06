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
 * not require. A request published while a node's watcher is not attached (before its first {@link #subscribe}, or in
 * the gap after a resume token aged out of the oplog) is not delivered to that node; its run then stops where it would
 * have without a bus, at the next step boundary once the task is gone. A request whose reason this build does not
 * know — a newer node's, during a rolling upgrade — is still honoured (see {@link ScheduledTaskInterruptCodec}).
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

    private final MongoCollection<Document> collection;
    private final ScheduledTaskInterruptCodec codec = new ScheduledTaskInterruptCodec();
    private final String nodeId;
    private final ResumeTokenStore resumeTokenStore = new ResumeTokenStore();

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
        // remove(Object) drops the first occurrence, so closing one of two identical subscriptions leaves the other.
        return () -> listeners.remove(listener);
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
                log.warn("Change stream history lost for node {} ({}); reopening without resume token — stop requests"
                        + " published in the gap are not delivered here", nodeId, e.toString());
                resumeTokenStore.clear();
                sleep(WATCHER_RESTART_BACKOFF_MS);
            } catch (MongoInterruptedException e) {
                if (closed.get()) {
                    return;
                }
                log.debug("Mongo cursor interrupted on node {} (close pending)", nodeId);
            } catch (RuntimeException e) {
                if (closed.get()) {
                    return;
                }
                log.warn("Stop-request watcher hit an error on node {}; retrying: {}", nodeId, e.toString());
                sleep(WATCHER_RESTART_BACKOFF_MS);
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
                } catch (RuntimeException e) {
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
