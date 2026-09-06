package at.aimon.session.mongodb;

import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.testkit.AbstractSessionInboxDurabilityContractTest;

/** The shared inbox durability contract, against a real MongoDB. */
@DisplayName("MongoSessionInbox — batch durability contract")
@Tag("docker")
class MongoSessionInboxDurabilityContractTest extends AbstractSessionInboxDurabilityContractTest {

    private MongoDatabase db;
    private MongoSessionInbox inbox;

    @BeforeEach
    void wire() {
        MongoTestSupport.dropAndApplyDdl();
        db = MongoTestSupport.sharedDatabase();
        inbox = new MongoSessionInbox(db);
    }

    @Override
    protected SessionInbox inbox() {
        return inbox;
    }

    @Override
    protected InboundMessageId plantUnreadableEntry(SessionId id, QueuedInputPriority tier, String turnId,
            String idempotencyKey) {
        // "ROBOT" is not a Principal.Type, so decodePrincipal's valueOf throws.
        final Document payload = new Document("agentRef", "agent-x").append("userInput", "unreadable")
                .append("turnId", turnId).append("idempotencyKey", idempotencyKey)
                .append("deliveredAt", java.util.Date.from(java.time.Instant.parse("2026-04-27T10:00:00Z")))
                .append("initiator", new Document("type", "ROBOT").append("id", "u-9").append("displayName", "bad"));
        // $$NOW, exactly as deliver does it: the sort axis is deliveredAt, so a planted entry stamped from a client
        // clock could be re-ordered against the deliveries around it by skew alone.
        final ObjectId planted = new ObjectId();
        final Document setStage = new Document("$set",
                new Document(DocumentKeys.F_CONVERSATION_ID, id.value()).append(DocumentKeys.F_PRIORITY, tier.ordinal())
                        .append(DocumentKeys.F_DELIVERED_AT, "$$NOW").append(DocumentKeys.F_PAYLOAD, payload));
        db.getCollection(DocumentKeys.COLL_INBOX).findOneAndUpdate(Filters.eq(DocumentKeys.F_ID, planted),
                List.of(setStage), new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return InboundMessageId.of(planted.toHexString());
    }

    @Override
    protected long countStored(SessionId id) {
        return db.getCollection(DocumentKeys.COLL_INBOX)
                .countDocuments(Filters.eq(DocumentKeys.F_CONVERSATION_ID, id.value()));
    }
}
