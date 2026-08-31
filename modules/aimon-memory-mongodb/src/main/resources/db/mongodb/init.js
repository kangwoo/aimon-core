// init.js — apply with: mongosh "<connection-uri>" db/mongodb/init.js
//
// Creates collections and indexes for aimon-memory-mongodb. Idempotent. The runtime never
// executes this script; ops applies it once per cluster (and re-runs after schema bumps),
// mirroring the operator-applied V1__init.sql of aimon-memory-postgres.
//
// Default database name is "aimon_memory"; if you point the stores at a different database,
// edit dbName below before applying.

const dbName = "aimon_memory";
const target = db.getSiblingDB(dbName);

const ensureCollection = (name, opts) => {
    const exists = target.getCollectionInfos({ name: name }).length > 0;
    if (!exists) {
        target.createCollection(name, opts || {});
    }
};

ensureCollection("mem_workspace");
ensureCollection("mem_observation");
ensureCollection("mem_representation");

// --- indexes -----------------------------------------------------------------
// createIndex is idempotent when the spec + name match; explicit names make a future bump an
// explicit drop+recreate rather than a silent rename.

// mem_workspace uses the workspace id as _id, so no extra index is required for findById/delete.

// mem_observation: (workspaceId, localId) is the logical key for save-upsert and findById.
target.mem_observation.createIndex(
    { workspaceId: 1, localId: 1 },
    { name: "uk_workspace_local", unique: true }
);
// findBySubject (newest first) + count, filtered to live rows.
target.mem_observation.createIndex(
    { workspaceId: 1, subjectType: 1, subjectId: 1, softDeletedAt: 1, createdAt: -1 },
    { name: "by_subject_recent" }
);
// findByConfidenceBelow (ascending confidence), filtered to live rows.
target.mem_observation.createIndex(
    { workspaceId: 1, subjectType: 1, subjectId: 1, softDeletedAt: 1, confidence: 1 },
    { name: "by_subject_confidence" }
);
// findSubjects (distinct live subjects) + purgeSoftDeletedBefore (workspace + softDeletedAt).
target.mem_observation.createIndex(
    { workspaceId: 1, softDeletedAt: 1 },
    { name: "by_workspace_softdeleted" }
);

// mem_representation: latest-by-subject (global + local) and retention sweep.
target.mem_representation.createIndex(
    { workspaceId: 1, subjectType: 1, subjectId: 1, generatedAt: -1 },
    { name: "by_subject_generated" }
);
target.mem_representation.createIndex(
    { workspaceId: 1, generatedAt: 1 },
    { name: "by_workspace_generated" }
);

print("aimon-memory-mongodb init.js applied to '" + dbName + "'.");
