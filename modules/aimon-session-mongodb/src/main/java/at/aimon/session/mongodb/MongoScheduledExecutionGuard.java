package at.aimon.session.mongodb;

import java.time.Clock;
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
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

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
 * Two steps, for the reason {@link MongoSessionLeaseStore} gives: MongoDB refuses {@code $expr} / {@code $$NOW} in an
 * upsert's filter.
 *
 * <ol>
 * <li>{@code findOneAndUpdate} without upsert: the lease has expired <em>and</em> {@code lastSlot} is before this
 * fire's
 * slot. Both conditions and the write are one server-side operation, so a refused fire changes nothing and a granted
 * one
 * moved {@code lastSlot} atomically. Time is the server's {@code $$NOW}, so clock skew between nodes decides nothing.
 * <li>No match means either there is no document yet or the fire was refused. {@code insertOne} tells the two apart
 * with
 * no extra read: it succeeds only when there was no document, and {@code DUPLICATE_KEY} means there was, so the fire
 * was
 * refused. Losing a concurrent insert is the same answer. The cold-start {@code leaseExpiresAt} uses the application
 * clock, as in {@link MongoSessionLeaseStore}, and the first heartbeat restamps it in server time. Until then, on a
 * task's very first fire, a clock further behind the server than the lease lets a newer fire take the lease.
 * </ol>
 *
 * <p>
 * Slots are compared by size, not by equality: a misfire older than an occurrence that already began is refused as
 * well,
 * so {@code lastSlot} never moves backwards. A slot refused for overlap does not advance it either, and is not retried:
 * the occurrence is skipped, and the next one starts as soon as the lease is free. Slots come from the nodes' own
 * clocks, so keep them synchronized: a node whose clock runs a day fast records a slot a day ahead, and until real time
 * passes it every other node's fire of that task is refused as old. Delete the document to recover.
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
 * lost in a failover forgets the slot it recorded. The collection comes from {@code db/mongodb/init.js}, though unlike
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

    private static final long BEATS_PER_LEASE = 3L;

    /** Compares older than every slot, for a document no slotted fire has written to. */
    private static final Date BEFORE_ANY_SLOT = new Date(Long.MIN_VALUE);

    private final MongoCollection<Document> collection;
    private final String nodeId;
    private final Duration lease;
    private final Clock clock;

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
        this(database, DocumentKeys.COLL_SCHEDULED_EXECUTION_GUARDS, nodeId, DEFAULT_LEASE, Clock.systemUTC());
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
     * @param clock
     *            stamps the first lease of a task only; every later decision uses server time (must not be null)
     */
    public MongoScheduledExecutionGuard(MongoDatabase database, String collectionName, String nodeId, Duration lease,
            Clock clock) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.lease = Objects.requireNonNull(lease, "lease must not be null");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
        if (!claimExisting(taskId, holderId, slot) && !claimAbsent(taskId, holderId, slot)) {
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

    private boolean claimExisting(ScheduledTaskId taskId, String holderId, Date slot) {
        final List<Document> conditions = new ArrayList<>();
        conditions.add(new Document("$lte", List.of("$" + DocumentKeys.F_LEASE_EXPIRES_AT, "$$NOW")));
        // The holder is $literal because a pipeline $set evaluates its values: a node id beginning with "$" would be
        // read as a field path, and renew and release, which match on the id they were given, would never find it.
        final Document set = new Document(DocumentKeys.F_HOLDER_ID, new Document("$literal", holderId))
                .append(DocumentKeys.F_LEASE_EXPIRES_AT, new Document("$add", List.of("$$NOW", lease.toMillis())));
        if (slot != null) {
            conditions.add(new Document("$lt",
                    List.of(new Document("$ifNull", List.of("$" + DocumentKeys.F_LAST_SLOT, BEFORE_ANY_SLOT)), slot)));
            set.append(DocumentKeys.F_LAST_SLOT, slot);
        }
        final Document filter = new Document(DocumentKeys.F_ID, taskId.value()).append("$expr",
                new Document("$and", conditions));
        try {
            return collection.findOneAndUpdate(filter, List.of(new Document("$set", set))) != null;
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error claiming the guard of task '" + taskId + "'", e);
        }
    }

    private boolean claimAbsent(ScheduledTaskId taskId, String holderId, Date slot) {
        final Date expiresAt = new Date(clock.millis() + lease.toMillis());
        final Document fresh = new Document(DocumentKeys.F_ID, taskId.value())
                .append(DocumentKeys.F_HOLDER_ID, holderId).append(DocumentKeys.F_LEASE_EXPIRES_AT, expiresAt);
        if (slot != null) {
            fresh.append(DocumentKeys.F_LAST_SLOT, slot);
        }
        try {
            collection.insertOne(fresh);
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw new SchedulingException("Mongo error creating the guard of task '" + taskId + "'", e);
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error creating the guard of task '" + taskId + "'", e);
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
                            log.warn("Lost the lease on task '{}': another node holds it, so its run may overlap this"
                                    + " one, which keeps going", taskId);
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
