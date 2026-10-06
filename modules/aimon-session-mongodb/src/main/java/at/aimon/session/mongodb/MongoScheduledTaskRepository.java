package at.aimon.session.mongodb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;

import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.SchedulingException;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.mongodb.internal.ScheduledTaskDocumentCodec;

/**
 * MongoDB-backed {@link ScheduledTaskRepository}: scheduled tasks that are still there after a restart, and that
 * every node of a cluster reads from one place.
 *
 * <p>
 * One document per task in {@code scheduled_tasks}, keyed by the task id. {@link #save} is an upsert;
 * {@link #updateIfPresent} is a single {@code replaceOne} without upsert, which is the atomic "replace only if it is
 * still stored" the SPI demands — the server matches and writes in one operation, so a concurrent
 * {@link #deleteById} either lands before it (nothing matches, nothing is written) or after it (the replaced document
 * is deleted), and never between.
 *
 * <h2>What this is half of</h2>
 *
 * <p>
 * A scheduled task also needs a trigger, and that lives in the {@code TaskScheduler}. With the default in-memory
 * scheduler the engine rebuilds the triggers from this repository when it starts
 * ({@code ScheduledTaskManager.rehydrate}), which is enough for one node. On one node of several it does that only if
 * the scheduler's triggers are shared by the deployment or an execution guard was supplied — see
 * {@code SchedulingEngineBuilder.multiNode}. Execution history is a separate repository and is not stored here.
 *
 * <h2>Unreadable documents</h2>
 *
 * <p>
 * A document this build cannot decode — written by hand, or by a build with a field this one does not know how to
 * read — is skipped with a warning by the queries that return lists, so that one bad record does not take every
 * other task's schedule with it at start. {@link #findById} throws instead: the caller asked for that task, and
 * "absent" would be a wrong answer. {@link #existsById} and {@link #countByOwner} read the stored fields and so
 * still see it.
 *
 * <p>
 * <b>The consequence is that such a task cannot be managed through the scheduling API.</b> Its owner does not see it
 * listed, and cancel, interrupt and enable/disable all read it by id and fail. It still counts against the owner's
 * quota. Removing it is an operator's job, in the database. An unknown <em>extra</em> field is not this case: it is
 * ignored on read and kept on {@link #recordExecution}, though {@link #save} and {@link #updateIfPresent} replace the
 * whole document and drop it.
 *
 * <h2>One collection, one application</h2>
 *
 * <p>
 * Nothing in a document says which application wrote it. Two applications pointed at the same collection each
 * schedule the other's tasks and fail them at every fire; give each its own collection name.
 *
 * <p>
 * The collection and its indexes come from {@code db/mongodb/init.js}; the runtime runs no DDL. The
 * {@link MongoDatabase} belongs to the caller.
 */
public final class MongoScheduledTaskRepository implements ScheduledTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(MongoScheduledTaskRepository.class);

    private final MongoCollection<Document> collection;
    private final ScheduledTaskDocumentCodec codec = new ScheduledTaskDocumentCodec();

    /**
     * Creates a repository on the default collection.
     *
     * @param database
     *            the database holding the collection (must not be null; owned by the caller)
     */
    public MongoScheduledTaskRepository(MongoDatabase database) {
        this(database, DocumentKeys.COLL_SCHEDULED_TASKS);
    }

    /**
     * Creates a repository on a named collection.
     *
     * @param database
     *            the database holding the collection (must not be null; owned by the caller)
     * @param collectionName
     *            the collection holding the task documents (must not be null)
     */
    public MongoScheduledTaskRepository(MongoDatabase database, String collectionName) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database
                .getCollection(Objects.requireNonNull(collectionName, "collectionName must not be null"));
    }

    @Override
    public void save(ScheduledTask task) {
        Objects.requireNonNull(task, "Task cannot be null");
        try {
            collection.replaceOne(byId(task.getId()), codec.encode(task), new ReplaceOptions().upsert(true));
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error saving task '" + task.getId() + "'", e);
        }
    }

    @Override
    public boolean updateIfPresent(ScheduledTask task) {
        Objects.requireNonNull(task, "Task cannot be null");
        try {
            // No upsert: the match and the write are one server-side operation, which is the atomicity the SPI asks
            // for. matchedCount rather than modifiedCount — replacing a document with an identical one matches and
            // modifies nothing, and that is still "the task was present".
            return collection.replaceOne(byId(task.getId()), codec.encode(task)).getMatchedCount() > 0;
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error updating task '" + task.getId() + "'", e);
        }
    }

    @Override
    public boolean recordExecution(ScheduledTaskId taskId, Instant executedAt) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(executedAt, "Execution time cannot be null");
        try {
            // One field, set in place. The rest of the document — including anything a newer build wrote that this one
            // does not know, and an "enabled" that changed while the run was in flight — is left exactly as stored.
            return collection.updateOne(byId(taskId), Updates.set(ScheduledTaskDocumentCodec.F_LAST_EXECUTED_AT,
                    ScheduledTaskDocumentCodec.encodeInstant(executedAt))).getMatchedCount() > 0;
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error recording the run of task '" + taskId + "'", e);
        }
    }

    @Override
    public Optional<ScheduledTask> findById(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        final Document doc;
        try {
            doc = collection.find(byId(taskId)).first();
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error reading task '" + taskId + "'", e);
        }
        if (doc == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.decode(doc));
        } catch (RuntimeException e) {
            throw new SchedulingException("Stored task '" + taskId + "' cannot be read by this build", e);
        }
    }

    @Override
    public List<ScheduledTask> findAll() {
        return query(new Document());
    }

    @Override
    public List<ScheduledTask> findByEnabledTrue() {
        return query(Filters.eq(ScheduledTaskDocumentCodec.F_ENABLED, true));
    }

    @Override
    public List<ScheduledTask> findByOwner(Principal owner) {
        Objects.requireNonNull(owner, "Owner cannot be null");
        return query(ownedBy(owner));
    }

    @Override
    public int countByOwner(Principal owner) {
        Objects.requireNonNull(owner, "Owner cannot be null");
        try {
            // Counted on the stored fields, so a document this build cannot decode still counts against its owner.
            return Math.toIntExact(collection.countDocuments(ownedBy(owner)));
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error counting the tasks of " + owner, e);
        }
    }

    @Override
    public List<ScheduledTask> findByOwnerAndEnabledTrue(Principal owner) {
        Objects.requireNonNull(owner, "Owner cannot be null");
        return query(Filters.and(ownedBy(owner), Filters.eq(ScheduledTaskDocumentCodec.F_ENABLED, true)));
    }

    @Override
    public void deleteById(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        try {
            collection.deleteOne(byId(taskId));
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error deleting task '" + taskId + "'", e);
        }
    }

    @Override
    public boolean existsById(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        try {
            // Not the default findById().isPresent(): existence must not depend on the document being decodable.
            return collection.countDocuments(byId(taskId)) > 0;
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error checking task '" + taskId + "'", e);
        }
    }

    @Override
    public void clear() {
        try {
            collection.deleteMany(new Document());
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error clearing scheduled tasks", e);
        }
    }

    private List<ScheduledTask> query(Bson filter) {
        final List<ScheduledTask> tasks = new ArrayList<>();
        try {
            for (Document doc : collection.find(filter)) {
                try {
                    tasks.add(codec.decode(doc));
                } catch (RuntimeException e) {
                    log.warn("Skipping stored task '{}' that this build cannot read: {}", doc.get(DocumentKeys.F_ID),
                            e.toString());
                }
            }
        } catch (MongoException e) {
            throw new SchedulingException("Mongo error listing scheduled tasks", e);
        }
        return List.copyOf(tasks);
    }

    private static Bson byId(ScheduledTaskId taskId) {
        return Filters.eq(DocumentKeys.F_ID, taskId.value());
    }

    /** Matches on the two fields {@link Principal#equals} compares; the display name is not identity. */
    private static Bson ownedBy(Principal owner) {
        return Filters.and(Filters.eq(ScheduledTaskDocumentCodec.F_OWNER_TYPE, owner.getType().name()),
                Filters.eq(ScheduledTaskDocumentCodec.F_OWNER_ID, owner.getId()));
    }
}
