// init.js — apply with: mongosh "<connection-uri>" db/mongodb/init.js
//
// Creates collections and indexes for aimon-session-mongodb. Idempotent. The runtime
// never executes this script; ops applies it once per cluster (and re-runs after schema bumps).
//
// All names are configurable via the manager builder; if you override them, edit this script
// to match before applying.

const dbName = "aimon_session";
const target = db.getSiblingDB(dbName);

// --- collections -------------------------------------------------------------
//
// db.createCollection is idempotent in mongosh as long as the options match;
// wrap each in a defensive listCollections check so re-runs are clean.

const ensureCollection = (name, opts) => {
    const exists = target.getCollectionInfos({ name: name }).length > 0;
    if (!exists) {
        target.createCollection(name, opts || {});
    }
};

ensureCollection("conversation_locks");
ensureCollection("conversation_inbox");
ensureCollection("idempotency_entries");
ensureCollection("conversation_signals", { capped: true, size: 64 * 1024 * 1024 });
ensureCollection("background_task");

// session_records is the one collection named for the session rather than the conversation. The
// "conversation_*" spellings above are contracts with documents already on disk; this collection
// had none when it was added, so it got the name the code uses. It is frozen from here on all the
// same — MongoSchemaFreezeTest pins it, because from the first deployment there is data behind it.
ensureCollection("session_records");

// --- indexes -----------------------------------------------------------------
//
// createIndex is idempotent when the spec + name match; we always pass an explicit
// name so a future bump is an explicit drop+recreate rather than silent rename.

target.conversation_inbox.createIndex(
    { conversationId: 1, priority: 1, deliveredAt: 1 },
    { name: "by_conv_priority_fifo" }
);

target.idempotency_entries.createIndex(
    { expiresAt: 1 },
    { name: "ttl_expires_at", expireAfterSeconds: 0 }
);

target.idempotency_entries.createIndex(
    { status: 1, lastTouchedAt: 1 },
    { name: "by_status_touch" }
);

// background_task: Task.list scans the whole collection and filters in the store, but the
// state / contextId indexes keep the common scoped listings (by state, by owning context) cheap.

target.background_task.createIndex(
    { state: 1 },
    { name: "by_state" }
);

target.background_task.createIndex(
    { contextId: 1 },
    { name: "by_context" }
);

// --- replica-set sanity check -----------------------------------------------
//
// Change Streams (signal bus) need a replica set. Print a clear error if the
// admin forgot to rs.initiate(); the script itself does not initiate.

try {
    const status = db.adminCommand({ replSetGetStatus: 1 });
    if (status.ok !== 1) {
        print("WARNING: not a replica set — aimon-session-mongodb signal bus will fail.");
        print("  Run: rs.initiate() (single-node) or configure a real replica set.");
    }
} catch (e) {
    print("WARNING: replSetGetStatus failed (" + e.message + ") — change streams require a replica set.");
    print("  Run: rs.initiate() (single-node) or configure a real replica set.");
}

print("aimon-session-mongodb init.js applied to '" + dbName + "'.");
