/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.mongodb.internal;

/**
 * Collection names and BSON field keys for the MongoDB memory backend. Centralised so the codec,
 * the stores, and the {@code db/mongodb/init.js} DDL stay in lock-step — changing a name here is a
 * versioned schema change and the init script must be updated to match.
 */
public final class DocumentKeys {

    private DocumentKeys() {
    }

    // --- collections ---------------------------------------------------------
    public static final String COLL_WORKSPACE = "mem_workspace";
    public static final String COLL_OBSERVATION = "mem_observation";
    public static final String COLL_REPRESENTATION = "mem_representation";

    // --- shared ---------------------------------------------------------------
    public static final String ID = "_id";
    public static final String WORKSPACE_ID = "workspaceId";
    public static final String DISPLAY_NAME = "displayName";
    public static final String CREATED_AT = "createdAt";
    public static final String METADATA = "metadata";

    // --- principal (subject / observer are stored flat) -----------------------
    public static final String SUBJECT_TYPE = "subjectType";
    public static final String SUBJECT_ID = "subjectId";
    public static final String SUBJECT_DISPLAY_NAME = "subjectDisplayName";
    public static final String OBSERVER_TYPE = "observerType";
    public static final String OBSERVER_ID = "observerId";
    public static final String OBSERVER_DISPLAY_NAME = "observerDisplayName";

    // --- observation ----------------------------------------------------------
    public static final String LOCAL_ID = "localId";
    public static final String CONTENT = "content";
    public static final String TYPE = "type";
    public static final String SOURCE_MESSAGE_IDS = "sourceMessageIds";
    public static final String CONFIDENCE = "confidence";
    public static final String SOFT_DELETED_AT = "softDeletedAt";

    // --- representation -------------------------------------------------------
    /** Embedded snapshot of the subject's workspace (representations are point-in-time). */
    public static final String WORKSPACE = "workspace";
    public static final String SESSION_ID = "sessionId";
    public static final String OBSERVATIONS = "observations";
    public static final String SUMMARY = "summary";
    public static final String GENERATED_AT = "generatedAt";
    public static final String TOKEN_COUNT = "tokenCount";
}
