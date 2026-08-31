/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.mongodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

import at.aimon.core.base.Principal;
import at.aimon.core.base.exception.AimonException;
import at.aimon.core.memory.DefaultWorkspaceAccessPolicy;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceAccessPolicy;
import at.aimon.core.memory.WorkspaceStore;
import at.aimon.memory.mongodb.internal.DocumentKeys;
import at.aimon.memory.mongodb.internal.MongoMemoryCodec;

/**
 * MongoDB-backed {@link WorkspaceStore}. Each workspace is one document in {@code mem_workspace}
 * keyed by the workspace id as {@code _id}. Thread-safe (the driver pools connections); multi-instance
 * safe (the database is the single source of truth — no in-memory mirror).
 *
 * <p>
 * {@link #findAll(Principal)} is filtered by a {@link WorkspaceAccessPolicy} (default
 * {@link DefaultWorkspaceAccessPolicy}) so a requester only lists workspaces it is entitled to.
 *
 * <p>
 * Schema (collections + indexes) is operator-applied via {@code db/mongodb/init.js}; the runtime
 * never creates collections or indexes.
 */
public final class MongoWorkspaceStore implements WorkspaceStore {

    private final MongoCollection<Document> collection;
    private final MongoMemoryCodec codec;
    private final WorkspaceAccessPolicy accessPolicy;

    /** Creates a store on the {@value DocumentKeys#COLL_WORKSPACE} collection with the default access policy. */
    public MongoWorkspaceStore(MongoDatabase database) {
        this(database, WorkspaceAccessPolicy.defaults());
    }

    /**
     * @param database
     *            database holding the {@value DocumentKeys#COLL_WORKSPACE} collection (must not be null)
     * @param accessPolicy
     *            policy applied by {@link #findAll(Principal)} (must not be null)
     */
    public MongoWorkspaceStore(MongoDatabase database, WorkspaceAccessPolicy accessPolicy) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database.getCollection(DocumentKeys.COLL_WORKSPACE);
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.codec = new MongoMemoryCodec();
    }

    @Override
    public Workspace create(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        try {
            // Insert directly and let the unique _id detect duplicates — atomic, unlike a check-then-insert
            // which races under concurrent create() of the same id.
            collection.insertOne(codec.workspaceToDocument(workspace));
            return workspace;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new IllegalStateException("Workspace already exists: " + workspace.getId(), e);
            }
            throw new AimonException("MongoDB error during workspace create: " + workspace.getId(), e);
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during workspace create: " + workspace.getId(), e);
        }
    }

    @Override
    public Optional<Workspace> findById(String id) {
        Objects.requireNonNull(id, "id cannot be null");
        try {
            Document doc = collection.find(Filters.eq(DocumentKeys.ID, id)).first();
            return Optional.ofNullable(doc).map(codec::workspaceFromDocument);
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during workspace findById: " + id, e);
        }
    }

    @Override
    public List<Workspace> findAll(Principal requester) {
        Objects.requireNonNull(requester, "requester cannot be null");
        try {
            List<Workspace> out = new ArrayList<>();
            try (MongoCursor<Document> cursor = collection.find().iterator()) {
                while (cursor.hasNext()) {
                    Workspace ws = codec.workspaceFromDocument(cursor.next());
                    if (accessPolicy.canAccess(requester, ws)) {
                        out.add(ws);
                    }
                }
            }
            return List.copyOf(out);
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during workspace findAll", e);
        }
    }

    @Override
    public void delete(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        try {
            collection.deleteOne(Filters.eq(DocumentKeys.ID, workspace.getId()));
        } catch (MongoException e) {
            throw new AimonException("MongoDB error during workspace delete: " + workspace.getId(), e);
        }
    }
}
