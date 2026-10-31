package at.aimon.session.mongodb;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;

import at.aimon.core.scheduling.ScheduledExecutionGuard;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.SchedulingException;
import at.aimon.core.scheduling.scheduler.ScheduledFire;
import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * MongoDB-backed {@link ScheduledExecutionGuard}: of all the nodes firing a task, one runs each occurrence, and no run
 * overlaps another.
 *
 * <p>
 * One document per task stops both hazards with one write:
 *
 * <pre>{@code
 * {
 * "_id": "<taskId>",
 * "holderId": "<nodeId>/<uuid>", // null once released
 * "leaseExpiresAt": ISODate, // overlap: nobody begins while this is in the future
 * "lastSlot": ISODate // duplication: no fire at or before this begins; absent until a slotted fire
 * }
 * }</pre>
 *
 * <h2>Granting a fire</h2>
 *
 * <p>
 * One {@code findOneAndUpdate} with upsert, whose filter is only the {@code _id}. A fire is granted when the lease has
 * expired, its slot is no further ahead of the server than {@code maxSkew}, and {@code lastSlot} is before its slot.
 * All
 * three are judged by {@code $cond} inside the update pipeline: MongoDB refuses {@code $expr} / {@code $$NOW} in an
 * upsert's <em>filter</em>, which is why {@link MongoSessionLeaseStore} takes two steps, but not in its pipeline. That
 * is
 * a measured fact, on 4.2, 4.4 and 6.0. So there is one round trip whether the document exists or not, and every time
 * is
 * the server's {@code $$NOW}: no application clock stamps anything, and the first fire of a task meets the same rules
 * as
 * every later one.
 *
 * <p>
 * The update returns the document as it left it, and the fire was granted if that names this lease as the holder. A
 * refused fire changes nothing, with one harmless exception: a first fire refused for a slot too far ahead leaves a
 * bare
 * {@code {_id}}, which the next fire treats as no lease and no slot. Two first fires can race on the upsert's insert;
 * the
 * loser gets a duplicate key and is retried once, when the document exists.
 *
 * <p>
 * Slots are compared by size, not by equality: a misfire older than an occurrence that already began is refused as
 * well, so {@code lastSlot} never moves backwards. A slot refused for overlap does not advance it either, and is not
 * retried: the occurrence is skipped, and the next one starts as soon as the lease is free.
 *
 * <h2>Slots ahead of the server</h2>
 *
 * <p>
 * Slots come from the nodes' own clocks, and {@code lastSlot} only ever moves forward, so one slot from a node whose
 * clock ran a day fast would refuse every node's fires for a day, even once that clock was fixed. So a slot further
 * ahead
 * of the server than {@code maxSkew} ({@link #DEFAULT_MAX_SKEW} unless configured) is refused and recorded nowhere.
 * That
 * node's fires of the task are all refused, other nodes' fires still run, and a WARN names the cause. The manager's own
 * log says only that the guard refused, as it does for a normal duplicate. Finding the cause takes one more read, on
 * the
 * refusal path only.
 *
 * <p>
 * A document that already holds a {@code lastSlot} past the bound is <b>not repaired</b>. Under this check nothing
 * writes
 * one, so it means a hand edit, a lowered {@code maxSkew}, or a server clock that moved back. Overwriting it could run
 * an
 * occurrence twice: that value replaced the real last slot, so nothing still knows whether the fire in hand already
 * ran.
 * Each refusal logs a WARN naming the slot instead, and fires stay refused until the clock reaches it or an operator
 * who has weighed that risk deletes the document.
 *
 * <p>
 * A fire with no {@link ScheduledFire#getSlot() slot}, and {@link #tryBegin(ScheduledTaskId)}, have no occurrence to
 * compare: they check the lease alone and leave {@code lastSlot} as it is. A document a slot-less fire created has no
 * {@code lastSlot}, and the next slotted fire treats that as older than any slot.
 *
 * <h2>At most once</h2>
 *
 * <p>
 * The slot is recorded when the run <em>begins</em>. If the node then dies, that occurrence is not run again by any
 * node:
 * its work may be partly done, or not started at all. This is deliberate. A routine may write, and repeating an unknown
 * amount of it is worse than missing one occurrence, which the next one usually covers. The same goes for an outage:
 * {@code tryBegin} throws, and {@code ScheduledTaskManager} skips that fire rather than run it unguarded. An error from
 * Mongo arrives as {@link SchedulingException}; a {@code MongoClient} already closed throws its own
 * {@link IllegalStateException}, which is not wrapped. If the write landed and only its acknowledgement was lost, the
 * slot is spent and
 * the lease expires on its own, since no heartbeat is running.
 *
 * <h2>Lease and heartbeat</h2>
 *
 * <p>
 * A run has no bound on its length and {@link ExecutionLease} has no way to report progress, so the guard renews the
 * lease itself: every granted lease has a daemon thread that restamps {@code leaseExpiresAt} every third of the lease
 * duration, and that ends when the lease is closed. A long fixed lease would be simpler, and a dead node would then
 * hold
 * its tasks for that long; with the heartbeat a dead node holds them for one lease duration at most. One thread per
 * lease
 * rather than one shared by the guard because a renewal can block on a sick server for as long as the driver's timeouts
 * allow, and one task's renewal must not make another's late.
 *
 * <p>
 * A failed renewal is logged and retried at the next beat, which allows two misses before the lease lapses. A renewal
 * that finds another holder has lost the lease — the node was cut off for longer than a lease and another node began —
 * and it is logged and stops. <b>The run itself keeps going</b>, because this SPI has no way to stop it, so a cut-off
 * longer than the lease can overlap two runs. Renewal only requires still being the holder, so a lease that lapsed
 * without anyone taking it is simply picked up again.
 *
 * <h2>Release</h2>
 *
 * <p>
 * Closing a lease clears {@code holderId} and stamps {@code leaseExpiresAt} with {@code $$NOW}, only while this lease
 * is
 * still the holder, so a lease that lapsed cannot release its successor's. The holder id is unique per lease for that
 * reason: a node's id alone would let a node's old lease release its own new one. Clearing it also means a renewal
 * already on the wire when the lease closes matches nothing and cannot extend a released lease. A failed release is
 * logged and never reaches the caller; the lease expires on its own.
 *
 * <p>
 * The document stays, because {@code lastSlot} is what refuses the late duplicate. <b>Do not add a TTL index on this
 * collection</b> — expiring a document forgets the slot and lets an occurrence run twice. Documents are small and there
 * is one per task that ever fired; nothing removes one when its task is cancelled, since the late fire of a cancelled
 * task's last occurrence is exactly what it still has to refuse. An operator may delete those of tasks long gone.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * Application-scoped, like the {@code SchedulingEngine} it is given to, and owned by whoever built it: neither the
 * engine
 * nor an agent runtime closes it. Close the engine first, then this, then the {@code MongoClient}. {@link #close()}
 * stops
 * the heartbeats of the leases still open and refuses further fires; it does not release those leases, because their
 * runs may still be going, so they expire by themselves. A host that never calls it leaks nothing past the last lease,
 * as
 * each heartbeat ends with its lease and is a daemon. The {@link MongoDatabase} belongs to the caller.
 *
 * <h2>Prerequisites</h2>
 *
 * <p>
 * MongoDB 4.2 or later, for pipeline updates and {@code $$NOW}. Use {@code w=majority} on the URI: an acknowledged
 * write
 * lost in a failover forgets the slot it recorded. Set {@code socketTimeoutMS} well below the lease: the driver's
 * default is
 * no timeout, and against a server paused mid-call an {@code updateOne} was measured still blocked after 60 seconds, so
 * a
 * renewal would not fail and retry while the lease lapsed, and {@code tryBegin} or a release would hold its caller.
 * With
 * 2 seconds the same call failed after 6, as the driver retries a retryable write, so allow for several times the
 * value.
 * It applies to the whole client; if other work on that client cannot take it, give the guard its own. The collection
 * comes from {@code db/mongodb/init.js}, though unlike
 * the capped bus channels nothing goes wrong if the first write creates it. Share the collection with one application
 * only; the task id is all a document knows.
 *
 * <p>
 * On per-node schedulers, every node must compute the same slot, so give each task a {@code ScheduledTask.timezone} or
 * run every node in the same time zone. A task without one fires on the grid of the node's JVM zone: nodes in different
 * zones then record different slots for what is meant to be one occurrence, each newer than the last, and it runs once
 * per zone.
 */
public final class MongoScheduledExecutionGuard implements ScheduledExecutionGuard, AutoCloseable {

    /** How long a lease lasts without a renewal, and so how long a dead node holds its tasks, unless configured. */
    public static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(MongoScheduledExecutionGuard.class);

    /** How far ahead of the server a fire's slot may be before it is refused, unless configured. */
    public static final Duration DEFAULT_MAX_SKEW = Duration.ofMinutes(5);

    private static final long BEATS_PER_LEASE = 3L;

    private static final String SLOT_AHEAD = "slotAhead";
    private static final String LAST_SLOT_AHEAD = "lastSlotAhead";

    /** Compares older than every slot and lease, for a document that has neither yet. */
    private static final Date BEFORE_EVERYTHING = new Date(Long.MIN_VALUE);

    private final MongoCollection<Document> collection;
    private final String nodeId;
    private final Duration lease;
    private final Duration maxSkew;
    private final String collectionName;

    private final Set<HeldLease> held = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a guard on the default collection with the {@link #DEFAULT_LEASE default lease}.
     *
     * @param database
     *            the database holding the collection (must not be null; owned by the caller)
     * @param nodeId
     *            this node's id, used to name lease holders for operators (must not be null)
     */
    public MongoScheduledExecutionGuard(MongoDatabase database, String nodeId) {
        this(database, DocumentKeys.COLL_SCHEDULED_EXECUTION_GUARDS, nodeId, DEFAULT_LEASE, DEFAULT_MAX_SKEW);
    }

    /**
     * Creates a guard on a named collection.
     *
     * @param database
     *            the database holding the collection (must not be null; owned by the caller)
     * @param collectionName
     *            the collection holding the guard documents (must not be null)
     * @param nodeId
     *            this node's id, used to name lease holders for operators (must not be null)
     * @param lease
     *            how long a lease lasts without a renewal (must be positive). Renewed every third of this, so keep it
     *            well above how long a primary election takes, or a failover lets the next occurrence overlap the run
     * @param maxSkew
     *            how far ahead of the server a fire's slot may be (must be positive). Refused beyond it. Slots sit on a
     *            cron grid point at or before their node's clock, so this bounds how fast a clock may run; a value
     *            below
     *            the clock error between nodes and server refuses good fires
     */
    public MongoScheduledExecutionGuard(MongoDatabase database, String collectionName, String nodeId, Duration lease,
            Duration maxSkew) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.lease = Objects.requireNonNull(lease, "lease must not be null");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }
        this.maxSkew = Objects.requireNonNull(maxSkew, "maxSkew must not be null");
        if (maxSkew.isZero() || maxSkew.isNegative()) {
            throw new IllegalArgumentException("maxSkew must be positive: " + maxSkew);
        }
        this.collectionName = collectionName;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Checks the lease only and leaves {@code lastSlot} alone: there is no occurrence to record.
     *
     * @throws SchedulingException
     *             if Mongo reported an error; {@code ScheduledTaskManager} skips the fire
     * @throws IllegalStateException
     *             if the {@code MongoClient} is already closed; the manager skips this fire too
     */
    @Override
    public Optional<ExecutionLease> tryBegin(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return begin(taskId, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Grants the fire only if the lease is free and its slot is newer than the last one that began on any node. A fire
     * without a slot falls back to {@link #tryBegin(ScheduledTaskId)}.
     *
     * @throws SchedulingException
     *             if Mongo reported an error; {@code ScheduledTaskManager} skips the fire
     * @throws IllegalStateException
     *             if the {@code MongoClient} is already closed; the manager skips this fire too
     */
    @Override
    public Optional<ExecutionLease> tryBegin(ScheduledFire fire) {
        Objects.requireNonNull(fire, "fire must not be null");
        return begin(fire.getTaskId(), fire.getSlot().map(MongoScheduledExecutionGuard::toDate).orElse(null));
    }

    /**
     * Stops the heartbeats of the leases still open and refuses further fires. Idempotent. It releases nothing: see the
     * class javadoc.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            held.forEach(HeldLease::stopHeartbeat);
        }
    }

    private Optional<ExecutionLease> begin(ScheduledTaskId taskId, Date slot) {
        if (closed.get()) {
            log.debug("Guard is closed; refusing the fire of task '{}'", taskId);
            return Optional.empty();
        }
        final String holderId = nodeId + '/' + UUID.randomUUID();
        if (!holderId.equals(claim(taskId, holderId, slot).getString(DocumentKeys.F_HOLDER_ID))) {
            if (slot != null) {
                warnIfAheadOfServer(taskId, slot);
            }
            return Optional.empty();
        }
        final HeldLease granted = new HeldLease(taskId, holderId);
        held.add(granted);
        granted.startHeartbeat();
        // close() may have walked the set before this lease joined it.
        if (closed.get()) {
            granted.stopHeartbeat();
        }
        return Optional.of(granted);
    }

    private Document claim(ScheduledTaskId taskId, String holderId, Date slot) {
        final List<Document> conditions = new ArrayList<>();
        conditions.add(new Document("$lte", List.of(
                new Document("$ifNull", List.of("$" + DocumentKeys.F_LEASE_EXPIRES_AT, BEFORE_EVERYTHING)), "$$NOW")));
        if (slot != null) {
            conditions.add(new Document("$lte", List.of(slot, furthestAllowedSlot())));
            conditions.add(new Document("$lt", List
                    .of(new Document("$ifNull", List.of("$" + DocumentKeys.F_LAST_SLOT, BEFORE_EVERYTHING)), slot)));
        }
        final Document granted = new Document("$and", conditions);
        // The holder is $literal because a pipeline $set evaluates its values: a node id beginning with "$" would be
        // read as a field path, and renew and release, which match on the id they were given, would never find it.
        final Document set = new Document(DocumentKeys.F_HOLDER_ID,
                ifGranted(granted, new Document("$literal", holderId), DocumentKeys.F_HOLDER_ID))
                .append(DocumentKeys.F_LEASE_EXPIRES_AT, ifGranted(granted,
                        new Document("$add", List.of("$$NOW", lease.toMillis())), DocumentKeys.F_LEASE_EXPIRES_AT));
        if (slot != null) {
            set.append(DocumentKeys.F_LAST_SLOT, ifGranted(granted, slot, DocumentKeys.F_LAST_SLOT));
        }
        final Document filter = new Document(DocumentKeys.F_ID, taskId.value());
        final List<Document> update = List.of(new Document("$set", set));
        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().upsert(true)
                .returnDocument(ReturnDocument.AFTER);
        try {
            try {
                return collection.findOneAndUpdate(filter, update, options);
            } catch (MongoException e) {
                if (ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY) {
                    throw e;
                }
                // Two first fires raced on the upsert's insert. The document exists now, so this cannot insert again.
                return collection.findOneAndUpdate(filter, update, options);
            }
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error claiming the guard of task '" + taskId + "'", e);
        }
    }

    /** A field keeps its value (or stays absent, for a document being inserted) unless the fire is granted. */
    private static Document ifGranted(Document granted, Object value, String field) {
        return new Document("$cond", List.of(granted, value, "$" + field));
    }

    private Document furthestAllowedSlot() {
        return new Document("$add", List.of("$$NOW", maxSkew.toMillis()));
    }

    /**
     * Tells a refusal caused by a clock from an ordinary one, which is what the extra read on this path is for. Nothing
     * about the refusal depends on it, so its own failure is only logged.
     */
    private void warnIfAheadOfServer(ScheduledTaskId taskId, Date slot) {
        final Document project = new Document(DocumentKeys.F_LAST_SLOT, 1)
                .append(SLOT_AHEAD, new Document("$gt", List.of(slot, furthestAllowedSlot()))).append(LAST_SLOT_AHEAD,
                        new Document("$gt", List.of("$" + DocumentKeys.F_LAST_SLOT, furthestAllowedSlot())));
        final List<Document> pipeline = List.of(new Document("$match", new Document(DocumentKeys.F_ID, taskId.value())),
                new Document("$project", project));
        try {
            final Document found = collection.aggregate(pipeline).first();
            if (found == null) {
                return;
            }
            if (found.getBoolean(SLOT_AHEAD, false)) {
                log.warn("Refused the fire of task '{}': its slot {} is more than {} ahead of the server's clock."
                        + " Nothing was recorded. Check this node's clock.", taskId, slot.toInstant(), maxSkew);
            }
            if (found.getBoolean(LAST_SLOT_AHEAD, false)) {
                log.warn("Refused the fire of task '{}': the recorded last slot {} is more than {} ahead of the"
                        + " server's clock, so every fire is refused until the clock reaches it. Deleting the task's"
                        + " document in '{}' ends that, and forgets which occurrences ran: a late fire of a recent one"
                        + " could run again.", taskId, found.getDate(DocumentKeys.F_LAST_SLOT).toInstant(), maxSkew,
                        collectionName);
            }
        } catch (RuntimeException e) {
            log.debug("Could not tell why the fire of task '{}' was refused: {}", taskId, e.toString());
        }
    }

    private static Date toDate(Instant slot) {
        return new Date(slot.toEpochMilli());
    }

    /** One granted lease and its heartbeat. */
    private final class HeldLease implements ExecutionLease {

        private final ScheduledTaskId taskId;
        private final String holderId;
        private final AtomicBoolean released = new AtomicBoolean(false);
        /** A latch rather than an interrupt: interrupting the driver mid-call would cost it the connection. */
        private final CountDownLatch stop = new CountDownLatch(1);

        private HeldLease(ScheduledTaskId taskId, String holderId) {
            this.taskId = taskId;
            this.holderId = holderId;
        }

        private void startHeartbeat() {
            final Thread thread = new Thread(this::beat, "mongo-execution-guard-heartbeat-" + taskId);
            thread.setDaemon(true);
            thread.start();
        }

        private void stopHeartbeat() {
            stop.countDown();
        }

        private void beat() {
            final long interval = Math.max(1L, lease.toMillis() / BEATS_PER_LEASE);
            try {
                while (!stop.await(interval, TimeUnit.MILLISECONDS)) {
                    if (!renewOrLogFailure()) {
                        // close() stops this beat before it clears the holder, so a renewal that matched nothing after
                        // being stopped ran into its own release: nothing was lost.
                        if (stop.getCount() > 0) {
                            log.warn("Lost the lease on task '{}': it is no longer ours, so another run may overlap"
                                    + " this one, which keeps going", taskId);
                        }
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Returns {@code false} only when the lease is known to be lost. RuntimeException and not MongoException,
         * because a MongoClient already closed throws IllegalStateException.
         */
        private boolean renewOrLogFailure() {
            final Document set = new Document("$set", new Document(DocumentKeys.F_LEASE_EXPIRES_AT,
                    new Document("$add", List.of("$$NOW", lease.toMillis()))));
            try {
                return collection.updateOne(heldByThisLease(), List.of(set)).getMatchedCount() > 0;
            } catch (RuntimeException e) {
                log.warn("Could not renew the lease on task '{}'; retrying at the next beat: {}", taskId, e.toString());
                return true;
            }
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            stopHeartbeat();
            held.remove(this);
            final Document set = new Document("$set",
                    new Document(DocumentKeys.F_HOLDER_ID, null).append(DocumentKeys.F_LEASE_EXPIRES_AT, "$$NOW"));
            try {
                collection.updateOne(heldByThisLease(), List.of(set));
            } catch (RuntimeException e) {
                log.warn("Could not release the lease on task '{}'; it expires within {}: {}", taskId, lease,
                        e.toString());
            }
        }

        private Document heldByThisLease() {
            return new Document(DocumentKeys.F_ID, taskId.value()).append(DocumentKeys.F_HOLDER_ID, holderId);
        }
    }
}
