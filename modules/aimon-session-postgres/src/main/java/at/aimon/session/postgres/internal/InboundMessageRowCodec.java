package at.aimon.session.postgres.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.codec.SubmitOptionsCodec;

/**
 * Jackson codec for the {@link InboundMessage} envelope persisted to {@code conversation_inbox.payload} (JSONB).
 *
 * <p>
 * The encoded form omits {@link InboundMessage#getId()} because the inbox-assigned id is the {@code bigserial}
 * {@code conversation_inbox.id} column; on decode the caller passes the id (rendered as a string) and the codec stamps
 * it onto the rebuilt envelope.
 *
 * <p>
 * <b>The {@code submitOptions} subtree is not this class's.</b> It is
 * {@link at.aimon.core.subagent.task.codec.SubmitOptionsCodec}, which this hands its own mapper so the subtree keeps
 * following the same rules as the document around it. This codec used to carry a hand-written copy of that mapping,
 * identical to the one in the Redis inbox and to the shared one — three copies that agreed by coincidence.
 */
public final class InboundMessageRowCodec {

    private final ObjectMapper mapper;

    public InboundMessageRowCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    public String encode(InboundMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            final ObjectNode root = mapper.createObjectNode();
            root.put("conversationId", message.getSessionId().value());
            root.put("agentRef", message.getAgentRef());
            root.put("userInput", message.getUserInput());
            root.put("priority", message.getPriority().name());
            message.getTurnId().ifPresent(t -> root.put("turnId", t.value()));
            message.getContextDiscriminator().ifPresent(d -> root.put("contextDiscriminator", d));
            message.getIdempotencyKey().ifPresent(k -> root.put("idempotencyKey", k));
            root.set("initiator", encodePrincipal(message.getInitiator()));
            root.put("deliveredAt", message.getDeliveredAt().toString());
            final ObjectNode meta = mapper.createObjectNode();
            for (Map.Entry<String, String> e : message.getMetadata().entrySet()) {
                meta.put(e.getKey(), e.getValue());
            }
            root.set("metadata", meta);
            final ObjectNode submitOptions = SubmitOptionsCodec.encode(message.getSubmitOptions(), mapper);
            if (submitOptions != null) {
                root.set("submitOptions", submitOptions);
            }
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode InboundMessage", e);
        }
    }

    public InboundMessage decode(String json, String rowId) {
        Objects.requireNonNull(json, "json must not be null");
        Objects.requireNonNull(rowId, "rowId must not be null");
        try {
            final JsonNode root = mapper.readTree(json);
            final InboundMessage.Builder b = InboundMessage.builder().id(InboundMessageId.of(rowId))
                    .sessionId(SessionId.of(root.get("conversationId").asText()))
                    .agentRef(root.get("agentRef").asText()).userInput(root.get("userInput").asText())
                    .priority(QueuedInputPriority.valueOf(root.get("priority").asText()))
                    .initiator(decodePrincipal(root.get("initiator")))
                    .deliveredAt(Instant.parse(root.get("deliveredAt").asText()));
            final JsonNode turn = root.get("turnId");
            if (turn != null && !turn.isNull()) {
                b.turnId(TurnId.of(turn.asText()));
            }
            final JsonNode discriminator = root.get("contextDiscriminator");
            if (discriminator != null && !discriminator.isNull()) {
                b.contextDiscriminator(discriminator.asText());
            }
            final JsonNode key = root.get("idempotencyKey");
            if (key != null && !key.isNull()) {
                b.idempotencyKey(key.asText());
            }
            final JsonNode metaNode = root.get("metadata");
            if (metaNode != null && metaNode.isObject()) {
                final Map<String, String> meta = new LinkedHashMap<>();
                final Iterator<Map.Entry<String, JsonNode>> it = metaNode.fields();
                while (it.hasNext()) {
                    final Map.Entry<String, JsonNode> e = it.next();
                    meta.put(e.getKey(), e.getValue().asText());
                }
                b.metadata(meta);
            }
            b.submitOptions(SubmitOptionsCodec.decode(root.get("submitOptions"), mapper));
            return b.build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode InboundMessage", e);
        }
    }

    private ObjectNode encodePrincipal(Principal principal) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("type", principal.getType().name());
        node.put("id", principal.getId());
        node.put("displayName", principal.getDisplayName());
        return node;
    }

    private Principal decodePrincipal(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("Missing initiator field");
        }
        return Principal.builder().type(Principal.Type.valueOf(node.get("type").asText())).id(node.get("id").asText())
                .displayName(node.get("displayName").asText()).build();
    }
}
