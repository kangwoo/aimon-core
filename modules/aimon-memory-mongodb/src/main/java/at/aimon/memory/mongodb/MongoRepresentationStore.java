/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.mongodb;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import at.aimon.core.base.exception.AimonException;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.memory.mongodb.internal.DocumentKeys;
import at.aimon.memory.mongodb.internal.MongoMemoryCodec;

/**
 * MongoDB-backed {@link RepresentationStore}. Append-only: each {@link #save} inserts a new snapshot
 * document into {@code mem_representation}; {@code findLatest*} returns the newest matching one and
 * {@link #deleteOlderThan} prunes by {@code generatedAt}. The database is the single source of truth
 * (no in-memory mirror), so the store is multi-instance safe.
 *
 * <p>
 * Each document embeds a workspace snapshot (representations are point-in-time), so reads reconstruct
 * the {@link PeerView}s without a separate workspace lookup. Schema is operator-applied via
 * {@code db/mongodb/init.js}.
 */
public final class MongoRepresentationStore implements RepresentationStore {

    private final MongoCollection<Document> collection;
    private final MongoMemoryCodec codec;

    /** Creates a store on the {@value DocumentKeys#COLL_REPRESENTATION} collection. */
    public MongoRepresentationStore(MongoDatabase database) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database.getCollection(DocumentKeys.COLL_REPRESENTATION);
        this.codec = new MongoMemoryCodec();
    }

    @Override
    public Representation save(Representation representation) {
        Objects.requireNonNull(representation, "representation must not be null");
        try {
            collection.insertOne(codec.representationToDocument(representation));
            return representation;
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during representation save: " + representation.getSubject().key(),
                    e);
        }
    }

    @Override
    public Optional<Representation> findLatestGlobal(PeerView subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        try {
            Bson filter = Filters.and(subjectFilter(subject), Filters.exists(DocumentKeys.OBSERVER_TYPE, false));
            Document doc = collection.find(filter).sort(Sorts.descending(DocumentKeys.GENERATED_AT)).first();
            return Optional.ofNullable(doc).map(codec::representationFromDocument);
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during findLatestGlobal: " + subject.key(), e);
        }
    }

    @Override
    public Optional<Representation> findLatestLocal(PeerView subject, PeerView observer, String sessionId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        try {
            Bson sessionFilter = sessionId == null
                    ? Filters.eq(DocumentKeys.SESSION_ID, null)
                    : Filters.eq(DocumentKeys.SESSION_ID, sessionId);
            Bson filter = Filters.and(subjectFilter(subject),
                    Filters.eq(DocumentKeys.OBSERVER_TYPE, observer.getPrincipal().getType().name()),
                    Filters.eq(DocumentKeys.OBSERVER_ID, observer.getPrincipal().getId()), sessionFilter);
            Document doc = collection.find(filter).sort(Sorts.descending(DocumentKeys.GENERATED_AT)).first();
            return Optional.ofNullable(doc).map(codec::representationFromDocument);
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during findLatestLocal: " + subject.key(), e);
        }
    }

    @Override
    public void deleteOlderThan(Workspace workspace, Instant cutoff) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        try {
            collection.deleteMany(Filters.and(Filters.eq(DocumentKeys.WORKSPACE_ID, workspace.getId()),
                    Filters.lt(DocumentKeys.GENERATED_AT, Date.from(cutoff))));
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during deleteOlderThan: " + workspace.getId(), e);
        }
    }

    private static Bson subjectFilter(PeerView subject) {
        return Filters.and(Filters.eq(DocumentKeys.WORKSPACE_ID, subject.getWorkspace().getId()),
                Filters.eq(DocumentKeys.SUBJECT_TYPE, subject.getPrincipal().getType().name()),
                Filters.eq(DocumentKeys.SUBJECT_ID, subject.getPrincipal().getId()));
    }
}
