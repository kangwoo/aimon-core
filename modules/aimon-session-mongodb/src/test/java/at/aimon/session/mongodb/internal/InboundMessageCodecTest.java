package at.aimon.session.mongodb.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * Round-trip tests for the Mongo {@link InboundMessageCodec}, focused on {@link SubmitOptions} preservation through
 * the BSON wire format. Mirrors the equivalent Postgres / Redis codec tests so cross-backend behavior stays
 * locked-down. Also pins the frozen top-level wire key the round-trip cannot see.
 */
@DisplayName("Mongo InboundMessageCodec submitOptions round-trip, and the frozen wire key it cannot see")
class InboundMessageCodecTest {

    private final InboundMessageCodec codec = new InboundMessageCodec();

    @Test
    @DisplayName("empty SubmitOptions round-trips as SubmitOptions.empty()")
    void emptySubmitOptions() {
        final InboundMessage message = baseMessage().submitOptions(SubmitOptions.empty()).build();

        final Document payload = codec.encodePayload(message);
        final Document full = wrap(payload, message);
        final InboundMessage decoded = codec.decode(full);

        assertThat(decoded.getSubmitOptions()).isEqualTo(SubmitOptions.empty());
        assertThat(payload).doesNotContainKey("submitOptions");
    }

    @Test
    @DisplayName("fully populated SubmitOptions round-trips field-by-field")
    void fullSubmitOptionsRoundTrip() {
        final SubmitOptions options = SubmitOptions.builder()
                .principal(Principal.builder().type(Principal.Type.USER).id("u-1").displayName("alice").build())
                .systemPromptVariable("region", "eu").systemPromptVariable("attempt", 3)
                .executionAttribute("ab.x", true).executionAttribute("rollout", "on")
                .llmCallMetadata(LlmCallMetadata.builder().component("orca-agent").parentComponent("web-facade")
                        .feature(LlmCallMetadata.Feature.REACT_LOOP).traceId("trace-9")
                        .principal(Principal.builder().type(Principal.Type.SERVICE).id("svc-1")
                                .displayName("dispatcher").build())
                        .tag("tenant", "acme").build())
                .userContextInjection(false).build();
        final InboundMessage message = baseMessage().submitOptions(options).build();

        final Document full = wrap(codec.encodePayload(message), message);
        final InboundMessage decoded = codec.decode(full);

        assertThat(decoded.getSubmitOptions()).isEqualTo(options);
    }

    @Test
    @DisplayName("nested Map values round-trip as LinkedHashMap (cross-backend uniformity)")
    void nestedMapRoundTripsAsLinkedHashMap() {
        final SubmitOptions options = SubmitOptions.builder()
                .systemPromptVariable("nested", Map.of("inner", 1, "deep", Map.of("k", "v"))).build();
        final InboundMessage message = baseMessage().submitOptions(options).build();

        final InboundMessage decoded = codec.decode(wrap(codec.encodePayload(message), message));

        final Object value = decoded.getSubmitOptions().getSystemPromptVariables().get("nested");
        assertThat(value).isInstanceOf(LinkedHashMap.class);
        @SuppressWarnings("unchecked")
        final Map<String, Object> nested = (Map<String, Object>) value;
        assertThat(nested).containsEntry("inner", 1);
        assertThat(nested.get("deep")).isInstanceOf(LinkedHashMap.class);
    }

    @Test
    @DisplayName("partial SubmitOptions: only userContextInjection override is preserved")
    void onlyUserContextInjection() {
        final SubmitOptions options = SubmitOptions.builder().userContextInjection(true).build();
        final InboundMessage message = baseMessage().submitOptions(options).build();

        final InboundMessage decoded = codec.decode(wrap(codec.encodePayload(message), message));

        assertThat(decoded.getSubmitOptions().getUserContextInjection()).contains(Boolean.TRUE);
        assertThat(decoded.getSubmitOptions().getPrincipal()).isEmpty();
        assertThat(decoded.getSubmitOptions().getLlmCallMetadata()).isEmpty();
        assertThat(decoded.getSubmitOptions().getSystemPromptVariables()).isEmpty();
        assertThat(decoded.getSubmitOptions().getExecutionAttributes()).isEmpty();
    }

    @Test
    @DisplayName("turnId round-trips through the payload sub-document, and its absence round-trips as absence")
    void turnIdRoundTrip() {
        final InboundMessage stamped = baseMessage().turnId(TurnId.of("turn-42")).build();

        final Document payload = codec.encodePayload(stamped);
        assertThat(payload).containsEntry("turnId", "turn-42");
        assertThat(codec.decode(wrap(payload, stamped)).getTurnId()).contains(TurnId.of("turn-42"));

        // An unstamped message writes an explicit null rather than omitting the key — either shape must decode to
        // empty,
        // because the holder mints its own id precisely when it can see none was sent.
        final InboundMessage bare = baseMessage().build();
        assertThat(codec.decode(wrap(codec.encodePayload(bare), bare)).getTurnId()).isEmpty();
    }

    @Test
    @DisplayName("contextDiscriminator round-trips, and its absence round-trips as absence")
    void contextDiscriminatorRoundTrip() {
        final InboundMessage stamped = baseMessage().contextDiscriminator("tenant-a").build();

        final Document payload = codec.encodePayload(stamped);
        assertThat(payload).containsEntry("contextDiscriminator", "tenant-a");
        assertThat(codec.decode(wrap(payload, stamped)).getContextDiscriminator()).contains("tenant-a");

        // Absence has to survive as absence, not become the string "null": the holder reads empty as "open the bare
        // agent:<ref> runtime", and a discriminator no submitter ever named resolves to no registered runtime at all.
        final InboundMessage bare = baseMessage().build();
        assertThat(codec.decode(wrap(codec.encodePayload(bare), bare)).getContextDiscriminator()).isEmpty();
    }

    @Test
    @DisplayName("a message written before the turn stamp existed still decodes, with no turn")
    void preTurnStampMessageStillDecodes() {
        // Rolling upgrade: the collection holds work the previous build wrote, whose payload has no turnId key at all.
        // The reader must degrade to "unknown turn" instead of throwing — an undecodable message is a lost turn.
        final Document stored = new Document("_id", new ObjectId()).append("conversationId", "conv-42")
                .append("priority", 1).append("deliveredAt", Date.from(Instant.parse("2026-04-27T10:00:00Z")))
                .append("payload", new Document("agentRef", "agent-x").append("userInput", "hello")
                        .append("deliveredAt", Date.from(Instant.parse("2026-04-27T10:00:00Z"))).append("initiator",
                                new Document("type", "USER").append("id", "u-1").append("displayName", "alice")));

        final InboundMessage decoded = codec.decode(stored);

        assertThat(decoded.getTurnId()).isEmpty();
        assertThat(decoded.getUserInput()).isEqualTo("hello");
    }

    @Test
    @DisplayName("a message already stored under \"conversationId\" still decodes to its conversation")
    void conversationIdKeyIsFrozenOnDecode() {
        // FROZEN WIRE FORMAT. This codec only reads the key — the writer side is MongoSessionInbox.deliver, pinned
        // separately by MongoSessionInboxTest — but the read is what makes an existing backlog collectable after a
        // deploy. A decode()-side rename makes every message already in conversation_inbox undecodable while the
        // round-trip tests above stay green, because they wrap() the document with the same constant the reader uses.
        // Hence the literal: a rename sweep carries the constant and all its references along in lockstep, so a
        // constant-based assertion can never fail. The other keys are spelled out too so the whole document is exactly
        // what a pre-rename node wrote.
        final ObjectId id = new ObjectId();
        final Document stored = new Document("_id", id).append("conversationId", "conv-42").append("priority", 1)
                .append("deliveredAt", Date.from(Instant.parse("2026-04-27T10:00:00Z")))
                .append("payload", new Document("agentRef", "agent-x").append("userInput", "hello")
                        .append("deliveredAt", Date.from(Instant.parse("2026-04-27T10:00:00Z"))).append("initiator",
                                new Document("type", "USER").append("id", "u-1").append("displayName", "alice")));

        final InboundMessage decoded = codec.decode(stored);

        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("conv-42"));
        assertThat(decoded.getId()).contains(InboundMessageId.of(id.toHexString()));
    }

    private InboundMessage.Builder baseMessage() {
        return InboundMessage.builder().id(InboundMessageId.of(new ObjectId().toHexString()))
                .sessionId(SessionId.of("c-1")).agentRef("agent-x").userInput("hello")
                .priority(QueuedInputPriority.NEXT)
                .initiator(Principal.builder().type(Principal.Type.USER).id("u-1").displayName("alice").build())
                .deliveredAt(Instant.parse("2026-04-27T10:00:00Z"));
    }

    private Document wrap(Document payload, InboundMessage message) {
        return new Document(DocumentKeys.F_ID, new ObjectId(message.getId().orElseThrow().value()))
                .append(DocumentKeys.F_CONVERSATION_ID, message.getSessionId().value())
                .append(DocumentKeys.F_PRIORITY, message.getPriority().ordinal())
                .append(DocumentKeys.F_DELIVERED_AT, Date.from(message.getDeliveredAt()))
                .append(DocumentKeys.F_PAYLOAD, payload);
    }

    /**
     * The cross-representation guard, and the reason {@link SubmitOptionsCodec} publishes its field names.
     *
     * <p>
     * This codec is the one representation of the {@code submitOptions} shape that cannot call that class: its
     * currency is a BSON {@link Document}, not an {@code ObjectNode}. The Redis and Postgres inboxes converged onto
     * it and no longer map the subtree at all, so what remains is two encoders that have to agree without sharing
     * code.
     *
     * <p>
     * A round-trip test cannot notice them disagreeing. Add a sixth property to {@code SubmitOptions}, handle it in
     * the shared codec only, and everything here still passes — this codec never writes it, never reads it, and
     * compares equal to a fixture that never set it. What breaks is a turn that goes through a Mongo inbox instead of
     * a Redis one, silently, in production.
     *
     * <p>
     * So the assertion is against the other encoder's published names rather than against literals of this test's
     * own, at all three levels. The core-side twin ({@code SubmitOptionsCodecTest}) asserts the same three sets
     * against the declared properties of the types themselves, which is what makes the pair closed: a new property
     * fails there, and handling it only in the shared codec fails here.
     */
    @Test
    @DisplayName("the BSON subtree carries exactly the keys the shared JSON codec publishes, at every level")
    void theBsonSubtreeCarriesExactlyTheSharedKeys() {
        final SubmitOptions options = SubmitOptions.builder()
                .principal(Principal.builder().type(Principal.Type.USER).id("u-1").displayName("alice").build())
                .systemPromptVariable("region", "eu").executionAttribute("rollout", "on")
                .llmCallMetadata(LlmCallMetadata.builder().component("orca-agent").parentComponent("web-facade")
                        .feature(LlmCallMetadata.Feature.REACT_LOOP).traceId("trace-9")
                        .principal(Principal.builder().type(Principal.Type.SERVICE).id("svc-1")
                                .displayName("dispatcher").build())
                        .tag("tenant", "acme").build())
                .userContextInjection(false).build();
        final InboundMessage message = baseMessage().submitOptions(options).build();

        final Document subtree = codec.encodePayload(message).get("submitOptions", Document.class);

        assertThat(subtree.keySet()).isEqualTo(SubmitOptionsCodec.TOP_LEVEL_FIELDS);
        assertThat(subtree.get(SubmitOptionsCodec.FIELD_PRINCIPAL, Document.class).keySet())
                .isEqualTo(SubmitOptionsCodec.PRINCIPAL_FIELDS);
        assertThat(subtree.get(SubmitOptionsCodec.FIELD_LLM_CALL_METADATA, Document.class).keySet())
                .isEqualTo(SubmitOptionsCodec.LLM_CALL_METADATA_FIELDS);
    }
}
