/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.mongodb;

import java.time.Duration;
import java.util.List;

import org.bson.Document;
import org.testcontainers.containers.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import at.aimon.memory.mongodb.internal.DocumentKeys;

/**
 * Shared test infrastructure: a singleton MongoDB container plus a {@code dropAndApplyDdl} hook that
 * recreates the memory collections + indexes between tests. The DDL mirrors {@code db/mongodb/init.js}
 * (the runtime never executes the script — operators apply it once per cluster), so tests exercise the
 * stores against the real schema.
 */
public final class MongoMemoryTestSupport {

    public static final String DATABASE_NAME = "aimon_memory_test";

    public static final MongoDBContainer MONGO;

    private static final MongoClient SHARED_CLIENT;

    static {
        MONGO = new MongoDBContainer("mongo:7.0").withStartupTimeout(Duration.ofMinutes(2));
        MONGO.start();
        SHARED_CLIENT = MongoClients.create(MONGO.getReplicaSetUrl(DATABASE_NAME));
    }

    private MongoMemoryTestSupport() {
    }

    public static MongoDatabase sharedDatabase() {
        return SHARED_CLIENT.getDatabase(DATABASE_NAME);
    }

    /** Drops the memory collections and reapplies the DDL from {@code init.js} (translated to driver calls). */
    public static void dropAndApplyDdl() {
        final MongoDatabase db = sharedDatabase();
        for (String name : List.of(DocumentKeys.COLL_WORKSPACE, DocumentKeys.COLL_OBSERVATION,
                DocumentKeys.COLL_REPRESENTATION)) {
            try {
                db.getCollection(name).drop();
            } catch (RuntimeException ignored) {
                // best-effort: collection may not exist yet
            }
        }

        db.createCollection(DocumentKeys.COLL_WORKSPACE);
        db.createCollection(DocumentKeys.COLL_OBSERVATION);
        db.createCollection(DocumentKeys.COLL_REPRESENTATION);

        final MongoCollection<Document> obs = db.getCollection(DocumentKeys.COLL_OBSERVATION);
        obs.createIndex(Indexes.ascending(DocumentKeys.WORKSPACE_ID, DocumentKeys.LOCAL_ID),
                new IndexOptions().unique(true).name("uk_workspace_local"));
        obs.createIndex(Indexes.compoundIndex(Indexes.ascending(DocumentKeys.WORKSPACE_ID, DocumentKeys.SUBJECT_TYPE,
                DocumentKeys.SUBJECT_ID, DocumentKeys.SOFT_DELETED_AT), Indexes.descending(DocumentKeys.CREATED_AT)),
                new IndexOptions().name("by_subject_recent"));
        obs.createIndex(
                Indexes.ascending(DocumentKeys.WORKSPACE_ID, DocumentKeys.SUBJECT_TYPE, DocumentKeys.SUBJECT_ID,
                        DocumentKeys.SOFT_DELETED_AT, DocumentKeys.CONFIDENCE),
                new IndexOptions().name("by_subject_confidence"));
        obs.createIndex(Indexes.ascending(DocumentKeys.WORKSPACE_ID, DocumentKeys.SOFT_DELETED_AT),
                new IndexOptions().name("by_workspace_softdeleted"));

        final MongoCollection<Document> reps = db.getCollection(DocumentKeys.COLL_REPRESENTATION);
        reps.createIndex(
                Indexes.compoundIndex(Indexes.ascending(DocumentKeys.WORKSPACE_ID, DocumentKeys.SUBJECT_TYPE,
                        DocumentKeys.SUBJECT_ID), Indexes.descending(DocumentKeys.GENERATED_AT)),
                new IndexOptions().name("by_subject_generated"));
        reps.createIndex(Indexes.ascending(DocumentKeys.WORKSPACE_ID, DocumentKeys.GENERATED_AT),
                new IndexOptions().name("by_workspace_generated"));
    }
}
