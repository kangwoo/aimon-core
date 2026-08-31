package at.aimon.session.mongodb;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.mongodb.client.MongoDatabase;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;

/**
 * Optional aggregate that wires the MongoDB-backed SPI implementations against one {@link MongoDatabase}.
 *
 * <p>
 * Convenience for assemblers who want a single object to hand to {@code SessionRouter.builder()} — see design
 * §6.3. Holds a reference to the {@link MongoSessionSignalBus} so callers can shut its watcher thread down on
 * graceful application stop without keeping a separate handle.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * The aggregate does <strong>not</strong> own the underlying {@link MongoDatabase} / {@code MongoClient}; the assembler
 * is responsible for closing the client after {@link #close()} has stopped the signal-bus watcher. {@link #close()} is
 * idempotent and only closes the {@link MongoSessionSignalBus}.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * try (MongoClient client = MongoClients.create("mongodb://.../?replicaSet=rs0&w=majority")) {
 *     MongoDatabase db = client.getDatabase("aimon_session");
 *     try (MongoSessionBackends backends = MongoSessionBackends.builder()
 *             .database(db)
 *             .nodeId("node-A")
 *             .build()) {
 *         SessionRouter manager = SessionRouter.builder()
 *                 .sessionLeaseStore(backends.leaseStore())
 *                 .sessionSignalBus(backends.signalBus())
 *                 .sessionInbox(backends.inbox())
 *                 .idempotencyStore(backends.idempotencyStore())
 *                 // ... other dependencies
 *                 .build();
 *     }
 * }
 * }</pre>
 */
public final class MongoSessionBackends implements AutoCloseable {

    private final MongoSessionLeaseStore lock;
    private final MongoSessionInbox inbox;
    private final MongoIdempotencyStore idempotencyStore;
    private final MongoSessionSignalBus signalBus;
    private final MongoSessionRecordStore recordStore;

    private MongoSessionBackends(Builder b) {
        this.lock = b.lock;
        this.inbox = b.inbox;
        this.idempotencyStore = b.idempotencyStore;
        this.signalBus = b.signalBus;
        this.recordStore = b.recordStore;
    }

    public static Builder builder() {
        return new Builder();
    }

    public SessionLeaseStore leaseStore() {
        return lock;
    }

    public SessionInbox inbox() {
        return inbox;
    }

    public IdempotencyStore idempotencyStore() {
        return idempotencyStore;
    }

    public SessionSignalBus signalBus() {
        return signalBus;
    }

    /**
     * The record store holding the sessions' transcripts.
     *
     * <p>
     * Application-scoped and shareable, like the lease store beside it — but note that {@code SessionStore}, which
     * pairs the two behind one door, is <em>node</em>-scoped: two session managers in one JVM share these backends and
     * still need a {@code SessionStore} each.
     *
     * @return the record store, never null
     */
    public SessionRecordStore recordStore() {
        return recordStore;
    }

    /** Closes the change-stream watcher; the {@code MongoClient} owner is responsible for closing the client. */
    @Override
    public void close() {
        signalBus.close();
    }

    /** Builder for {@link MongoSessionBackends}. */
    public static final class Builder {
        private MongoDatabase database;
        private String nodeId;
        private String locksCollection;
        private String inboxCollection;
        private String idempotencyCollection;
        private String signalsCollection;
        private String sessionRecordsCollection;
        private Duration idempotencyDoneTtl = Duration.ofHours(24);
        private Clock clock = Clock.systemUTC();

        private MongoSessionLeaseStore lock;
        private MongoSessionInbox inbox;
        private MongoIdempotencyStore idempotencyStore;
        private MongoSessionSignalBus signalBus;
        private MongoSessionRecordStore recordStore;

        private Builder() {
        }

        public Builder database(MongoDatabase v) {
            this.database = v;
            return this;
        }

        public Builder nodeId(String v) {
            this.nodeId = v;
            return this;
        }

        public Builder locksCollection(String v) {
            this.locksCollection = v;
            return this;
        }

        public Builder inboxCollection(String v) {
            this.inboxCollection = v;
            return this;
        }

        public Builder idempotencyCollection(String v) {
            this.idempotencyCollection = v;
            return this;
        }

        public Builder signalsCollection(String v) {
            this.signalsCollection = v;
            return this;
        }

        public Builder sessionRecordsCollection(String v) {
            this.sessionRecordsCollection = v;
            return this;
        }

        public Builder idempotencyDoneTtl(Duration v) {
            this.idempotencyDoneTtl = v;
            return this;
        }

        public Builder clock(Clock v) {
            this.clock = v;
            return this;
        }

        public MongoSessionBackends build() {
            Objects.requireNonNull(database, "database must not be null");
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            Objects.requireNonNull(idempotencyDoneTtl, "idempotencyDoneTtl must not be null");
            Objects.requireNonNull(clock, "clock must not be null");
            final String locks = locksCollection != null
                    ? locksCollection
                    : at.aimon.session.mongodb.internal.DocumentKeys.COLL_LOCKS;
            final String inboxColl = inboxCollection != null
                    ? inboxCollection
                    : at.aimon.session.mongodb.internal.DocumentKeys.COLL_INBOX;
            final String idem = idempotencyCollection != null
                    ? idempotencyCollection
                    : at.aimon.session.mongodb.internal.DocumentKeys.COLL_IDEMPOTENCY;
            final String signals = signalsCollection != null
                    ? signalsCollection
                    : at.aimon.session.mongodb.internal.DocumentKeys.COLL_SIGNALS;
            this.lock = new MongoSessionLeaseStore(database, locks, clock);
            this.inbox = new MongoSessionInbox(database, inboxColl);
            this.idempotencyStore = new MongoIdempotencyStore(database, idem, idempotencyDoneTtl, clock);
            final String records = sessionRecordsCollection != null
                    ? sessionRecordsCollection
                    : at.aimon.session.mongodb.internal.DocumentKeys.COLL_SESSION_RECORDS;
            this.signalBus = new MongoSessionSignalBus(database, signals, nodeId);
            this.recordStore = new MongoSessionRecordStore(database, records);
            return new MongoSessionBackends(this);
        }
    }
}
