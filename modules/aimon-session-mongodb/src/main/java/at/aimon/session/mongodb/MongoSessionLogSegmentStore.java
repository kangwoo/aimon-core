package at.aimon.session.mongodb;

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
 *   "_id":        "<segmentId>",
 *   "sessionId":  "<sessionId>",       // indexed — every query is scoped by it
 *   "fromSeq":    <long>,
 *   "toSeq":      <long>,
 *   "entryCount": <int>,
 *   "payload":    "<encoded entries>", // a string, for the reason the record's transcript is one
 *   "createdAt":  ISODate               // the sealing node's clock, compared with the GC grace period
 * }
 * }</pre>
 *
 * <p>
 * {@code _id} is the segment id, so {@link #put} is an {@code insertOne} and a duplicate id is rejected by the server
 * rather than overwritten. Reads and deletes filter on {@code sessionId} as well as {@code _id}, so one session never
 * sees another's segment. A segment is sized by {@code minSealTokens}, far below the 16MB document limit.
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
        final Document doc = new Document(DocumentKeys.F_ID, segment.getId().value())
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
                infos.add(SegmentInfo.of(SegmentId.of(doc.getString(DocumentKeys.F_ID)),
                        doc.getDate(DocumentKeys.F_SEGMENT_CREATED_AT).toInstant()));
            }
        } catch (MongoException e) {
            throw failure("list", sessionId, e);
        }
        return infos;
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
        return new Document(DocumentKeys.F_ID, id.value()).append(DocumentKeys.F_SEGMENT_SESSION_ID, sessionId.value());
    }

    private static SessionLogSegmentStoreException failure(String operation, SessionId sessionId, Exception cause) {
        return new SessionLogSegmentStoreException("Mongo error during " + operation + " for " + sessionId, cause);
    }
}
