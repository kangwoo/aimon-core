package at.aimon.session.mongodb.internal;

import java.util.Optional;

import org.bson.BsonDocument;

/**
 * In-memory holder for the most recent change-stream resume token, used by {@code ChangeStreamWatcher} when
 * reconnecting after a watcher restart.
 *
 * <p>
 * Per-node, not per-session: a single resume token rolls forward as the watcher dispatches events for any
 * session. After a fail-over the watcher passes the last seen token to {@code watch().resumeAfter(...)} so
 * already-handled events are skipped. If the token has aged past the oplog window the driver throws
 * {@code MongoChangeStreamException} (history-lost); callers fall back to opening without a resume token.
 *
 * <p>
 * Thread-safety: writes are best-effort in the dispatcher thread, reads happen on watcher restart. {@code volatile} on
 * the reference is sufficient — no compound state to coordinate.
 */
public final class ResumeTokenStore {

    private volatile BsonDocument lastToken;

    public ResumeTokenStore() {
    }

    public void update(BsonDocument token) {
        if (token != null) {
            this.lastToken = token;
        }
    }

    public Optional<BsonDocument> last() {
        return Optional.ofNullable(lastToken);
    }

    public void clear() {
        this.lastToken = null;
    }
}
