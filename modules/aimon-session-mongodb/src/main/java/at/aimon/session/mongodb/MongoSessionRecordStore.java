package at.aimon.session.mongodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionRecordStoreException;
import at.aimon.core.agent.session.store.SessionRecordCodec;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.store.StoredSessionRecord;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * MongoDB-backed {@link SessionRecordStore}: the transcript half of the session, which until now only the in-memory
 * store held.
 *
 * <p>
 * Everything else about a session was already distributed — the lease, the signal bus, the inbox, the idempotency
 * ledger — so a fleet could hand a session from one node to another and lose the conversation while keeping the
 * bookkeeping about it. This is the store that closes that gap.
 *
 * <h2>Document layout</h2>
 *
 * <pre>{@code
 * {
 *   "_id":                    "<sessionId>",
 *   "transcript":             "<encoded string>",   // absent until the first merge
 *   "agentRef":               "<agent>",            // absent while unbound
 *   "compactionFailureCount": <int>,                // absent until the first increment
 *   "sessionTotals":          { ... },              // absent until the first turn ends
 *   "budgetOverride":         { ... },              // absent when there is no override
 *   "updatedAt":              ISODate
 * }
 * }</pre>
 *
 * <p>
 * <b>Absent is not a separate state.</b> Every field above decodes to the same default the in-memory store reports for
 * a record that has one — no transcript, no totals, no override, a count of zero — so the first turn of a session,
 * which runs against a record the claim path provisioned and nothing has written to yet, behaves identically on both.
 *
 * <p>
 * The transcript is a string rather than a subdocument, and that is forced rather than chosen: a tool use's input is
 * an arbitrary model-supplied map whose keys may contain {@code .} or start with {@code $}, which BSON rejects as
 * field names. The side fields have fixed, numeric shapes and no such exposure, so they go in as subdocuments and stay
 * queryable from the shell. {@code SessionRecordCodec} owns both encodings for all three backends.
 *
 * <h2>Every write is partial, and each maps to one server-side operation</h2>
 *
 * <p>
 * {@link SessionRecordStore} has no full-record write because four writers own four fields, and the {@code @implSpec}
 * on each method asks for one atomic operation rather than a read-modify-write. The mapping:
 *
 * <ul>
 * <li>{@link #provision(SessionId, String) provision} — {@code findOneAndUpdate} with {@code upsert} and a pipeline
 * {@code $set} whose binding stage is {@code $ifNull}, so "create if missing", "bind if unbound" and "tell me who owns
 * this" settle in one round trip and an existing binding is never overwritten
 * <li>{@link #mergeFromSnapshot(SessionSnapshot) mergeFromSnapshot} — an upserting {@code $set} of the transcript
 * alone; the side fields are not named, so a concurrent writer of any of them cannot lose its write here
 * <li>{@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget) setTotalsAndBudgetOverride} — one
 * non-upserting {@code updateOne} carrying both fields ({@code $unset} for a cleared override), so the pair moves
 * together and a missing record stays missing
 * <li>{@link #incrementCompactionFailureCount(SessionId) incrementCompactionFailureCount} — {@code $inc} returning the
 * document after, so two nodes incrementing concurrently get two different numbers
 * <li>{@link #resetCompactionFailureCount(SessionId) resetCompactionFailureCount} — a non-upserting {@code $set} of
 * that field alone
 * </ul>
 *
 * <p>
 * Server-side {@code $$NOW} / {@code $currentDate} stamps {@code updatedAt} rather than an application clock. Nothing
 * reads it — it exists for operator triage — but a per-node clock would make it lie about ordering in exactly the
 * situation an operator consults it.
 *
 * <h2>Fencing</h2>
 *
 * <p>
 * None here, deliberately. This class implements the plain SPI; writes are fenced against the node's lease when the
 * store is reached through {@code SessionStore.records()}, which is where the lease lives. An assembler that hands
 * this store around directly gets last-write-wins between nodes on the same session — the same bargain the in-memory
 * store offers within one JVM.
 */
public final class MongoSessionRecordStore implements SessionRecordStore {

    private final MongoCollection<Document> collection;

    /**
     * Creates a store over the default {@code session_records} collection.
     *
     * @param database
     *            the database (must not be null)
     */
    public MongoSessionRecordStore(MongoDatabase database) {
        this(database, DocumentKeys.COLL_SESSION_RECORDS);
    }

    /**
     * Creates a store over a named collection.
     *
     * @param database
     *            the database (must not be null)
     * @param collectionName
     *            the collection holding session records (must not be null)
     */
    public MongoSessionRecordStore(MongoDatabase database, String collectionName) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
    }

    @Override
    public void mergeFromSnapshot(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        final SessionId id = snapshot.getSessionId();
        // Names the transcript and nothing else. Everything the snapshot cannot carry — the binding, the counter, the
        // totals, the override — is simply not in the update, so it survives by not being mentioned rather than by
        // being read and written back.
        final Document set = new Document(DocumentKeys.F_TRANSCRIPT, SessionRecordCodec.encodeTranscript(snapshot))
                .append(DocumentKeys.F_UPDATED_AT, "$$NOW");
        upsert(id, set, "mergeFromSnapshot");
    }

    @Override
    public SessionRecordView provision(SessionId sessionId, String agentRef) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final Document set = new Document(DocumentKeys.F_UPDATED_AT, "$$NOW");
        if (agentRef != null) {
            // $ifNull, not a plain assignment: an existing binding wins. A node that wanted a different agent finds
            // that out from the returned record and refuses, instead of having already stolen the session.
            set.append(DocumentKeys.F_AGENT_REF,
                    new Document("$ifNull", List.of("$" + DocumentKeys.F_AGENT_REF, agentRef)));
        }
        final Document after = provisionOnce(sessionId, set);
        return after == null ? StoredSessionRecord.empty(sessionId, agentRef) : toRecord(sessionId, after);
    }

    @Override
    public void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals, ExecutionBudget budgetOverride) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(totals, "totals must not be null");
        final Document set = new Document(DocumentKeys.F_SESSION_TOTALS,
                Document.parse(SessionRecordCodec.encodeTotals(totals)));
        final String encodedOverride = SessionRecordCodec.encodeBudgetOverride(budgetOverride);
        if (encodedOverride != null) {
            set.append(DocumentKeys.F_BUDGET_OVERRIDE, Document.parse(encodedOverride));
        }
        final Document update = new Document("$set", set).append("$currentDate",
                new Document(DocumentKeys.F_UPDATED_AT, true));
        if (encodedOverride == null) {
            // A null override does not mean "leave it alone": it clears one that was set, so the next open falls back
            // to the opener's default.
            update.append("$unset", new Document(DocumentKeys.F_BUDGET_OVERRIDE, ""));
        }
        // No upsert: this write follows provisioning, and a record that is not there yet is a no-op, not a create.
        try {
            collection.updateOne(byId(sessionId), update);
        } catch (MongoException e) {
            throw failure("setTotalsAndBudgetOverride", sessionId, e);
        }
    }

    @Override
    public int incrementCompactionFailureCount(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final Document update = new Document("$inc", new Document(DocumentKeys.F_COMPACTION_FAILURE_COUNT, 1))
                .append("$currentDate", new Document(DocumentKeys.F_UPDATED_AT, true));
        try {
            // Returns the document after the increment so the caller gets the value its own increment produced. A
            // read-after-write would let another node's increment land in between and hand both callers the same
            // number — the one miscount a circuit breaker must not make.
            final Document after = collection.findOneAndUpdate(byId(sessionId), update,
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            return after == null ? 0 : intValue(after, DocumentKeys.F_COMPACTION_FAILURE_COUNT);
        } catch (MongoException e) {
            throw failure("incrementCompactionFailureCount", sessionId, e);
        }
    }

    @Override
    public void resetCompactionFailureCount(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final Document update = new Document("$set", new Document(DocumentKeys.F_COMPACTION_FAILURE_COUNT, 0))
                .append("$currentDate", new Document(DocumentKeys.F_UPDATED_AT, true));
        try {
            collection.updateOne(byId(sessionId), update);
        } catch (MongoException e) {
            throw failure("resetCompactionFailureCount", sessionId, e);
        }
    }

    @Override
    public Optional<SessionRecordView> load(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            final Document found = collection.find(byId(sessionId)).first();
            return found == null ? Optional.empty() : Optional.of(toRecord(sessionId, found));
        } catch (MongoException e) {
            throw failure("load", sessionId, e);
        }
    }

    @Override
    public void delete(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            collection.deleteOne(byId(sessionId));
        } catch (MongoException e) {
            throw failure("delete", sessionId, e);
        }
    }

    @Override
    public List<SessionId> listSessionIds() {
        final List<SessionId> ids = new ArrayList<>();
        try {
            for (Document doc : collection.find().projection(new Document(DocumentKeys.F_ID, 1))) {
                ids.add(SessionId.of(doc.getString(DocumentKeys.F_ID)));
            }
        } catch (MongoException e) {
            throw new SessionRecordStoreException("Mongo error during listSessionIds", e);
        }
        return ids;
    }

    @Override
    public boolean exists(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            return collection.find(byId(sessionId)).projection(new Document(DocumentKeys.F_ID, 1)).first() != null;
        } catch (MongoException e) {
            throw failure("exists", sessionId, e);
        }
    }

    @Override
    public void clear() {
        try {
            collection.deleteMany(new Document());
        } catch (MongoException e) {
            throw new SessionRecordStoreException("Mongo error during clear", e);
        }
    }

    private Document byId(SessionId sessionId) {
        return new Document(DocumentKeys.F_ID, sessionId.value());
    }

    private Document provisionOnce(SessionId sessionId, Document set) {
        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().upsert(true)
                .returnDocument(ReturnDocument.AFTER);
        try {
            return collection.findOneAndUpdate(byId(sessionId), List.of(new Document("$set", set)), options);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw failure("provision", sessionId, e);
            }
            // Two nodes upserted the same _id in the same instant and one lost the insert. The record now exists, so
            // the retry takes the update branch — where $ifNull hands the win to whoever bound it first.
            try {
                return collection.findOneAndUpdate(byId(sessionId), List.of(new Document("$set", set)), options);
            } catch (MongoException retry) {
                throw failure("provision", sessionId, retry);
            }
        } catch (MongoException e) {
            throw failure("provision", sessionId, e);
        }
    }

    private void upsert(SessionId sessionId, Document set, String operation) {
        final UpdateOptions options = new UpdateOptions().upsert(true);
        try {
            collection.updateOne(byId(sessionId), List.of(new Document("$set", set)), options);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw failure(operation, sessionId, e);
            }
            try {
                collection.updateOne(byId(sessionId), List.of(new Document("$set", set)), options);
            } catch (MongoException retry) {
                throw failure(operation, sessionId, retry);
            }
        } catch (MongoException e) {
            throw failure(operation, sessionId, e);
        }
    }

    private StoredSessionRecord toRecord(SessionId sessionId, Document doc) {
        try {
            return StoredSessionRecord.builder(sessionId)
                    .transcript(
                            SessionRecordCodec.decodeTranscript(sessionId, doc.getString(DocumentKeys.F_TRANSCRIPT)))
                    .agentRef(doc.getString(DocumentKeys.F_AGENT_REF))
                    .compactionFailureCount(intValue(doc, DocumentKeys.F_COMPACTION_FAILURE_COUNT))
                    .sessionTotals(SessionRecordCodec.decodeTotals(json(doc, DocumentKeys.F_SESSION_TOTALS)))
                    .budgetOverride(SessionRecordCodec.decodeBudgetOverride(json(doc, DocumentKeys.F_BUDGET_OVERRIDE)))
                    .build();
        } catch (RuntimeException e) {
            // A record that will not decode is an infrastructure failure from the caller's side, not a missing one:
            // reporting it as absent would let a session silently resume with an empty history.
            throw new SessionRecordStoreException("Failed to decode stored session record for " + sessionId, e);
        }
    }

    private static String json(Document doc, String field) {
        final Document sub = doc.get(field, Document.class);
        return sub == null ? null : sub.toJson();
    }

    private static int intValue(Document doc, String field) {
        final Number value = doc.get(field, Number.class);
        return value == null ? 0 : value.intValue();
    }

    private static SessionRecordStoreException failure(String operation, SessionId sessionId, Exception cause) {
        return new SessionRecordStoreException("Mongo error during " + operation + " for " + sessionId, cause);
    }
}
