package at.aimon.session.mongodb.internal;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;

import org.bson.Document;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.store.StoredAgentExecutionResult;

/**
 * Codec between {@link IdempotencyEntry} and BSON {@link Document} stored in MongoDB.
 *
 * <p>
 * Layout (single document per key, with TTL on {@code expiresAt}):
 *
 * <pre>{@code
 * {
 *   "_id": "<idempotency-key>",
 *   "conversationId": "<conv-id>",
 *   "inputHash": "<sha-256>",
 *   "status": "IN_FLIGHT" | "DONE",
 *   "holderId": "<node>/..." | null,
 *   "createdAt": ISODate,
 *   "lastTouchedAt": ISODate,
 *   "expiresAt": ISODate,        // TTL-index target (set by caller, server $$NOW for in-place updates)
 *   "result": { ... } | null     // StoredAgentExecutionResult projection
 * }
 * }</pre>
 *
 * <p>
 * The {@code result} subtree is the {@link StoredAgentExecutionResult} projection — full {@link AgentExecutionResult}
 * polymorphism is out of scope (design §9.2). Mirrors the Redis codec field-for-field except {@code expiresAt} replaces
 * the Redis-side {@code ttlMillis} hint (the TTL is part of the document via the indexed field).
 */
public final class IdempotencyEntryCodec {

    public IdempotencyEntryCodec() {
    }

    /**
     * Encodes an entry to a BSON document. The caller is responsible for stamping {@code _id} and {@code expiresAt}
     * since both are write-time computed and not part of {@link IdempotencyEntry}.
     *
     * @param entry
     *            the entry to encode (must not be null)
     * @return the encoded document (does not yet contain {@code _id} or {@code expiresAt})
     */
    public Document encode(IdempotencyEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        final Document doc = new Document();
        doc.append(DocumentKeys.F_CONVERSATION_ID, entry.getSessionId().value());
        doc.append(DocumentKeys.F_INPUT_HASH, entry.getInputHash());
        doc.append(DocumentKeys.F_STATUS, entry.getStatus().name());
        doc.append(DocumentKeys.F_HOLDER_ID, entry.getHolderId().orElse(null));
        doc.append(DocumentKeys.F_CREATED_AT, Date.from(entry.getCreatedAt()));
        doc.append(DocumentKeys.F_LAST_TOUCHED_AT, Date.from(entry.getLastTouchedAt()));
        doc.append(DocumentKeys.F_RESULT, entry.getResult().map(IdempotencyEntryCodec::encodeResult).orElse(null));
        return doc;
    }

    public IdempotencyEntry decode(Document doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        final IdempotencyEntry.Builder builder = IdempotencyEntry.builder().key(doc.getString(DocumentKeys.F_ID))
                .sessionId(SessionId.of(doc.getString(DocumentKeys.F_CONVERSATION_ID)))
                .inputHash(doc.getString(DocumentKeys.F_INPUT_HASH))
                .status(IdempotencyEntry.Status.valueOf(doc.getString(DocumentKeys.F_STATUS)))
                .createdAt(toInstant(doc.get(DocumentKeys.F_CREATED_AT)))
                .lastTouchedAt(toInstant(doc.get(DocumentKeys.F_LAST_TOUCHED_AT)));
        final String holderId = doc.getString(DocumentKeys.F_HOLDER_ID);
        if (holderId != null) {
            builder.holderId(holderId);
        }
        final Document result = doc.get(DocumentKeys.F_RESULT, Document.class);
        if (result != null) {
            builder.result(decodeResult(result));
        }
        return builder.build();
    }

    public static Document encodeResult(AgentExecutionResult r) {
        final Document out = new Document();
        out.append("success", r.isSuccess());
        out.append("finalAnswer", r.getFinalAnswer());
        out.append("errorMessage", r.getErrorMessage());
        out.append("completionReason", r.getCompletionReason().name());
        out.append("wasStreamed", r.wasStreamed());
        return out;
    }

    public static AgentExecutionResult decodeResult(Document node) {
        final StoredAgentExecutionResult.Builder builder = StoredAgentExecutionResult.builder()
                .success(node.getBoolean("success", false))
                .completionReason(CompletionReason.valueOf(node.getString("completionReason")))
                .wasStreamed(node.getBoolean("wasStreamed", false));
        final String finalAnswer = node.getString("finalAnswer");
        if (finalAnswer != null) {
            builder.finalAnswer(finalAnswer);
        }
        final String errorMessage = node.getString("errorMessage");
        if (errorMessage != null) {
            builder.errorMessage(errorMessage);
        }
        return builder.build();
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
