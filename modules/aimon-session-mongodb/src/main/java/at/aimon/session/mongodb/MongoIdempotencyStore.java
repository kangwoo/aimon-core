package at.aimon.session.mongodb;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.result.UpdateResult;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.exception.IdempotencyStoreException;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.PutResult;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.mongodb.internal.IdempotencyEntryCodec;

/**
 * MongoDB-backed {@link IdempotencyStore} per design §4.4.
 *
 * <p>
 * Each idempotency key maps to one document in {@code idempotency_entries}, with a TTL index on {@code expiresAt}
 * driving primary expiration and the {@code (status, lastTouchedAt)} index supporting the holder-loss sweeper's
 * {@link #findStaleInFlight(Instant)} scan.
 *
 * <p>
 * Document layout: see {@link IdempotencyEntryCodec}.
 *
 * <p>
 * The runtime never creates the collection or indexes — operators apply {@code db/mongodb/init.js} once per cluster
 * (design §3.4). If the indexes are missing, queries still succeed but degrade to collection scans.
 *
 * <p>
 * MongoDB's TTL monitor runs on a 60-second cadence, so {@code DONE} entries may live up to 60s past their nominal
 * expiry; this is harmless for correctness because the sweeper relies on {@code lastTouchedAt} rather than TTL deletion
 * (design §3.3, §8 row "TTL monitor lag").
 */
public final class MongoIdempotencyStore implements IdempotencyStore {

    /** Primary TTL applied when an entry transitions to {@link IdempotencyEntry.Status#DONE}. */
    public static final Duration DEFAULT_DONE_TTL = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(MongoIdempotencyStore.class);

    private static final int MAX_STALE_BATCH = 256;
    private static final int MAX_DUPLICATE_RETRY = 2;

    private final MongoCollection<Document> collection;
    private final IdempotencyEntryCodec codec;
    private final Duration doneTtl;
    private final Clock clock;

    public MongoIdempotencyStore(MongoDatabase database) {
        this(database, DocumentKeys.COLL_IDEMPOTENCY, DEFAULT_DONE_TTL, Clock.systemUTC());
    }

    public MongoIdempotencyStore(MongoDatabase database, String collectionName, Duration doneTtl, Clock clock) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
        this.doneTtl = Objects.requireNonNull(doneTtl, "doneTtl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.codec = new IdempotencyEntryCodec();
    }

    @Override
    public PutResult putIfAbsent(String key, IdempotencyEntry entry, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");

        for (int attempt = 0; attempt < MAX_DUPLICATE_RETRY; attempt++) {
            try {
                final Document doc = codec.encode(entry).append(DocumentKeys.F_ID, key)
                        .append(DocumentKeys.F_EXPIRES_AT, Date.from(clock.instant().plus(ttl)));
                collection.insertOne(doc);
                return PutResult.inserted();
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    throw new IdempotencyStoreException("Mongo error during putIfAbsent for key " + key, e);
                }
                final Document existing = collection.find(Filters.eq(DocumentKeys.F_ID, key)).first();
                if (existing == null) {
                    // TTL deletion may have removed the prior entry between our insert and the find; retry insert.
                    log.debug("Duplicate-then-missing race on putIfAbsent for {}, retrying (attempt {})", key, attempt);
                    continue;
                }
                return PutResult.existing(codec.decode(existing));
            } catch (MongoException e) {
                throw new IdempotencyStoreException("Mongo error during putIfAbsent for key " + key, e);
            }
        }
        throw new IllegalStateException(
                "putIfAbsent for key " + key + " exhausted retry budget against duplicate-then-deleted races");
    }

    @Override
    public void markDone(String key, AgentExecutionResult result) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(result, "result must not be null");
        try {
            final Document set = new Document().append(DocumentKeys.F_STATUS, IdempotencyEntry.Status.DONE.name())
                    .append(DocumentKeys.F_RESULT, IdempotencyEntryCodec.encodeResult(result))
                    .append(DocumentKeys.F_LAST_TOUCHED_AT, Date.from(clock.instant()))
                    .append(DocumentKeys.F_EXPIRES_AT, Date.from(clock.instant().plus(doneTtl)))
                    .append(DocumentKeys.F_HOLDER_ID, null);
            final UpdateResult update = collection.updateOne(Filters.eq(DocumentKeys.F_ID, key),
                    new Document("$set", set));
            if (update.getMatchedCount() == 0L) {
                // Either TTL deletion ran ahead of markDone, or the key was reset by compareAndReset; either way the
                // caller's expectation of an IN_FLIGHT → DONE transition is unmet. Log so operators can correlate with
                // the manager's holder-loss recovery path (design §4.4).
                log.warn("markDone for key {} matched no document — entry was reset or TTL-evicted", key);
            }
        } catch (MongoException e) {
            throw new IdempotencyStoreException("Mongo error during markDone for key " + key, e);
        }
    }

    @Override
    public Optional<IdempotencyEntry> find(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            final Document doc = collection.find(Filters.eq(DocumentKeys.F_ID, key)).first();
            return doc == null ? Optional.empty() : Optional.of(codec.decode(doc));
        } catch (MongoException e) {
            throw new IdempotencyStoreException("Mongo error during find for key " + key, e);
        }
    }

    @Override
    public boolean touch(String key, String holderId) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        try {
            // Bump expiresAt by doneTtl so the renewer interval fits comfortably below the absolute deadline; the
            // manager calls touch on the lease-renewer cadence and the actual stale detection rides lastTouchedAt.
            final Document set = new Document().append(DocumentKeys.F_LAST_TOUCHED_AT, Date.from(clock.instant()))
                    .append(DocumentKeys.F_EXPIRES_AT, Date.from(clock.instant().plus(doneTtl)));
            final Document result = collection.findOneAndUpdate(
                    Filters.and(Filters.eq(DocumentKeys.F_ID, key),
                            Filters.eq(DocumentKeys.F_STATUS, IdempotencyEntry.Status.IN_FLIGHT.name()),
                            Filters.eq(DocumentKeys.F_HOLDER_ID, holderId)),
                    new Document("$set", set), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            return result != null;
        } catch (MongoException e) {
            throw new IdempotencyStoreException("Mongo error during touch for key " + key, e);
        }
    }

    @Override
    public boolean releaseHolder(String key, String expectedHolderId, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(expectedHolderId, "expectedHolderId must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        try {
            // Unset rather than delete: the key stays reserved against a duplicate submit and against a no-op
            // markDone, but with no holder it is no longer evidence of a live turn for the holder-loss sweeper.
            final Document update = new Document("$set",
                    new Document().append(DocumentKeys.F_LAST_TOUCHED_AT, Date.from(clock.instant()))
                            .append(DocumentKeys.F_EXPIRES_AT, Date.from(clock.instant().plus(ttl))))
                    .append("$unset", new Document(DocumentKeys.F_HOLDER_ID, ""));
            final Document result = collection.findOneAndUpdate(
                    Filters.and(Filters.eq(DocumentKeys.F_ID, key),
                            Filters.eq(DocumentKeys.F_STATUS, IdempotencyEntry.Status.IN_FLIGHT.name()),
                            Filters.eq(DocumentKeys.F_HOLDER_ID, expectedHolderId)),
                    update, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            return result != null;
        } catch (MongoException e) {
            throw new IdempotencyStoreException("Mongo error during releaseHolder for key " + key, e);
        }
    }

    @Override
    public boolean discardReservation(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            // The inverse of releaseHolder's filter: that one matches the entry a named holder is executing under,
            // this one the reservation it left behind. exists(false) is what keeps a live turn's entry out of reach.
            final Document deleted = collection.findOneAndDelete(Filters.and(Filters.eq(DocumentKeys.F_ID, key),
                    Filters.eq(DocumentKeys.F_STATUS, IdempotencyEntry.Status.IN_FLIGHT.name()),
                    Filters.exists(DocumentKeys.F_HOLDER_ID, false)));
            return deleted != null;
        } catch (MongoException e) {
            throw new IdempotencyStoreException("Mongo error during discardReservation for key " + key, e);
        }
    }

    @Override
    public boolean compareAndReset(String key, String expectedHolderId) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(expectedHolderId, "expectedHolderId must not be null");
        try {
            final Document deleted = collection.findOneAndDelete(Filters.and(Filters.eq(DocumentKeys.F_ID, key),
                    Filters.eq(DocumentKeys.F_STATUS, IdempotencyEntry.Status.IN_FLIGHT.name()),
                    Filters.eq(DocumentKeys.F_HOLDER_ID, expectedHolderId)));
            return deleted != null;
        } catch (MongoException e) {
            throw new IdempotencyStoreException("Mongo error during compareAndReset for key " + key, e);
        }
    }

    @Override
    public List<IdempotencyEntry> findStaleInFlight(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        try {
            final List<IdempotencyEntry> out = new ArrayList<>();
            // exists(holderId) excludes reserved-but-unclaimed entries: nobody executes them, so nobody touches them,
            // and they are not evidence of holder loss. Filtering server-side also keeps them out of MAX_STALE_BATCH.
            for (Document doc : collection
                    .find(Filters.and(Filters.eq(DocumentKeys.F_STATUS, IdempotencyEntry.Status.IN_FLIGHT.name()),
                            Filters.exists(DocumentKeys.F_HOLDER_ID),
                            Filters.lt(DocumentKeys.F_LAST_TOUCHED_AT, Date.from(cutoff))))
                    .limit(MAX_STALE_BATCH)) {
                out.add(codec.decode(doc));
            }
            return out;
        } catch (MongoException e) {
            throw new IdempotencyStoreException("Mongo error during findStaleInFlight", e);
        }
    }
}
