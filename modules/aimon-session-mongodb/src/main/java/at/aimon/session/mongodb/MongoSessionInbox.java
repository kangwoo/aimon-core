package at.aimon.session.mongodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndDeleteOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionInboxException;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.mongodb.internal.InboundMessageCodec;

/**
 * MongoDB-backed {@link SessionInbox} per design §4.3.
 *
 * <p>
 * Each delivered message is one document in {@code conversation_inbox}; the compound index
 * {@code (conversationId, priority, deliveredAt)} drives both the priority-then-FIFO sort used by {@link #collect}
 * and the cheap {@link #isEmpty} short-circuit. That first field is the frozen wire key, not a rename this class
 * missed — see {@link DocumentKeys#F_CONVERSATION_ID}.
 *
 * <p>
 * {@link #collect} loops {@code findOneAndDelete} ordered by {@code (priority, deliveredAt)} up to
 * {@link #MAX_BATCH_SIZE}. Single-consumer atomicity is guaranteed by the manager's lock-holder invariant (only the
 * holder collects); the per-document atomic delete is defensive against any future relaxation.
 */
public final class MongoSessionInbox implements SessionInbox {

    private static final Logger log = LoggerFactory.getLogger(MongoSessionInbox.class);

    /** Per-call ceiling — large backlogs drain over the next collect call (design §4.3). */
    public static final int MAX_BATCH_SIZE = 64;

    private final MongoCollection<Document> collection;
    private final InboundMessageCodec codec;

    public MongoSessionInbox(MongoDatabase database) {
        this(database, DocumentKeys.COLL_INBOX);
    }

    public MongoSessionInbox(MongoDatabase database, String collectionName) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
        this.codec = new InboundMessageCodec();
    }

    @Override
    public InboundMessageId deliver(InboundMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            // Server-side time: stamp deliveredAt via $$NOW so FIFO ordering inside a priority bucket does not depend
            // on cross-node application clock sync. We pre-allocate the ObjectId so we can return it without a second
            // round-trip to read back the upserted document.
            final ObjectId id = new ObjectId();
            final Document payload = codec.encodePayload(message);
            final Document setStage = new Document("$set",
                    new Document(DocumentKeys.F_CONVERSATION_ID, message.getSessionId().value())
                            .append(DocumentKeys.F_PRIORITY, message.getPriority().ordinal())
                            .append(DocumentKeys.F_DELIVERED_AT, "$$NOW").append(DocumentKeys.F_PAYLOAD, payload));
            collection.findOneAndUpdate(Filters.eq(DocumentKeys.F_ID, id), List.of(setStage),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            log.debug("Delivered to inbox conv={} id={}", message.getSessionId(), id);
            return InboundMessageId.of(id.toHexString());
        } catch (MongoException e) {
            throw new SessionInboxException("Mongo error delivering to " + message.getSessionId(), e);
        }
    }

    @Override
    public List<InboundMessage> collect(SessionId id, QueuedInputPriority maxPriority) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(maxPriority, "maxPriority must not be null");
        try {
            final List<InboundMessage> out = new ArrayList<>();
            final FindOneAndDeleteOptions options = new FindOneAndDeleteOptions()
                    .sort(Sorts.ascending(DocumentKeys.F_PRIORITY, DocumentKeys.F_DELIVERED_AT));
            for (int i = 0; i < MAX_BATCH_SIZE; i++) {
                final Document doc = collection
                        .findOneAndDelete(Filters.and(Filters.eq(DocumentKeys.F_CONVERSATION_ID, id.value()),
                                Filters.lte(DocumentKeys.F_PRIORITY, maxPriority.ordinal())), options);
                if (doc == null) {
                    break;
                }
                out.add(codec.decode(doc));
            }
            return out;
        } catch (MongoException e) {
            throw new SessionInboxException("Mongo error collecting from " + id, e);
        }
    }

    @Override
    public boolean isEmpty(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        try {
            return collection.countDocuments(Filters.eq(DocumentKeys.F_CONVERSATION_ID, id.value()),
                    new CountOptions().limit(1)) == 0L;
        } catch (MongoException e) {
            throw new SessionInboxException("Mongo error sizing " + id, e);
        }
    }

    @Override
    public void purge(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        try {
            collection.deleteMany(Filters.eq(DocumentKeys.F_CONVERSATION_ID, id.value()));
        } catch (MongoException e) {
            throw new SessionInboxException("Mongo error purging " + id, e);
        }
    }
}
