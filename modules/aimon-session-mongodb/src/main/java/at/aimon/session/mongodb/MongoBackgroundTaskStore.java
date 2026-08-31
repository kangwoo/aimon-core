package at.aimon.session.mongodb;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;

import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.BackgroundTaskStore;
import at.aimon.core.subagent.task.TaskQuery;
import at.aimon.session.mongodb.internal.BackgroundTaskDocumentCodec;
import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * MongoDB-backed {@link BackgroundTaskStore}: the shared-backend metadata store that lets {@code Task.list} / status
 * queries observe background subagent tasks spawned on <em>any</em> instance (subagent design §4).
 *
 * <p>
 * <b>Layout.</b> Each task snapshot is one document in {@code background_task}, keyed by {@code _id = taskId} (see
 * {@link BackgroundTaskDocumentCodec}). {@link #put(BackgroundTask)} upserts by id, so an insert and a subsequent
 * overwrite share the same document.
 *
 * <p>
 * <b>Terminal-guarded transition.</b> {@link #transition(String, BackgroundTaskState)} and
 * {@link #heartbeat(String, Instant)} are single atomic {@code findOneAndUpdate} operations whose filter excludes tasks
 * already in a {@link BackgroundTaskState#isTerminal() terminal} state. When the filter matches nothing — the task is
 * unknown or already terminal — the update is a no-op and returns {@link Optional#empty()}, making completion
 * notification, duplicate {@code stop} requests, and post-completion heartbeats idempotent. This is the same guard the
 * in-memory and Redis reference stores enforce, expressed directly as a Mongo conditional update rather than a
 * read-modify-write.
 *
 * <p>
 * The runtime never creates the collection or its indexes — operators apply {@code db/mongodb/init.js} once per
 * cluster.
 * If the indexes are missing, {@link #list(TaskQuery)} still works but degrades to a collection scan. All backend
 * errors
 * surface as {@link IllegalStateException}; the Mongo SDK exception never crosses the module boundary.
 */
public final class MongoBackgroundTaskStore implements BackgroundTaskStore {

    private final MongoCollection<Document> collection;
    private final BackgroundTaskDocumentCodec codec;
    private final Clock clock;

    /**
     * Creates a store on the {@code background_task} collection with the system UTC clock.
     *
     * @param database
     *            the Mongo database (must not be null; owned by the caller)
     */
    public MongoBackgroundTaskStore(MongoDatabase database) {
        this(database, DocumentKeys.COLL_BACKGROUND_TASK, Clock.systemUTC());
    }

    /**
     * Creates a store with explicit collaborators.
     *
     * @param database
     *            the Mongo database (must not be null; owned by the caller)
     * @param collectionName
     *            the collection holding task snapshots (must not be null)
     * @param clock
     *            the clock used to stamp terminal-transition end times (must not be null)
     */
    public MongoBackgroundTaskStore(MongoDatabase database, String collectionName, Clock clock) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.codec = new BackgroundTaskDocumentCodec();
    }

    @Override
    public void put(BackgroundTask task) {
        Objects.requireNonNull(task, "task must not be null");
        try {
            collection.replaceOne(Filters.eq(DocumentKeys.F_ID, task.getTaskId()), codec.encode(task),
                    new ReplaceOptions().upsert(true));
        } catch (MongoException e) {
            throw new IllegalStateException("Mongo error during put for task " + task.getTaskId(), e);
        }
    }

    @Override
    public Optional<BackgroundTask> find(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        try {
            final Document doc = collection.find(Filters.eq(DocumentKeys.F_ID, taskId)).first();
            return doc == null ? Optional.empty() : Optional.of(codec.decode(doc));
        } catch (MongoException e) {
            throw new IllegalStateException("Mongo error during find for task " + taskId, e);
        }
    }

    @Override
    public List<BackgroundTask> list(TaskQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        try {
            final List<BackgroundTask> result = new ArrayList<>();
            for (Document doc : collection.find()) {
                final BackgroundTask task = codec.decode(doc);
                if (query.matches(task)) {
                    result.add(task);
                }
            }
            return result;
        } catch (MongoException e) {
            throw new IllegalStateException("Mongo error during list", e);
        }
    }

    @Override
    public Optional<BackgroundTask> transition(String taskId, BackgroundTaskState to) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(to, "target state must not be null");
        try {
            final Document set = new Document(DocumentKeys.F_STATE, to.name());
            if (to.isTerminal()) {
                set.append(DocumentKeys.F_END_TIME, Date.from(clock.instant()));
            }
            return applyGuardedUpdate(taskId, set);
        } catch (MongoException e) {
            throw new IllegalStateException("Mongo error during transition for task " + taskId, e);
        }
    }

    @Override
    public Optional<BackgroundTask> heartbeat(String taskId, Instant at) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(at, "heartbeat instant must not be null");
        try {
            return applyGuardedUpdate(taskId, new Document(DocumentKeys.F_LAST_HEARTBEAT, Date.from(at)));
        } catch (MongoException e) {
            throw new IllegalStateException("Mongo error during heartbeat for task " + taskId, e);
        }
    }

    @Override
    public void remove(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        try {
            collection.deleteOne(Filters.eq(DocumentKeys.F_ID, taskId));
        } catch (MongoException e) {
            throw new IllegalStateException("Mongo error during remove for task " + taskId, e);
        }
    }

    /**
     * Applies a {@code $set} to the task only while it is non-terminal, returning the updated snapshot or empty when
     * the
     * task is unknown or already terminal.
     */
    private Optional<BackgroundTask> applyGuardedUpdate(String taskId, Document set) {
        final Bson filter = Filters.and(Filters.eq(DocumentKeys.F_ID, taskId),
                Filters.nin(DocumentKeys.F_STATE, BackgroundTaskState.COMPLETED.name(),
                        BackgroundTaskState.FAILED.name(), BackgroundTaskState.KILLED.name()));
        final Document updated = collection.findOneAndUpdate(filter, new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return updated == null ? Optional.empty() : Optional.of(codec.decode(updated));
    }
}
