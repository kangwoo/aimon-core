package at.aimon.session.mongodb.internal;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;

import org.bson.Document;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;

/**
 * Codec between {@link BackgroundTask} and the BSON {@link Document} persisted by {@code MongoBackgroundTaskStore}.
 *
 * <p>
 * Layout (single document per task, keyed by {@code _id = taskId}):
 *
 * <pre>{@code
 * {
 *   "_id": "<taskId>",
 *   "subagentName": "<name>",
 *   "description": "<text>",           // never null, may be ""
 *   "state": "PENDING" | "RUNNING" | "COMPLETED" | "FAILED" | "KILLED",
 *   "startTime": ISODate,
 *   "endTime": ISODate,                // omitted while non-terminal
 *   "outputOffset": <long>,
 *   "owner": { "type": "USER", "id": "...", "displayName": "..." }, // omitted when absent
 *   "contextId": "agent:<name>",       // omitted when absent
 *   "lastHeartbeat": ISODate           // omitted when absent
 * }
 * }</pre>
 *
 * <p>
 * Instants are stored as native {@link Date} values (mirroring {@link IdempotencyEntryCodec}) so they are queryable and
 * human-readable in the shell. Nullable fields are simply omitted, so a decoded snapshot reports empty optionals for
 * whatever the source snapshot omitted.
 */
public final class BackgroundTaskDocumentCodec {

    private static final String OWNER_TYPE = "type";
    private static final String OWNER_ID = "id";
    private static final String OWNER_DISPLAY_NAME = "displayName";

    /** Creates a stateless codec. */
    public BackgroundTaskDocumentCodec() {
    }

    /**
     * Encodes a task snapshot to a BSON document keyed by {@code _id = taskId}.
     *
     * @param task
     *            the snapshot to encode (must not be null)
     * @return the encoded document (never null)
     */
    public Document encode(BackgroundTask task) {
        Objects.requireNonNull(task, "task must not be null");
        final Document doc = new Document();
        doc.append(DocumentKeys.F_ID, task.getTaskId());
        doc.append(DocumentKeys.F_SUBAGENT_NAME, task.getSubagentName());
        doc.append(DocumentKeys.F_DESCRIPTION, task.getDescription());
        doc.append(DocumentKeys.F_STATE, task.getState().name());
        doc.append(DocumentKeys.F_START_TIME, Date.from(task.getStartTime()));
        doc.append(DocumentKeys.F_OUTPUT_OFFSET, task.getOutputOffset());
        task.getEndTime().ifPresent(end -> doc.append(DocumentKeys.F_END_TIME, Date.from(end)));
        task.getOwner().ifPresent(owner -> doc.append(DocumentKeys.F_OWNER, encodeOwner(owner)));
        // F_CONTEXT_ID ("contextId") is a FROZEN WIRE KEY — see its javadoc; do not rename it with the Java identifier.
        task.getAgentRuntimeId().ifPresent(ctx -> doc.append(DocumentKeys.F_CONTEXT_ID, ctx.value()));
        task.getLastHeartbeat().ifPresent(hb -> doc.append(DocumentKeys.F_LAST_HEARTBEAT, Date.from(hb)));
        return doc;
    }

    /**
     * Decodes a task snapshot from its BSON document form.
     *
     * @param doc
     *            the stored document (must not be null)
     * @return the reconstructed snapshot (never null)
     */
    public BackgroundTask decode(Document doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        final BackgroundTask.Builder builder = BackgroundTask.builder().taskId(doc.getString(DocumentKeys.F_ID))
                .subagentName(doc.getString(DocumentKeys.F_SUBAGENT_NAME))
                .description(doc.getString(DocumentKeys.F_DESCRIPTION))
                .state(BackgroundTaskState.valueOf(doc.getString(DocumentKeys.F_STATE)))
                .startTime(toInstant(doc.get(DocumentKeys.F_START_TIME))).outputOffset(readOffset(doc));
        final Object endTime = doc.get(DocumentKeys.F_END_TIME);
        if (endTime != null) {
            builder.endTime(toInstant(endTime));
        }
        final Document owner = doc.get(DocumentKeys.F_OWNER, Document.class);
        if (owner != null) {
            builder.owner(decodeOwner(owner));
        }
        // F_CONTEXT_ID ("contextId") is a FROZEN WIRE KEY — see its javadoc; do not rename it with the Java identifier.
        final String contextId = doc.getString(DocumentKeys.F_CONTEXT_ID);
        if (contextId != null) {
            builder.agentRuntimeId(AgentRuntimeId.of(contextId));
        }
        final Object lastHeartbeat = doc.get(DocumentKeys.F_LAST_HEARTBEAT);
        if (lastHeartbeat != null) {
            builder.lastHeartbeat(toInstant(lastHeartbeat));
        }
        return builder.build();
    }

    private static Document encodeOwner(Principal owner) {
        return new Document().append(OWNER_TYPE, owner.getType().name()).append(OWNER_ID, owner.getId())
                .append(OWNER_DISPLAY_NAME, owner.getDisplayName());
    }

    private static Principal decodeOwner(Document owner) {
        return Principal.builder().type(Principal.Type.valueOf(owner.getString(OWNER_TYPE)))
                .id(owner.getString(OWNER_ID)).displayName(owner.getString(OWNER_DISPLAY_NAME)).build();
    }

    private static long readOffset(Document doc) {
        final Object value = doc.get(DocumentKeys.F_OUTPUT_OFFSET);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Date d) {
            return d.toInstant();
        }
        if (value instanceof Instant i) {
            return i;
        }
        throw new IllegalStateException("Expected Date for timestamp field but got: " + value);
    }
}
