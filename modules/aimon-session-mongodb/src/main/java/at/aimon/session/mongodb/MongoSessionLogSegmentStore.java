package at.aimon.session.mongodb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * MongoDB-backed {@link SessionLogSegmentStore}: one document per sealed segment in {@code session_log_segments}.
 *
 * <h2>Document layout</h2>
 *
 * <pre>{@code
 * {
 *   "_id":        { "sessionId": "<sessionId>", "segmentId": "<segmentId>" },
 *   "sessionId":  "<sessionId>",       // indexed — list and deleteAll are scoped by it
 *   "fromSeq":    <long>,
 *   "toSeq":      <long>,
 *   "entryCount": <int>,
 *   "payload":    "<encoded entries>", // a string, for the reason the record's transcript is one
 *   "createdAt":  ISODate               // the sealing node's clock, compared with the GC grace period
 * }
 * }</pre>
 *
 * <p>
 * {@code _id} is the pair of session id and segment id, in that field order — the SPI scopes a segment id by session,
 * so the same id in two sessions is two documents. {@link #put} is an {@code insertOne}, and a duplicate within a
 * session is rejected by the server rather than overwritten. {@link #get} and {@link #delete} match the whole
 * {@code _id}; {@link #list} and {@link #deleteAll} filter on the top-level {@code sessionId}, which the
 * {@code by_session} index covers — an index on a subdocument {@code _id} does not serve a query on one of its fields.
 * A segment is sized by {@code minSealTokens}, far below the 16MB document limit.
 *
 * <p>
 * Not fenced, like the record store: deletes are fenced when reached through {@code SessionStore.segments(...)}.
 * Read-after-write holds under the driver's default primary read preference.
 */
public final class MongoSessionLogSegmentStore implements SessionLogSegmentStore {

    private final MongoCollection<Document> collection;

    /**
     * Creates a store over the default {@code session_log_segments} collection.
     *
     * @param database
     *            the database (must not be null)
     */
    public MongoSessionLogSegmentStore(MongoDatabase database) {
        this(database, DocumentKeys.COLL_SESSION_LOG_SEGMENTS);
    }

    /**
     * Creates a store over a named collection.
     *
     * @param database
     *            the database (must not be null)
     * @param collectionName
     *            the collection holding segments (must not be null)
     */
    public MongoSessionLogSegmentStore(MongoDatabase database, String collectionName) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
    }

    @Override
    public void put(SessionLogSegment segment) {
        Objects.requireNonNull(segment, "segment must not be null");
        final Document doc = new Document(DocumentKeys.F_ID, compoundId(segment.getSessionId(), segment.getId()))
                .append(DocumentKeys.F_SEGMENT_SESSION_ID, segment.getSessionId().value())
                .append(DocumentKeys.F_SEGMENT_FROM_SEQ, segment.getFromSeq())
                .append(DocumentKeys.F_SEGMENT_TO_SEQ, segment.getToSeq())
                .append(DocumentKeys.F_SEGMENT_ENTRY_COUNT, segment.getEntryCount())
                .append(DocumentKeys.F_SEGMENT_PAYLOAD, segment.getPayload())
                .append(DocumentKeys.F_SEGMENT_CREATED_AT, Date.from(segment.getCreatedAt()));
        try {
            collection.insertOne(doc);
        } catch (MongoException e) {
            throw failure("put", segment.getSessionId(), e);
        }
    }

    @Override
    public Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try {
            final Document found = collection.find(byId(sessionId, id)).first();
            if (found == null) {
                return Optional.empty();
            }
            return Optional.of(SessionLogSegment.builder().sessionId(sessionId).id(id)
                    .fromSeq(found.get(DocumentKeys.F_SEGMENT_FROM_SEQ, Number.class).longValue())
                    .toSeq(found.get(DocumentKeys.F_SEGMENT_TO_SEQ, Number.class).longValue())
                    .entryCount(found.get(DocumentKeys.F_SEGMENT_ENTRY_COUNT, Number.class).intValue())
                    .payload(found.getString(DocumentKeys.F_SEGMENT_PAYLOAD))
                    .createdAt(found.getDate(DocumentKeys.F_SEGMENT_CREATED_AT).toInstant()).build());
        } catch (RuntimeException e) {
            throw failure("get", sessionId, e);
        }
    }

    @Override
    public List<SegmentInfo> list(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final List<SegmentInfo> infos = new ArrayList<>();
        try {
            for (Document doc : collection.find(bySession(sessionId))
                    .projection(new Document(DocumentKeys.F_SEGMENT_CREATED_AT, 1))) {
                final Document id = doc.get(DocumentKeys.F_ID, Document.class);
                infos.add(SegmentInfo.of(SegmentId.of(id.getString(DocumentKeys.F_SEGMENT_ID)),
                        doc.getDate(DocumentKeys.F_SEGMENT_CREATED_AT).toInstant()));
            }
        } catch (MongoException e) {
            throw failure("list", sessionId, e);
        }
        return infos;
    }

    /**
     * Exact: only sessions with a segment older than {@code createdBefore}, in ascending id order, each once per pass —
     * a {@code $match} / {@code $group} / {@code $sort} / {@code $limit} aggregation. The cursor is the last session id
     * of the previous page; ids compare as strings, the same order {@code $sort} uses.
     */
    @Override
    public SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit) {
        Objects.requireNonNull(createdBefore, "createdBefore must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        final Document match = new Document(DocumentKeys.F_SEGMENT_CREATED_AT,
                new Document("$lt", Date.from(createdBefore)));
        if (cursor != null) {
            match.append(DocumentKeys.F_SEGMENT_SESSION_ID, new Document("$gt", cursor));
        }
        final List<Document> pipeline = List.of(new Document("$match", match),
                new Document("$group", new Document(DocumentKeys.F_ID, "$" + DocumentKeys.F_SEGMENT_SESSION_ID)),
                new Document("$sort", new Document(DocumentKeys.F_ID, 1)), new Document("$limit", limit));
        final List<SessionId> ids = new ArrayList<>();
        try {
            for (Document doc : collection.aggregate(pipeline)) {
                ids.add(SessionId.of(doc.getString(DocumentKeys.F_ID)));
            }
        } catch (MongoException e) {
            throw new SessionLogSegmentStoreException("Mongo error during scanSessions", e);
        }
        return SegmentScanPage.of(ids, ids.size() < limit ? null : ids.get(ids.size() - 1).value());
    }

    @Override
    public void delete(SessionId sessionId, SegmentId id) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try {
            collection.deleteOne(byId(sessionId, id));
        } catch (MongoException e) {
            throw failure("delete", sessionId, e);
        }
    }

    @Override
    public void deleteAll(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            collection.deleteMany(bySession(sessionId));
        } catch (MongoException e) {
            throw failure("deleteAll", sessionId, e);
        }
    }

    private static Document bySession(SessionId sessionId) {
        return new Document(DocumentKeys.F_SEGMENT_SESSION_ID, sessionId.value());
    }

    private static Document byId(SessionId sessionId, SegmentId id) {
        return new Document(DocumentKeys.F_ID, compoundId(sessionId, id));
    }

    /** Field order matters: Mongo compares subdocuments field by field, in order. Built in one place only. */
    private static Document compoundId(SessionId sessionId, SegmentId id) {
        return new Document(DocumentKeys.F_SEGMENT_SESSION_ID, sessionId.value()).append(DocumentKeys.F_SEGMENT_ID,
                id.value());
    }

    private static SessionLogSegmentStoreException failure(String operation, SessionId sessionId, Exception cause) {
        return new SessionLogSegmentStoreException("Mongo error during " + operation + " for " + sessionId, cause);
    }
}
