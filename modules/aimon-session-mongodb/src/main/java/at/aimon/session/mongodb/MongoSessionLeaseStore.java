package at.aimon.session.mongodb;

import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLeaseException;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * MongoDB-backed {@link SessionLeaseStore} per design §4.1.
 *
 * <p>
 * Maps {@code tryAcquire} / {@code extend} / {@code release} onto {@code findOneAndUpdate} and {@code insertOne} calls
 * against the {@code conversation_locks} collection. Steal-on-expiry, extend, and release all evaluate time server-side
 * via {@code $$NOW} so application-clock skew between manager nodes never affects fencing decisions.
 *
 * <h2>Document layout</h2>
 *
 * <pre>{@code
 * {
 *   "_id":            "<conversationId>",
 *   "holderId":       "<node>/<thread>/<turnSeq>",
 *   "fencingToken":   <long>,
 *   "leaseExpiresAt": ISODate,
 *   "acquiredAt":     ISODate
 * }
 * }</pre>
 *
 * <h2>Why {@link #tryAcquire} is two-step</h2>
 *
 * <p>
 * MongoDB rejects aggregation-expression filters (including {@code $expr} / {@code $$NOW}) when the operation has
 * {@code upsert: true} (server error 224 {@code QueryFeatureNotAllowed}, see SERVER-44708). The original design's
 * single-round-trip "upsert + {@code leaseExpiresAt $lte $$NOW} filter" is therefore not expressible in MongoDB. We
 * preserve the load-bearing invariant (server-side time evaluation for all fencing decisions) by splitting the path:
 *
 * <ol>
 * <li>{@code findOneAndUpdate} <em>without</em> upsert, filter {@code $expr: $lte($leaseExpiresAt, $$NOW)} — matches
 * existing-document-with-expired-lease (steal path). On match the update pipeline advances the counter.
 * <li>If step 1 returned null (no document yet), {@code insertOne} a fresh document with {@code fencingToken: 1}. A
 * concurrent loser sees DUPLICATE_KEY and returns empty. The cold-start {@code leaseExpiresAt} uses app-clock; this is
 * safe because there is no prior lease to compare against, and every subsequent steal/extend/release uses server time.
 * </ol>
 *
 * <h2>Fencing-token lifetime invariant</h2>
 *
 * <p>
 * {@code fencingToken} is strictly monotonic per {@code _id} for the whole lifetime of the session, matching the
 * {@link SessionLeaseStore} SPI contract and the postgres backend's separate {@code conversation_lock_fence} row.
 * {@link #release} therefore <em>expires the lease in place</em> — clearing {@code holderId} and stamping
 * {@code leaseExpiresAt} with {@code $$NOW} — rather than deleting the document. Deleting it would drop the counter, so
 * the next acquire would restart at 1 and a stale handle from a previous holder could pass a fencing comparison it must
 * fail.
 *
 * <p>
 * Consequence: lock documents outlive the lease and are never removed by this class, exactly as the postgres fence rows
 * are. Growth is bounded by the number of distinct sessions, and a released document is one small record. Do
 * <em>not</em> add a TTL index on this collection — expiring a document silently resets its counter and reintroduces
 * the defect described above. {@code deleteSession} is the only legitimate point at which a session's lock
 * document may go away, because the session id itself is retired with it.
 *
 * <p>
 * The class name predates the {@code ConversationLock} &rarr; {@code SessionLeaseStore} rename and is kept on
 * purpose: the {@code conversation_locks} collection and its field names are already deployed, and renaming the class
 * would suggest a document-shape change that is not happening.
 */
public final class MongoSessionLeaseStore implements SessionLeaseStore {

    private static final Logger log = LoggerFactory.getLogger(MongoSessionLeaseStore.class);

    private final MongoCollection<Document> collection;
    private final Clock clock;

    public MongoSessionLeaseStore(MongoDatabase database) {
        this(database, DocumentKeys.COLL_LOCKS, Clock.systemUTC());
    }

    public MongoSessionLeaseStore(MongoDatabase database, String collectionName, Clock clock) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<SessionLease> tryAcquire(SessionId id, String holderId, Duration lease) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }

        // Step 1 — steal-or-renew an existing document whose lease has already expired. No upsert, so $expr is allowed
        // here and time evaluation runs server-side against $$NOW. Update pipeline $set bumps fencingToken via $add +
        // $ifNull, sets the new holder, and stamps acquiredAt / leaseExpiresAt in cluster time.
        final Document stealFilter = new Document(DocumentKeys.F_ID, id.value()).append("$expr",
                new Document("$lte", List.of("$" + DocumentKeys.F_LEASE_EXPIRES_AT, "$$NOW")));
        final Document setStage = new Document("$set", new Document(DocumentKeys.F_HOLDER_ID, holderId)
                .append(DocumentKeys.F_FENCING_TOKEN,
                        new Document("$add",
                                List.of(new Document("$ifNull", List.of("$" + DocumentKeys.F_FENCING_TOKEN, 0L)), 1L)))
                .append(DocumentKeys.F_ACQUIRED_AT, "$$NOW")
                .append(DocumentKeys.F_LEASE_EXPIRES_AT, new Document("$add", List.of("$$NOW", lease.toMillis()))));

        final Document stolen;
        try {
            stolen = collection.findOneAndUpdate(stealFilter, List.of(setStage),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        } catch (MongoException e) {
            throw new SessionLeaseException("Mongo error during tryAcquire (steal step) for " + id, e);
        }
        if (stolen != null) {
            final long token = stolen.get(DocumentKeys.F_FENCING_TOKEN, Number.class).longValue();
            return Optional.of(lease(id, holderId, token, lease));
        }

        // Step 2 — cold-start: no document yet (or the document still holds a fresh lease). Try to insert a fresh
        // record. If another node beat us to it (or the existing doc still holds a fresh lease), we get DUPLICATE_KEY
        // and return empty. The cold-start leaseExpiresAt uses app-clock; safe because there is no prior lease to
        // compare against, and every subsequent decision runs against $$NOW.
        final Date now = Date.from(clock.instant());
        final Date expiresAt = new Date(now.getTime() + lease.toMillis());
        final Document fresh = new Document(DocumentKeys.F_ID, id.value()).append(DocumentKeys.F_HOLDER_ID, holderId)
                .append(DocumentKeys.F_FENCING_TOKEN, 1L).append(DocumentKeys.F_ACQUIRED_AT, now)
                .append(DocumentKeys.F_LEASE_EXPIRES_AT, expiresAt);
        try {
            collection.insertOne(fresh);
            return Optional.of(lease(id, holderId, 1L, lease));
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                // Another holder won — either by inserting first or because the existing doc still holds a fresh lease.
                return Optional.empty();
            }
            throw new SessionLeaseException("Mongo error during tryAcquire (insert step) for " + id, e);
        } catch (MongoException e) {
            throw new SessionLeaseException("Mongo error during tryAcquire (insert step) for " + id, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Liveness is evaluated server-side against {@code $$NOW}, the same basis as the steal filter in
     * {@link #tryAcquire}, so the two can never disagree about whether a lease has lapsed. {@code $expr} is permitted
     * here because this is a plain read — the restriction that forced {@code tryAcquire} into two steps applies only to
     * upserts. The {@code holderId != null} clause makes the intent explicit for a released document; the expiry
     * predicate alone would already exclude it, since {@link #release} stamps {@code leaseExpiresAt} with
     * {@code $$NOW}.
     */
    @Override
    public Optional<LeaseHolder> findHolder(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");

        final Document filter = new Document(DocumentKeys.F_ID, id.value())
                .append(DocumentKeys.F_HOLDER_ID, new Document("$ne", null))
                .append("$expr", new Document("$gt", List.of("$" + DocumentKeys.F_LEASE_EXPIRES_AT, "$$NOW")));
        try {
            final Document held = collection.find(filter).first();
            if (held == null) {
                return Optional.empty();
            }
            return Optional.of(LeaseHolder.builder().holderId(held.getString(DocumentKeys.F_HOLDER_ID))
                    .fencingToken(held.get(DocumentKeys.F_FENCING_TOKEN, Number.class).longValue())
                    .expiresAt(held.getDate(DocumentKeys.F_LEASE_EXPIRES_AT).toInstant()).build());
        } catch (MongoException e) {
            throw new SessionLeaseException("Mongo error during findHolder for " + id, e);
        }
    }

    @Override
    public boolean extend(SessionLease lease, Duration duration) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive: " + duration);
        }

        final Document filter = new Document(DocumentKeys.F_ID, lease.getSessionId().value())
                .append(DocumentKeys.F_HOLDER_ID, lease.getHolderId())
                .append(DocumentKeys.F_FENCING_TOKEN, lease.getFencingToken());
        final Document setStage = new Document("$set", new Document(DocumentKeys.F_LEASE_EXPIRES_AT,
                new Document("$add", List.of("$$NOW", duration.toMillis()))));
        try {
            final Document result = collection.findOneAndUpdate(filter, List.of(setStage),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            return result != null;
        } catch (MongoException e) {
            throw new SessionLeaseException("Mongo error during extend for " + lease.getSessionId(), e);
        }
    }

    @Override
    public void release(SessionLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        // Expire the lease in place instead of deleting the document: the fencing counter lives in this record and must
        // keep advancing across release/acquire cycles. Setting leaseExpiresAt to $$NOW makes tryAcquire's steal filter
        // ($lte leaseExpiresAt $$NOW) match on the very next attempt, so releasing stays as prompt as a delete was.
        // holderId is cleared so a released lock is distinguishable from one whose lease merely lapsed.
        final Document filter = new Document(DocumentKeys.F_ID, lease.getSessionId().value())
                .append(DocumentKeys.F_HOLDER_ID, lease.getHolderId())
                .append(DocumentKeys.F_FENCING_TOKEN, lease.getFencingToken());
        final Document setStage = new Document("$set",
                new Document(DocumentKeys.F_HOLDER_ID, null).append(DocumentKeys.F_LEASE_EXPIRES_AT, "$$NOW"));
        try {
            collection.findOneAndUpdate(filter, List.of(setStage));
        } catch (MongoException e) {
            log.warn("Best-effort release for {} failed: {}", lease.getSessionId(), e.toString());
        }
    }

    private SessionLease lease(SessionId id, String holderId, long token, Duration duration) {
        return SessionLease.builder().sessionId(id).holderId(holderId).fencingToken(token).acquiredAt(clock.instant())
                .lease(duration).build();
    }
}
