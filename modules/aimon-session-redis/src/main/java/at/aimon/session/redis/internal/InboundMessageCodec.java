package at.aimon.session.redis.internal;

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
import at.aimon.core.agent.session.inbox.UnreadableEntry;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.codec.SubmitOptionsCodec;
import at.aimon.core.subagent.task.codec.UserInputCodec;

/**
 * Jackson codec for the {@link InboundMessage} envelope persisted to Redis Streams.
 *
 * <p>
 * The encoded form omits {@link InboundMessage#getId()} because the inbox-assigned id is the Redis Stream entry id
 * itself; on decode the caller passes the entry id and the codec stamps it onto the rebuilt envelope.
 *
 * <p>
 * {@link Principal} is serialized as {@code {type, id, displayName}}; {@link SessionId} as its string value;
 * {@link QueuedInputPriority} as its enum name; {@link Instant} as ISO-8601 string. Metadata is a flat
 * {@code Map<String, String>} per the envelope contract.
 *
 * <p>
 * <b>{@code conversationId} is a frozen wire key.</b> The Java accessor already moved with the type it belongs to —
 * it reads {@link InboundMessage#getSessionId()} now — but this key did not follow it and may not. An inbox holds work
 * that has <i>not been done yet</i>, so at every upgrade the stream still contains entries written by the older build,
 * and a node on the new build has to route them. Renaming encode() and decode() together keeps every round-trip test
 * above green while each of those undelivered messages loses the session it was addressed to.
 * {@code InboundMessageCodecTest} pins the literal in both directions.
 *
 * <p>
 * <b>The {@code submitOptions} subtree is not this class's.</b> It is
 * {@link at.aimon.core.subagent.task.codec.SubmitOptionsCodec}, which this hands its own mapper so the subtree keeps
 * following the same rules as the document around it. This codec used to carry a hand-written copy of that mapping,
 * identical to the one in the Postgres inbox and to the shared one — three copies that agreed by coincidence.
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
 * String, String)}, which falls back to the same {@code asText()} rendering and logs at {@code WARN} naming this
 * session. Refusing would not
 * reject one entry: {@code collect} removes entries from the backend <em>before</em> this codec runs, so a throw
 * destroys everything that call collected.
 * </ul>
 */
public final class InboundMessageCodec {

    private final ObjectMapper mapper;

    public InboundMessageCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /** Serializes the envelope to a JSON string. The id field, if present, is intentionally omitted. */
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

    /**
     * Decodes a JSON string and stamps the supplied stream entry id onto the rebuilt envelope.
     *
     * @param json
     *            the encoded payload (must not be null)
     * @param streamEntryId
     *            the Redis Stream entry id to wrap as {@link InboundMessageId} (must not be null)
     */
    public InboundMessage decode(String json, String streamEntryId) {
        Objects.requireNonNull(json, "json must not be null");
        Objects.requireNonNull(streamEntryId, "streamEntryId must not be null");
        try {
            final JsonNode root = mapper.readTree(json);
            final InboundMessage.Builder b = InboundMessage.builder().id(InboundMessageId.of(streamEntryId))
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
     * Best-effort address recovery for an entry {@link #decode} could not rebuild.
     *
     * <p>
     * <b>This method never throws</b>, and that is its whole contract. It runs inside the per-entry guard of
     * {@code collect}, on a payload that has already proved itself unreadable, so a second failure here would either
     * re-create the batch loss the guard exists to prevent or leave the drop unreported. Anything it cannot read
     * comes back as an absent address, which the router already has a branch for.
     *
     * <p>
     * The two addresses ride as raw strings rather than as {@code TurnId} — rebuilding a validating value object
     * from this input is exactly the move that broke here in the first place. The conversion happens where a
     * {@code null} means something.
     *
     * @param json
     *            the payload as stored (may be null)
     * @param entryId
     *            the backend's id for the entry (null or empty is reported as {@code unknown} rather than refused)
     * @param cause
     *            what {@link #decode} threw (must not be null)
     * @return the entry, with whatever address survived
     */
    public UnreadableEntry recoverAddress(String json, String entryId, RuntimeException cause) {
        Objects.requireNonNull(cause, "cause must not be null");
        // toString rather than getMessage: the message is null for plenty of runtime exceptions, and the type is
        // half of what tells an operator a newer document apart from a damaged one. Neither appends the payload,
        // though the JDK's own message can quote the single envelope value that failed — see UnreadableEntry.
        // The id is the one field there is always some answer for — the backend handed this entry back, so if its
        // own id will not wrap, name it unknown rather than lose the report to a second exception. Nothing reaches
        // that fallback today (a stream entry id and a row id are neither null nor empty); it is here because a
        // throw from this method lands exactly where the per-entry guard was put to stop one, and the caller has no
        // way to tell that a "never throws" contract only held on one axis.
        final UnreadableEntry.Builder recovered = UnreadableEntry.builder()
                .id(InboundMessageId.of(entryId == null || entryId.isEmpty() ? "unknown" : entryId))
                .reason(cause.toString());
        if (json == null) {
            return recovered.build();
        }
        try {
            final JsonNode root = mapper.readTree(json);
            recovered.turnId(rawText(root, "turnId")).idempotencyKey(rawText(root, "idempotencyKey"));
        } catch (IOException | RuntimeException ignored) {
            // Nothing legible in there. The entry is still reported — unaddressably, which the caller can tell.
        }
        return recovered.build();
    }

    /**
     * One address field, as text, or null when the document does not usefully carry it.
     *
     * <p>
     * Blank folds to absent, and that is not tidiness. {@code asText()} answers {@code ""} for an object or an array
     * node, so a document holding {@code "idempotencyKey": {}} would otherwise recover an empty key, report itself
     * as addressable, and send the router announcing a turn under an address nobody registered. Absent is the
     * truthful answer, and it is the one the caller already has a branch for.
     */
    private static String rawText(JsonNode root, String field) {
        final JsonNode node = root == null ? null : root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        final String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * The envelope's input: the {@code userInputEncoded} subtree when it is there and readable, otherwise the
     * {@code userInput} string wrapped as text. See the class javadoc for why the two keys coexist, and
     * {@link UserInputCodec#decodeOrText(JsonNode, String, String)} for why an unreadable encoding degrades here
     * rather than refusing the entry.
     */
    private static UserInput decodeUserInput(JsonNode root) {
        // The session id goes with it: a warning nobody can attribute to a session is one nobody can act on, and
        // this is the last point that still holds one.
        return UserInputCodec.decodeOrText(root.get("userInputEncoded"), root.get("userInput").asText(),
                root.get("conversationId").asText());
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
