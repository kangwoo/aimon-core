package at.aimon.session.mongodb.internal;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.bson.Document;
import org.bson.types.ObjectId;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.subagent.task.codec.SubmitOptionsCodec;

/**
 * Codec between the {@link InboundMessage} envelope and the BSON {@link Document} stored in MongoDB.
 *
 * <p>
 * On encode the codec writes a {@code payload} subtree carrying everything except the inbox-assigned id (which is the
 * Mongo-generated {@code _id}); on decode the caller passes the {@code _id} so the rebuilt envelope's
 * {@link InboundMessage#getId()} is stamped from it.
 *
 * <p>
 * Mirrors {@code at.aimon.session.redis.internal.InboundMessageCodec} field-for-field (Principal, SessionId,
 * priority, deliveredAt, idempotencyKey, metadata) so cross-backend behavior is identical.
 *
 * <p>
 * <b>This is the second representation of the {@code submitOptions} shape, and it is meant to stay that way.</b> The
 * Redis and Postgres inboxes no longer map it at all — they call
 * {@link at.aimon.core.subagent.task.codec.SubmitOptionsCodec}, whose currency is a Jackson {@code ObjectNode}. This
 * codec's is a BSON {@link Document}, so it cannot: converting between the two would have to travel through the
 * scalar mapping this class deliberately does not use, where a {@code Date} is a BSON type rather than a string and
 * {@code normalizeBsonMap} exists precisely to bring nested values back to the shape the JSON backends produce.
 *
 * <p>
 * What is shared instead is the <b>names</b>: every key in the {@code submitOptions} subtree, and in the
 * {@link Principal} and {@code LlmCallMetadata} it nests, is a constant on that class. A rename therefore reaches both
 * representations. An <i>added</i> field does not — it has to be handled here too, and what says so is
 * {@code InboundMessageCodecTest}, which asserts this codec's key sets against
 * {@code SubmitOptionsCodec.TOP_LEVEL_FIELDS} and its two nested siblings rather than against literals of its own.
 */
public final class InboundMessageCodec {

    public InboundMessageCodec() {
    }

    /**
     * Encodes the envelope payload subtree (everything except {@code conversationId}, {@code priority},
     * {@code deliveredAt} which are top-level columns for indexing). The id is intentionally not encoded.
     *
     * <p>
     * {@code conversationId} is the stored spelling and stays that way even though the Java identifier moved to
     * {@code SessionId} / {@code getSessionId()} — see {@link DocumentKeys#F_CONVERSATION_ID}.
     */
    public Document encodePayload(InboundMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        final Document payload = new Document();
        payload.append("agentRef", message.getAgentRef());
        payload.append("contextDiscriminator", message.getContextDiscriminator().orElse(null));
        payload.append("userInput", message.getUserInput());
        payload.append("turnId", message.getTurnId().map(TurnId::value).orElse(null));
        payload.append("idempotencyKey", message.getIdempotencyKey().orElse(null));
        payload.append("initiator", encodePrincipal(message.getInitiator()));
        payload.append("deliveredAt", Date.from(message.getDeliveredAt()));
        final Document meta = new Document();
        for (Map.Entry<String, String> e : message.getMetadata().entrySet()) {
            meta.append(e.getKey(), e.getValue());
        }
        payload.append("metadata", meta);
        final Document submitOptions = encodeSubmitOptions(message.getSubmitOptions());
        if (submitOptions != null) {
            payload.append("submitOptions", submitOptions);
        }
        return payload;
    }

    /**
     * Rebuilds an {@link InboundMessage} from the full Mongo document.
     *
     * @param doc
     *            the document (must include {@code _id}, {@code conversationId}, {@code priority},
     *            {@code deliveredAt}, and the {@code payload} subtree)
     */
    public InboundMessage decode(Document doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        final ObjectId id = doc.getObjectId(DocumentKeys.F_ID);
        final Document payload = doc.get(DocumentKeys.F_PAYLOAD, Document.class);
        if (payload == null) {
            throw new IllegalStateException("Missing payload subtree on inbox document " + id);
        }
        final InboundMessage.Builder b = InboundMessage.builder().id(InboundMessageId.of(id.toHexString()))
                .sessionId(SessionId.of(doc.getString(DocumentKeys.F_CONVERSATION_ID)))
                .priority(QueuedInputPriority.values()[doc.getInteger(DocumentKeys.F_PRIORITY)])
                .agentRef(payload.getString("agentRef")).userInput(payload.getString("userInput"))
                .initiator(decodePrincipal(payload.get("initiator", Document.class)))
                .deliveredAt(toInstant(payload.get("deliveredAt")));
        final String turnId = payload.getString("turnId");
        if (turnId != null) {
            b.turnId(TurnId.of(turnId));
        }
        final String contextDiscriminator = payload.getString("contextDiscriminator");
        if (contextDiscriminator != null) {
            b.contextDiscriminator(contextDiscriminator);
        }
        final String idempotencyKey = payload.getString("idempotencyKey");
        if (idempotencyKey != null) {
            b.idempotencyKey(idempotencyKey);
        }
        final Document meta = payload.get("metadata", Document.class);
        if (meta != null && !meta.isEmpty()) {
            final Map<String, String> out = new LinkedHashMap<>(meta.size());
            for (Map.Entry<String, Object> e : meta.entrySet()) {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
            b.metadata(out);
        }
        final SubmitOptions submitOptions = decodeSubmitOptions(payload.get("submitOptions", Document.class));
        if (submitOptions != null) {
            b.submitOptions(submitOptions);
        }
        return b.build();
    }

    private static Document encodePrincipal(Principal principal) {
        return new Document().append(SubmitOptionsCodec.FIELD_TYPE, principal.getType().name())
                .append(SubmitOptionsCodec.FIELD_ID, principal.getId())
                .append(SubmitOptionsCodec.FIELD_DISPLAY_NAME, principal.getDisplayName());
    }

    private static Principal decodePrincipal(Document node) {
        if (node == null) {
            throw new IllegalStateException("Missing initiator field");
        }
        return Principal.builder().type(Principal.Type.valueOf(node.getString(SubmitOptionsCodec.FIELD_TYPE)))
                .id(node.getString(SubmitOptionsCodec.FIELD_ID))
                .displayName(node.getString(SubmitOptionsCodec.FIELD_DISPLAY_NAME)).build();
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Date d) {
            return d.toInstant();
        }
        if (value instanceof Instant i) {
            return i;
        }
        throw new IllegalStateException("Expected Date for deliveredAt field but got: " + value);
    }

    /**
     * Recursively converts a BSON {@link Document} into a {@link LinkedHashMap}, replacing nested {@code Document}
     * instances with {@code LinkedHashMap} too. Aligns the Mongo decode output with the JSON-codec backends
     * (Postgres/Redis) where nested objects surface as {@code LinkedHashMap} via Jackson, so equality and downstream
     * type assumptions hold uniformly across persistent backends.
     */
    private static Map<String, Object> normalizeBsonMap(Document doc) {
        final Map<String, Object> out = new LinkedHashMap<>(doc.size());
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            out.put(e.getKey(), normalizeBsonValue(e.getValue()));
        }
        return out;
    }

    private static Object normalizeBsonValue(Object value) {
        if (value instanceof Document nested) {
            return normalizeBsonMap(nested);
        }
        return value;
    }

    /**
     * Recursively wraps a heterogeneous {@code Map<String, Object>} as a BSON {@link Document}, converting nested
     * {@link Map} values into nested {@code Document}s. Used on the encode side so the in-memory document shape matches
     * what the BSON wire format produces on decode, keeping unit tests honest without requiring a live MongoDB driver
     * round-trip.
     */
    private static Document toBsonDocument(Map<String, Object> map) {
        final Document doc = new Document();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            doc.append(e.getKey(), wrapBsonValue(e.getValue()));
        }
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static Object wrapBsonValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            return toBsonDocument((Map<String, Object>) nested);
        }
        return value;
    }

    /**
     * Returns a BSON subdocument carrying the {@link SubmitOptions}, or {@code null} when the options carry no data so
     * the caller can skip emitting the {@code submitOptions} key entirely.
     *
     * <p>
     * Heterogeneous {@code Map<String, Object>} values are best-effort: BSON-native types (String, Number, Boolean,
     * Date) round-trip cleanly. Nested maps are normalized back to {@link LinkedHashMap} on decode (see
     * {@link #normalizeBsonMap}) so Mongo-decoded values match the {@code LinkedHashMap} shape produced by the JSON
     * backends (Postgres / Redis). Custom POJOs surface as nested {@code Map} on the consumer side because the BSON
     * wire format carries no polymorphic type information.
     */
    private static Document encodeSubmitOptions(SubmitOptions options) {
        if (options.equals(SubmitOptions.empty())) {
            return null;
        }
        final Document doc = new Document();
        options.getPrincipal().ifPresent(p -> doc.append(SubmitOptionsCodec.FIELD_PRINCIPAL, encodePrincipal(p)));
        if (!options.getSystemPromptVariables().isEmpty()) {
            doc.append(SubmitOptionsCodec.FIELD_SYSTEM_PROMPT_VARIABLES,
                    toBsonDocument(options.getSystemPromptVariables()));
        }
        if (!options.getExecutionAttributes().isEmpty()) {
            doc.append(SubmitOptionsCodec.FIELD_EXECUTION_ATTRIBUTES, toBsonDocument(options.getExecutionAttributes()));
        }
        options.getLlmCallMetadata()
                .ifPresent(m -> doc.append(SubmitOptionsCodec.FIELD_LLM_CALL_METADATA, encodeLlmCallMetadata(m)));
        options.getUserContextInjection()
                .ifPresent(b -> doc.append(SubmitOptionsCodec.FIELD_USER_CONTEXT_INJECTION, b));
        return doc;
    }

    private static SubmitOptions decodeSubmitOptions(Document doc) {
        if (doc == null) {
            return null;
        }
        final SubmitOptions.Builder b = SubmitOptions.builder();
        final Document principalDoc = doc.get(SubmitOptionsCodec.FIELD_PRINCIPAL, Document.class);
        if (principalDoc != null) {
            b.principal(decodePrincipal(principalDoc));
        }
        final Document spv = doc.get(SubmitOptionsCodec.FIELD_SYSTEM_PROMPT_VARIABLES, Document.class);
        if (spv != null && !spv.isEmpty()) {
            b.systemPromptVariables(normalizeBsonMap(spv));
        }
        final Document ea = doc.get(SubmitOptionsCodec.FIELD_EXECUTION_ATTRIBUTES, Document.class);
        if (ea != null && !ea.isEmpty()) {
            b.executionAttributes(normalizeBsonMap(ea));
        }
        final Document lcm = doc.get(SubmitOptionsCodec.FIELD_LLM_CALL_METADATA, Document.class);
        if (lcm != null) {
            b.llmCallMetadata(decodeLlmCallMetadata(lcm));
        }
        final Object uci = doc.get(SubmitOptionsCodec.FIELD_USER_CONTEXT_INJECTION);
        if (uci instanceof Boolean bool) {
            b.userContextInjection(bool);
        }
        return b.build();
    }

    private static Document encodeLlmCallMetadata(LlmCallMetadata m) {
        final Document doc = new Document();
        m.getComponent().ifPresent(v -> doc.append(SubmitOptionsCodec.FIELD_COMPONENT, v));
        m.getParentComponent().ifPresent(v -> doc.append(SubmitOptionsCodec.FIELD_PARENT_COMPONENT, v));
        m.getFeature().ifPresent(v -> doc.append(SubmitOptionsCodec.FIELD_FEATURE, v));
        m.getPrincipal().ifPresent(p -> doc.append(SubmitOptionsCodec.FIELD_PRINCIPAL, encodePrincipal(p)));
        m.getTraceId().ifPresent(v -> doc.append(SubmitOptionsCodec.FIELD_TRACE_ID, v));
        if (!m.getTags().isEmpty()) {
            doc.append(SubmitOptionsCodec.FIELD_TAGS, new Document(m.getTags()));
        }
        return doc;
    }

    private static LlmCallMetadata decodeLlmCallMetadata(Document doc) {
        final LlmCallMetadata.Builder b = LlmCallMetadata.builder();
        final String component = doc.getString(SubmitOptionsCodec.FIELD_COMPONENT);
        if (component != null) {
            b.component(component);
        }
        final String parentComponent = doc.getString(SubmitOptionsCodec.FIELD_PARENT_COMPONENT);
        if (parentComponent != null) {
            b.parentComponent(parentComponent);
        }
        final String feature = doc.getString(SubmitOptionsCodec.FIELD_FEATURE);
        if (feature != null) {
            b.feature(feature);
        }
        final Document principal = doc.get(SubmitOptionsCodec.FIELD_PRINCIPAL, Document.class);
        if (principal != null) {
            b.principal(decodePrincipal(principal));
        }
        final String traceId = doc.getString(SubmitOptionsCodec.FIELD_TRACE_ID);
        if (traceId != null) {
            b.traceId(traceId);
        }
        final Document tags = doc.get(SubmitOptionsCodec.FIELD_TAGS, Document.class);
        if (tags != null && !tags.isEmpty()) {
            final Map<String, String> out = new LinkedHashMap<>(tags.size());
            for (Map.Entry<String, Object> e : tags.entrySet()) {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
            b.tags(out);
        }
        return b.build();
    }
}
