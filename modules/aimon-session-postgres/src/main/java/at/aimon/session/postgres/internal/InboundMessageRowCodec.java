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

import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.codec.SubmitOptionsCodec;
import at.aimon.core.subagent.task.codec.UserInputCodec;

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
 *
 * <p>
 * <b>{@code userInput} leads with text, and {@code userInputEncoded} carries the rest.</b> The envelope's input
 * used to be a {@code String} and this key held it directly. It now holds
 * {@link at.aimon.core.agent.input.UserInput#asText()}, and a non-text input additionally writes its
 * {@link at.aimon.core.subagent.task.codec.UserInputCodec} subtree under {@code userInputEncoded}; a
 * {@link at.aimon.core.agent.input.TextInput} writes no sidecar at all, so a text submission is byte-for-byte the
 * document the previous build wrote. Decode prefers the sidecar and falls back to wrapping the string.
 *
 * <p>
 * That asymmetry is the compatibility contract, and the reason for it is that an inbox holds work that has
 * <i>not been done yet</i>. Three readers have to be considered, not one:
 *
 * <ul>
 * <li><b>This build reading an older entry</b> — no sidecar, so the string is the input, which is exactly what it
 * was.
 * <li><b>An older build reading this build's text entry</b> — unchanged in every byte.
 * <li><b>An older build reading this build's multimodal entry</b> — it cannot run the image whatever we write, so
 * the question is only what it does instead. Under the old key it finds the {@code asText()} rendering and runs a
 * turn that says an image was attached. Turning {@code userInput} into an object would give it {@code ""} from
 * {@code JsonNode.asText()} and an empty turn, silently; omitting the key would strand the entry undecodable, and
 * an inbox entry nobody can decode is a turn nobody runs.
 * <li><b>This build reading a newer entry</b> — the mirror of the case above, and the one this format created: a
 * node one release ahead can write a sixth {@code InputType}. Decoding goes through
 * {@link at.aimon.core.subagent.task.codec.UserInputCodec#decodeOrText(com.fasterxml.jackson.databind.JsonNode,
 * String)}, which falls back to the same {@code asText()} rendering and logs at {@code WARN}. Refusing would not
 * reject one entry: {@code collect} removes entries from the backend <em>before</em> this codec runs, so a throw
 * destroys everything that call collected.
 * </ul>
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
            root.put("userInput", message.getUserInput().asText());
            if (!(message.getUserInput() instanceof TextInput)) {
                root.set("userInputEncoded", UserInputCodec.encode(message.getUserInput()));
            }
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
                    .agentRef(root.get("agentRef").asText()).userInput(decodeUserInput(root))
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

    /**
     * The envelope's input: the {@code userInputEncoded} subtree when it is there and readable, otherwise the
     * {@code userInput} string wrapped as text. See the class javadoc for why the two keys coexist, and
     * {@link UserInputCodec#decodeOrText(JsonNode, String)} for why an unreadable encoding degrades here rather than
     * refusing the entry.
     */
    private static UserInput decodeUserInput(JsonNode root) {
        return UserInputCodec.decodeOrText(root.get("userInputEncoded"), root.get("userInput").asText());
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
