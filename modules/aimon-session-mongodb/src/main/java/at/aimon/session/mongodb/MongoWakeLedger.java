package at.aimon.session.mongodb;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.bson.Document;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.wake.WakeLedger;
import at.aimon.core.agent.session.wake.WakeState;
import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * MongoDB-backed {@link WakeLedger}: the idle-session waker's caps shared by every node (idle-wake design §4.3).
 *
 * <p>
 * One document per session in {@code wake_ledger}, keyed by the session id. {@link #update} is an optimistic
 * compare-and-set on a {@code version} field: it reads the document, applies the change in Java and replaces the
 * document only while its version is still the one read (a missing document is inserted, and a duplicate-key refusal
 * means another node inserted first). A refused write reads again and retries, so no node's count is lost.
 *
 * <p>
 * Every write sets {@code expiresAt} to the write time plus the expiry ({@link #DEFAULT_TTL} unless given), and the
 * {@code ttl_expires_at} index declared in {@code init.js} removes the document after it — a session quiet for that
 * long has no wakes left in its hour window and only forgets its streak. All backend errors surface as
 * {@link IllegalStateException}.
 */
public final class MongoWakeLedger implements WakeLedger {

    /** Default expiry of a session's document after its last write. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(1);

    static final String F_RECENT_WAKES = "recentWakes";
    static final String F_CONSECUTIVE = "consecutive";
    static final String F_TURN_COUNT_AFTER_WAKE = "turnCountAfterWake";
    static final String F_OUTSTANDING = "outstanding";
    static final String F_VERSION = "version";
    static final String F_EXPIRES_AT = "expiresAt";

    // Contention on one session is a few nodes waking it and settling its turn; a refused CAS is retried at once.
    private static final int MAX_CAS_ATTEMPTS = 200;

    private final MongoCollection<Document> collection;
    private final Duration ttl;
    private final Clock clock;

    /**
     * Creates a ledger over {@code wake_ledger} with the default expiry and the system UTC clock.
     *
     * @param database
     *            the database (must not be null; its client is owned by the caller)
     */
    public MongoWakeLedger(MongoDatabase database) {
        this(database, DEFAULT_TTL, Clock.systemUTC());
    }

    /**
     * Creates a ledger with an explicit expiry and clock.
     *
     * @param database
     *            the database (must not be null; its client is owned by the caller)
     * @param ttl
     *            how long a session's document outlives its last write (must be positive)
     * @param clock
     *            the clock that stamps {@code expiresAt} (must not be null)
     */
    public MongoWakeLedger(MongoDatabase database, Duration ttl, Clock clock) {
        this.collection = Objects.requireNonNull(database, "database must not be null")
                .getCollection(DocumentKeys.COLL_WAKE_LEDGER);
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public WakeState get(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            final Document doc = collection.find(Filters.eq(DocumentKeys.F_ID, sessionId.value())).first();
            return doc == null ? WakeState.empty() : decode(doc);
        } catch (MongoException e) {
            throw new IllegalStateException("MongoDB error reading the wake ledger of " + sessionId, e);
        }
    }

    @Override
    public WakeState update(SessionId sessionId, UnaryOperator<WakeState> change) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(change, "change must not be null");
        final String id = sessionId.value();
        try {
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                final Document stored = collection.find(Filters.eq(DocumentKeys.F_ID, id)).first();
                final WakeState current = stored == null ? WakeState.empty() : decode(stored);
                final WakeState next = Objects.requireNonNull(change.apply(current), "change returned null");
                if (stored == null) {
                    if (insert(id, next)) {
                        return next;
                    }
                    continue;
                }
                final long version = stored.get(F_VERSION, Number.class).longValue();
                final Document replacement = encode(id, next, version + 1);
                if (collection
                        .replaceOne(Filters.and(Filters.eq(DocumentKeys.F_ID, id), Filters.eq(F_VERSION, version)),
                                replacement)
                        .getMatchedCount() == 1) {
                    return next;
                }
            }
        } catch (MongoException e) {
            throw new IllegalStateException("MongoDB error updating the wake ledger of " + sessionId, e);
        }
        throw new IllegalStateException(
                "Wake ledger of " + sessionId + " changed under every one of " + MAX_CAS_ATTEMPTS + " attempts");
    }

    @Override
    public void remove(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            collection.deleteOne(Filters.eq(DocumentKeys.F_ID, sessionId.value()));
        } catch (MongoException e) {
            throw new IllegalStateException("MongoDB error removing the wake ledger of " + sessionId, e);
        }
    }

    /** @return false when another writer inserted the document first */
    private boolean insert(String id, WakeState state) {
        try {
            collection.insertOne(encode(id, state, 1L));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    private Document encode(String id, WakeState state, long version) {
        final List<Date> recent = new ArrayList<>();
        state.getRecentWakes().forEach(at -> recent.add(Date.from(at)));
        return new Document(DocumentKeys.F_ID, id).append(F_RECENT_WAKES, recent)
                .append(F_CONSECUTIVE, state.getConsecutive())
                .append(F_TURN_COUNT_AFTER_WAKE,
                        state.getTurnCountAfterWake().isPresent() ? state.getTurnCountAfterWake().getAsInt() : null)
                .append(F_OUTSTANDING, state.isOutstanding()).append(F_VERSION, version)
                .append(F_EXPIRES_AT, Date.from(clock.instant().plus(ttl)));
    }

    private static WakeState decode(Document doc) {
        final List<Instant> recent = new ArrayList<>();
        for (Date at : doc.getList(F_RECENT_WAKES, Date.class, List.of())) {
            recent.add(at.toInstant());
        }
        final Number turnCount = doc.get(F_TURN_COUNT_AFTER_WAKE, Number.class);
        return WakeState.of(recent, doc.get(F_CONSECUTIVE, Number.class).intValue(),
                turnCount == null ? null : turnCount.intValue(), Boolean.TRUE.equals(doc.getBoolean(F_OUTSTANDING)));
    }
}
