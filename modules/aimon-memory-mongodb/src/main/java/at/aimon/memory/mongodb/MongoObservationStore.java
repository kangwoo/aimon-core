/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.mongodb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import at.aimon.core.base.Principal;
import at.aimon.core.base.exception.AimonException;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.memory.mongodb.internal.DocumentKeys;
import at.aimon.memory.mongodb.internal.MongoMemoryCodec;

/**
 * MongoDB-backed {@link ObservationStore} — the metadata side of the design doc §5.2 store/index split.
 *
 * <p>
 * Like {@code PostgresObservationStore}, semantic search is <em>not</em> implemented here:
 * {@link #semanticSearch} throws {@link UnsupportedOperationException}. Compose this store with
 * {@code IndexedObservationStore} + an {@code ObservationIndex} (e.g. a {@code KnowledgeStore}-backed
 * one) to add vector/keyword search without building a parallel RAG stack in the metadata layer.
 *
 * <p>
 * Every query filters out soft-deleted rows ({@code softDeletedAt == null}). {@link #merge} and
 * {@link #softDelete} set {@code softDeletedAt} so the row is retained for the audit window;
 * {@link #purgeSoftDeletedBefore} hard-deletes rows past it. The database is the single source of
 * truth (no in-memory mirror) so the store is multi-instance safe.
 *
 * <p>
 * Schema is operator-applied via {@code db/mongodb/init.js}; the runtime never creates indexes.
 */
public final class MongoObservationStore implements ObservationStore {

    private static final Logger log = LoggerFactory.getLogger(MongoObservationStore.class);

    private final MongoCollection<Document> observations;
    private final MongoCollection<Document> workspaces;
    private final MongoMemoryCodec codec;

    /**
     * Creates a store on the {@value DocumentKeys#COLL_OBSERVATION} / {@value DocumentKeys#COLL_WORKSPACE} collections.
     */
    public MongoObservationStore(MongoDatabase database) {
        Objects.requireNonNull(database, "database must not be null");
        this.observations = database.getCollection(DocumentKeys.COLL_OBSERVATION);
        this.workspaces = database.getCollection(DocumentKeys.COLL_WORKSPACE);
        this.codec = new MongoMemoryCodec();
    }

    @Override
    public Observation save(Observation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        try {
            // Upsert on the (workspaceId, localId) logical key; replacing resets softDeletedAt to null,
            // so re-saving a previously soft-deleted id resurrects it as live.
            observations.replaceOne(idFilter(observation.getId()), codec.observationToDocument(observation),
                    new ReplaceOptions().upsert(true));
            return observation;
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during observation save: " + observation.getId(), e);
        }
    }

    @Override
    public Optional<Observation> findById(ObservationId id) {
        Objects.requireNonNull(id, "id must not be null");
        try {
            Document doc = observations.find(Filters.and(idFilter(id), liveFilter())).first();
            if (doc == null) {
                return Optional.empty();
            }
            return Optional.of(codec.observationFromDocument(doc, loadWorkspace(id.getWorkspaceId())));
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during observation findById: " + id, e);
        }
    }

    @Override
    public List<Observation> findBySubject(PeerView subject, int limit) {
        Objects.requireNonNull(subject, "subject must not be null");
        requirePositive(limit);
        try {
            return hydrate(observations.find(Filters.and(subjectFilter(subject), liveFilter()))
                    .sort(Sorts.descending(DocumentKeys.CREATED_AT)).limit(limit), subject.getWorkspace());
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during findBySubject: " + subject.key(), e);
        }
    }

    @Override
    public long count(PeerView subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        try {
            return observations.countDocuments(Filters.and(subjectFilter(subject), liveFilter()));
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during count: " + subject.key(), e);
        }
    }

    /**
     * Always throws {@link UnsupportedOperationException} — this store is metadata-only (design §5.2).
     * Wrap it in {@code IndexedObservationStore} with an {@code ObservationIndex} to get search.
     */
    @Override
    public List<Observation> semanticSearch(PeerView subject, String query, int topK) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(query, "query must not be null");
        requirePositive(topK);
        throw new UnsupportedOperationException("MongoObservationStore does not implement semanticSearch directly; "
                + "wrap it in IndexedObservationStore with an ObservationIndex");
    }

    @Override
    public List<Observation> findByConfidenceBelow(PeerView subject, double threshold, int limit) {
        Objects.requireNonNull(subject, "subject must not be null");
        requirePositive(limit);
        try {
            return hydrate(observations
                    .find(Filters.and(subjectFilter(subject), liveFilter(),
                            Filters.lt(DocumentKeys.CONFIDENCE, threshold)))
                    .sort(Sorts.ascending(DocumentKeys.CONFIDENCE)).limit(limit), subject.getWorkspace());
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during findByConfidenceBelow: " + subject.key(), e);
        }
    }

    @Override
    public List<PeerView> findSubjects(Workspace workspace, int limit) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        requirePositive(limit);
        try {
            Document group = new Document(DocumentKeys.ID, new Document("t", "$" + DocumentKeys.SUBJECT_TYPE)
                    .append("i", "$" + DocumentKeys.SUBJECT_ID).append("d", "$" + DocumentKeys.SUBJECT_DISPLAY_NAME));
            List<Bson> pipeline = List.of(
                    new Document("$match",
                            new Document(DocumentKeys.WORKSPACE_ID, workspace.getId())
                                    .append(DocumentKeys.SOFT_DELETED_AT, null)),
                    new Document("$group", group), new Document("$limit", limit));
            List<PeerView> out = new ArrayList<>();
            try (MongoCursor<Document> cursor = observations.aggregate(pipeline).iterator()) {
                while (cursor.hasNext()) {
                    Document key = cursor.next().get(DocumentKeys.ID, Document.class);
                    Principal principal = Principal.builder().type(Principal.Type.valueOf(key.getString("t")))
                            .id(key.getString("i")).displayName(key.getString("d")).build();
                    out.add(PeerView.of(workspace, principal));
                }
            }
            return List.copyOf(out);
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during findSubjects: " + workspace.getId(), e);
        }
    }

    @Override
    public void delete(ObservationId id) {
        Objects.requireNonNull(id, "id must not be null");
        try {
            observations.deleteOne(idFilter(id));
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during delete: " + id, e);
        }
    }

    @Override
    public Observation merge(ObservationId winner, ObservationId loser, Observation merged) {
        Objects.requireNonNull(winner, "winner must not be null");
        Objects.requireNonNull(loser, "loser must not be null");
        Objects.requireNonNull(merged, "merged must not be null");
        if (winner.equals(loser)) {
            throw new IllegalArgumentException("winner and loser must differ: " + winner);
        }
        if (!merged.getId().equals(winner)) {
            throw new IllegalArgumentException(
                    "merged observation id (" + merged.getId() + ") must equal winner (" + winner + ")");
        }
        if (!winner.getWorkspaceId().equals(loser.getWorkspaceId())) {
            throw new IllegalArgumentException("winner workspace (" + winner.getWorkspaceId()
                    + ") must equal loser workspace (" + loser.getWorkspaceId() + ")");
        }
        try {
            // Soft-delete the loser FIRST, then publish the merged winner. Without a multi-document
            // transaction this is the safe non-transactional order: a reader in the window (or after a crash
            // between the two writes) sees the winner only — never the winner AND loser as two live rows,
            // which would inflate count()/findBySubject(). If the second write fails the loser is already
            // hidden (recoverable from the audit window) and re-running merge() converges.
            observations.updateOne(idFilter(loser),
                    Updates.set(DocumentKeys.SOFT_DELETED_AT, Date.from(Instant.now())));
            observations.replaceOne(idFilter(winner), codec.observationToDocument(merged),
                    new ReplaceOptions().upsert(true));
            return merged;
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during merge winner=" + winner + " loser=" + loser, e);
        }
    }

    @Override
    public void softDelete(ObservationId id) {
        Objects.requireNonNull(id, "id must not be null");
        try {
            observations.updateOne(Filters.and(idFilter(id), liveFilter()),
                    Updates.set(DocumentKeys.SOFT_DELETED_AT, Date.from(Instant.now())));
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during softDelete: " + id, e);
        }
    }

    @Override
    public int purgeSoftDeletedBefore(Workspace workspace, Instant cutoff) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        try {
            long deleted = observations.deleteMany(Filters.and(Filters.eq(DocumentKeys.WORKSPACE_ID, workspace.getId()),
                    Filters.ne(DocumentKeys.SOFT_DELETED_AT, null),
                    Filters.lt(DocumentKeys.SOFT_DELETED_AT, Date.from(cutoff)))).getDeletedCount();
            return (int) Math.min(deleted, Integer.MAX_VALUE);
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during purgeSoftDeletedBefore: " + workspace.getId(), e);
        }
    }

    // --- helpers -------------------------------------------------------------

    private List<Observation> hydrate(MongoIterable<Document> docs, Workspace workspace) {
        List<Observation> out = new ArrayList<>();
        try (MongoCursor<Document> cursor = docs.iterator()) {
            while (cursor.hasNext()) {
                out.add(codec.observationFromDocument(cursor.next(), workspace));
            }
        }
        return List.copyOf(out);
    }

    private Workspace loadWorkspace(String workspaceId) {
        Document doc = workspaces.find(Filters.eq(DocumentKeys.ID, workspaceId)).first();
        if (doc != null) {
            return codec.workspaceFromDocument(doc);
        }
        // The workspace row is gone but observations remain (deleted out of order). Hydrate a minimal
        // workspace so the observation is still usable, but warn — its displayName/metadata are unknown.
        log.warn("Observation workspace {} not found; hydrating a minimal Workspace (displayName/metadata lost)",
                workspaceId);
        return Workspace.builder().id(workspaceId).build();
    }

    private static Bson idFilter(ObservationId id) {
        return Filters.and(Filters.eq(DocumentKeys.WORKSPACE_ID, id.getWorkspaceId()),
                Filters.eq(DocumentKeys.LOCAL_ID, id.getLocalId()));
    }

    private static Bson subjectFilter(PeerView subject) {
        return Filters.and(Filters.eq(DocumentKeys.WORKSPACE_ID, subject.getWorkspace().getId()),
                Filters.eq(DocumentKeys.SUBJECT_TYPE, subject.getPrincipal().getType().name()),
                Filters.eq(DocumentKeys.SUBJECT_ID, subject.getPrincipal().getId()));
    }

    private static Bson liveFilter() {
        return Filters.eq(DocumentKeys.SOFT_DELETED_AT, null);
    }

    private static void requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit/topK must be >= 1, got " + limit);
        }
    }
}
