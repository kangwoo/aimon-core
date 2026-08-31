package at.aimon.session.mongodb;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.testcontainers.containers.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * Shared test infrastructure: singleton MongoDB replica-set container, helpers for {@link MongoClient} /
 * {@link MongoDatabase} lifecycle, and a {@code dropAndApplyDdl} hook between tests so each test sees a clean
 * collection state. The DDL applied here mirrors {@code db/mongodb/init.js} — production runtime never executes
 * the JS script (operators apply it once per cluster), but the tests need the schema in place before exercising
 * the SPI.
 */
public final class MongoTestSupport {

    public static final String DATABASE_NAME = "aimon_session_test";

    public static final MongoDBContainer MONGO;

    private static final MongoClient SHARED_CLIENT;

    static {
        // MongoDBContainer auto-runs `rs.initiate()` on startup, giving Change Streams what they need.
        MONGO = new MongoDBContainer("mongo:7.0").withStartupTimeout(java.time.Duration.ofMinutes(2));
        MONGO.start();
        SHARED_CLIENT = MongoClients.create(MONGO.getReplicaSetUrl(DATABASE_NAME));
    }

    private MongoTestSupport() {
    }

    public static String connectionString() {
        return MONGO.getReplicaSetUrl(DATABASE_NAME);
    }

    public static MongoClient newClient() {
        return MongoClients.create(connectionString());
    }

    public static MongoDatabase sharedDatabase() {
        return SHARED_CLIENT.getDatabase(DATABASE_NAME);
    }

    /**
     * Drops any existing collections and reapplies the DDL from {@code init.js} (translated to driver calls). Tests
     * call
     * this in {@code @BeforeEach} so each run starts from a known schema.
     */
    public static void dropAndApplyDdl() {
        final MongoDatabase db = sharedDatabase();
        for (String name : List.of(DocumentKeys.COLL_LOCKS, DocumentKeys.COLL_INBOX, DocumentKeys.COLL_IDEMPOTENCY,
                DocumentKeys.COLL_SIGNALS, DocumentKeys.COLL_BACKGROUND_TASK, DocumentKeys.COLL_SESSION_RECORDS)) {
            try {
                db.getCollection(name).drop();
            } catch (RuntimeException ignored) {
                // collection may not exist — drop is best-effort
            }
        }

        db.createCollection(DocumentKeys.COLL_LOCKS);
        db.createCollection(DocumentKeys.COLL_INBOX);
        db.createCollection(DocumentKeys.COLL_IDEMPOTENCY);
        db.createCollection(DocumentKeys.COLL_SIGNALS,
                new CreateCollectionOptions().capped(true).sizeInBytes(64L * 1024L * 1024L));
        db.createCollection(DocumentKeys.COLL_BACKGROUND_TASK);
        // No index: every access is by _id, and listSessionIds is a full scan either way. init.js creates it for the
        // same reason it creates the others — so an operator sees the collection before the first session lands in it.
        db.createCollection(DocumentKeys.COLL_SESSION_RECORDS);

        final MongoCollection<Document> inbox = db.getCollection(DocumentKeys.COLL_INBOX);
        // Spelled out rather than built from DocumentKeys: this index mirrors the one operators applied from init.js,
        // and going through the constant would make it follow a rename of the Java identifier while the deployed index
        // stayed put — the integration tests would keep passing against an index production does not have.
        inbox.createIndex(Indexes.ascending("conversationId", "priority", "deliveredAt"),
                new IndexOptions().name("by_conv_priority_fifo"));

        final MongoCollection<Document> idem = db.getCollection(DocumentKeys.COLL_IDEMPOTENCY);
        idem.createIndex(Indexes.ascending(DocumentKeys.F_EXPIRES_AT),
                new IndexOptions().name("ttl_expires_at").expireAfter(0L, TimeUnit.SECONDS));
        idem.createIndex(Indexes.ascending(DocumentKeys.F_STATUS, DocumentKeys.F_LAST_TOUCHED_AT),
                new IndexOptions().name("by_status_touch"));

        final MongoCollection<Document> tasks = db.getCollection(DocumentKeys.COLL_BACKGROUND_TASK);
        tasks.createIndex(Indexes.ascending(DocumentKeys.F_STATE), new IndexOptions().name("by_state"));
        tasks.createIndex(Indexes.ascending(DocumentKeys.F_CONTEXT_ID), new IndexOptions().name("by_context"));
    }
}
