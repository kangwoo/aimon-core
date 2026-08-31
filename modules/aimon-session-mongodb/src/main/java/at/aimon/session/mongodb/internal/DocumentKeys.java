package at.aimon.session.mongodb.internal;

/**
 * Field-name and collection-name constants shared across the MongoDB-backed SPI implementations.
 *
 * <p>
 * Field names mirror the schema in {@code init.js} (design §3). Keeping them in one place avoids drift between the four
 * SPI classes and the JS DDL.
 */
public final class DocumentKeys {

    /**
     * Default collection holding one document per session lock.
     *
     * <p>
     * <b>FROZEN WIRE NAME — do not rename alongside the Java identifier.</b> See {@link #COLL_SIGNALS} for why the
     * collection names are more exposed than the field names, and {@code MongoSchemaFreezeTest} for the pin. A rename
     * here points the runtime at an empty collection and silently drops every live lease, so two nodes can hold the
     * same session at once.
     */
    public static final String COLL_LOCKS = "conversation_locks";

    /**
     * Default collection holding inbox messages, one document per envelope.
     *
     * <p>
     * <b>FROZEN WIRE NAME — do not rename alongside the Java identifier.</b> A rename strands the undelivered backlog:
     * the messages stay where they are and the runtime reads a collection Mongo creates empty on first insert.
     */
    public static final String COLL_INBOX = "conversation_inbox";

    /** Default collection holding idempotency entries, keyed by idempotency key. */
    public static final String COLL_IDEMPOTENCY = "idempotency_entries";

    /**
     * Default capped collection holding cross-node signal envelopes.
     *
     * <p>
     * <b>FROZEN WIRE NAME — do not rename alongside the Java identifier.</b> This is the highest-risk literal in the
     * module: it is the fleet's change-stream channel, so a rename partitions a rolling deploy in half — upgraded nodes
     * publish to and watch one capped collection, un-upgraded nodes another, and neither sees the other's signals.
     *
     * <p>
     * The collection names are <em>more</em> exposed than the field names, not less: the classes around them
     * ({@code MongoSessionLeaseStore}, {@code MongoSessionInbox}, {@code MongoSessionSignalBus}) all carry
     * "Conversation" in their own names and are expected to move, which makes "fixing" the collection to match look
     * like tidying up. It is not — the name is a contract with data that is already on disk.
     */
    public static final String COLL_SIGNALS = "conversation_signals";

    /** Default collection holding background subagent task snapshots, keyed by task id. */
    public static final String COLL_BACKGROUND_TASK = "background_task";

    /**
     * Default collection holding one document per session record, keyed by session id.
     *
     * <p>
     * The only name here that says {@code session} rather than {@code conversation}, and deliberately. The frozen
     * spellings above are contracts with documents already on disk — this collection has none, because nothing
     * persisted a record before {@code MongoSessionRecordStore} existed. A new name costs nothing here and spelling it
     * {@code conversation_records} would have added a sixth name to keep explaining. It is frozen from now on all the
     * same, pinned by {@code MongoSchemaFreezeTest}: from the first deployment there is data behind it.
     */
    public static final String COLL_SESSION_RECORDS = "session_records";

    /** Mongo {@code _id} field name. */
    public static final String F_ID = "_id";

    /** Lock document — current holder identity. */
    public static final String F_HOLDER_ID = "holderId";

    /** Lock document — monotonic fencing token. */
    public static final String F_FENCING_TOKEN = "fencingToken";

    /** Lock document — absolute UTC instant the lease expires. */
    public static final String F_LEASE_EXPIRES_AT = "leaseExpiresAt";

    /** Lock document — diagnostic acquisition timestamp. */
    public static final String F_ACQUIRED_AT = "acquiredAt";

    /**
     * Inbox / idempotency / signal document — target session id (string).
     *
     * <p>
     * <b>FROZEN WIRE KEY — do not rename alongside the Java identifier.</b> The Java identifier is the half that
     * legitimately moved: the type is {@code SessionId} and the accessor {@code getSessionId()}, while the stored key
     * stayed {@code conversationId}. Three things are pinned to that literal and none of them moves with a Java
     * rename: documents already in the {@code conversation_inbox}, {@code idempotency_entries} and
     * {@code conversation_signals} collections, the
     * {@code { conversationId: 1, priority: 1, deliveredAt: 1 }} index declared in {@code init.js} that serves the
     * priority-then-FIFO collect, and the {@code conversationId} key the sibling Redis codecs put in their JSON
     * envelopes (Postgres carries it inside the {@code conversation_inbox.payload} JSONB and as a
     * {@code conversation_id} column elsewhere — a mixed-backend fleet must agree). Because every writer and reader
     * shares this one constant, renaming it keeps all round-trip tests green while stored messages become
     * uncollectable and the deployed index stops being used. Pinned in both directions by
     * {@code SessionSignalCodecTest}, {@code IdempotencyEntryCodecTest}, {@code InboundMessageCodecTest} and
     * {@code MongoSessionInboxTest}; the DDL side is pinned by {@code MongoSchemaFreezeTest}.
     */
    public static final String F_CONVERSATION_ID = "conversationId";

    /** Inbox document — priority ordinal (sort cheap). */
    public static final String F_PRIORITY = "priority";

    /** Inbox document — server delivery timestamp (FIFO tiebreaker within a priority tier). */
    public static final String F_DELIVERED_AT = "deliveredAt";

    /** Inbox document — full envelope payload subtree. */
    public static final String F_PAYLOAD = "payload";

    /** Idempotency document — input hash for replay validation. */
    public static final String F_INPUT_HASH = "inputHash";

    /** Idempotency document — current lifecycle status. */
    public static final String F_STATUS = "status";

    /** Idempotency document — cached agent execution result projection. */
    public static final String F_RESULT = "result";

    /** Idempotency document — first-insert timestamp. */
    public static final String F_CREATED_AT = "createdAt";

    /** Idempotency document — refreshed by lease renewer. */
    public static final String F_LAST_TOUCHED_AT = "lastTouchedAt";

    /** Idempotency document — TTL-index target (absolute deadline). */
    public static final String F_EXPIRES_AT = "expiresAt";

    /** Signal document — kind enum name. */
    public static final String F_KIND = "kind";

    /** Signal document — origin node id (used for self-broadcast dedup). */
    public static final String F_ORIGIN_NODE_ID = "originNodeId";

    /** Background-task document — subagent name that runs (or ran) the task. */
    public static final String F_SUBAGENT_NAME = "subagentName";

    /** Background-task document — human-readable description (never null, may be empty). */
    public static final String F_DESCRIPTION = "description";

    /** Background-task document — lifecycle state enum name (indexed). */
    public static final String F_STATE = "state";

    /** Background-task document — registration / start instant. */
    public static final String F_START_TIME = "startTime";

    /** Background-task document — terminal instant (omitted while non-terminal). */
    public static final String F_END_TIME = "endTime";

    /** Background-task document — output streaming cursor. */
    public static final String F_OUTPUT_OFFSET = "outputOffset";

    /** Background-task document — owning principal subtree (omitted when absent). */
    public static final String F_OWNER = "owner";

    /**
     * Background-task document — owning agent runtime id string (indexed; omitted when absent).
     *
     * <p>
     * <b>FROZEN WIRE KEY — do not rename alongside the Java identifier.</b> The agent-scope refactor renamed the type
     * to {@code AgentRuntime} but deliberately left persisted names alone (CHANGELOG, "Not changed (deliberately
     * frozen)"). Two things are pinned to this literal and neither moves with a Java rename: documents already in the
     * {@code background_task} collection, and the {@code { contextId: 1 }} index declared in {@code init.js} that
     * serves the by-owning-context listing. Because the encoder and decoder share this one constant, renaming it keeps
     * every round-trip test green while the owner silently disappears from stored tasks. Pinned in both directions by
     * {@code BackgroundTaskDocumentCodecTest}.
     */
    public static final String F_CONTEXT_ID = "contextId";

    /** Background-task document — last lease-heartbeat instant (omitted when absent). */
    public static final String F_LAST_HEARTBEAT = "lastHeartbeat";

    /**
     * Session-record document — the encoded transcript, as a string.
     *
     * <p>
     * A string and not a subdocument: a tool use's input is an arbitrary model-supplied map, so its keys may contain
     * {@code .} or start with {@code $}, neither of which BSON accepts as a field name. See
     * {@code SessionRecordCodec}, which owns the encoding.
     */
    public static final String F_TRANSCRIPT = "transcript";

    /** Session-record document — the bound agent (omitted while the record is unbound). */
    public static final String F_AGENT_REF = "agentRef";

    /** Session-record document — consecutive AUTO compaction failures (omitted until the first increment). */
    public static final String F_COMPACTION_FAILURE_COUNT = "compactionFailureCount";

    /** Session-record document — accumulated session totals subdocument (omitted until the first turn ends). */
    public static final String F_SESSION_TOTALS = "sessionTotals";

    /** Session-record document — runtime budget override subdocument (omitted when there is no override). */
    public static final String F_BUDGET_OVERRIDE = "budgetOverride";

    /** Session-record document — server-side timestamp of the last write, for operator triage only. */
    public static final String F_UPDATED_AT = "updatedAt";

    private DocumentKeys() {
    }
}
