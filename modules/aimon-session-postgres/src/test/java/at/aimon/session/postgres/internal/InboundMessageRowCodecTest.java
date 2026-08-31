package at.aimon.session.postgres.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Round-trip tests for {@link InboundMessageRowCodec}, focused on {@link SubmitOptions} preservation through the
 * Postgres JSONB wire format. Mirrors the equivalent Redis / Mongo codec tests so cross-backend behavior stays
 * locked-down. Plus the frozen payload key the round-trips cannot see.
 */
@DisplayName("InboundMessageRowCodec submitOptions round-trip, and the frozen key round-trips cannot see")
class InboundMessageRowCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final InboundMessageRowCodec codec = new InboundMessageRowCodec(mapper);

    @Test
    @DisplayName("empty SubmitOptions round-trips as SubmitOptions.empty()")
    void emptySubmitOptions() {
        final InboundMessage message = baseMessage().submitOptions(SubmitOptions.empty()).build();

        final String json = codec.encode(message);
        final InboundMessage decoded = codec.decode(json, "42");

        assertThat(decoded.getSubmitOptions()).isEqualTo(SubmitOptions.empty());
        assertThat(json).doesNotContain("submitOptions");
    }

    @Test
    @DisplayName("fully populated SubmitOptions round-trips field-by-field")
    void fullSubmitOptionsRoundTrip() {
        final SubmitOptions options = SubmitOptions.builder().principal(Principal.user("u-1", "alice"))
                .systemPromptVariable("region", "eu").systemPromptVariable("attempt", 3)
                .executionAttribute("ab.x", true).executionAttribute("rollout", "on")
                .llmCallMetadata(LlmCallMetadata.builder().component("orca-agent").parentComponent("web-facade")
                        .feature(LlmCallMetadata.Feature.REACT_LOOP).traceId("trace-9")
                        .principal(Principal.builder().type(Principal.Type.SERVICE).id("svc-1")
                                .displayName("dispatcher").build())
                        .tag("tenant", "acme").build())
                .userContextInjection(false).build();
        final InboundMessage message = baseMessage().submitOptions(options).build();

        final String json = codec.encode(message);
        final InboundMessage decoded = codec.decode(json, "42");

        assertThat(decoded.getSubmitOptions()).isEqualTo(options);
    }

    @Test
    @DisplayName("nested Map values round-trip as LinkedHashMap (cross-backend uniformity)")
    void nestedMapRoundTripsAsLinkedHashMap() {
        final SubmitOptions options = SubmitOptions.builder()
                .systemPromptVariable("nested", Map.of("inner", 1, "deep", Map.of("k", "v"))).build();
        final InboundMessage message = baseMessage().submitOptions(options).build();

        final InboundMessage decoded = codec.decode(codec.encode(message), "1");

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

        final InboundMessage decoded = codec.decode(codec.encode(message), "1");

        assertThat(decoded.getSubmitOptions().getUserContextInjection()).contains(Boolean.TRUE);
        assertThat(decoded.getSubmitOptions().getPrincipal()).isEmpty();
        assertThat(decoded.getSubmitOptions().getLlmCallMetadata()).isEmpty();
        assertThat(decoded.getSubmitOptions().getSystemPromptVariables()).isEmpty();
        assertThat(decoded.getSubmitOptions().getExecutionAttributes()).isEmpty();
    }

    @Test
    @DisplayName("turnId round-trips through the JSONB payload, and its absence round-trips as absence")
    void turnIdRoundTrip() {
        final String json = codec.encode(baseMessage().turnId(TurnId.of("turn-42")).build());
        assertThat(codec.decode(json, "42").getTurnId()).contains(TurnId.of("turn-42"));
        assertThat(json).contains("\"turnId\":\"turn-42\"");

        // The id rides in the existing payload column, so no DDL change was needed — but that also means nothing but
        // this test pins it. Absence must stay absence: the holder mints an id only because it can see none was sent.
        final String bare = codec.encode(baseMessage().build());
        assertThat(bare).doesNotContain("turnId");
        assertThat(codec.decode(bare, "42").getTurnId()).isEmpty();
    }

    @Test
    @DisplayName("contextDiscriminator round-trips through the JSONB payload, and its absence round-trips as absence")
    void contextDiscriminatorRoundTrip() {
        final String json = codec.encode(baseMessage().contextDiscriminator("tenant-a").build());
        assertThat(json).contains("\"contextDiscriminator\":\"tenant-a\"");
        assertThat(codec.decode(json, "42").getContextDiscriminator()).contains("tenant-a");

        // Rides the existing payload column, so again no DDL and again nothing but this test pinning it. Absence must
        // stay absence: empty means "open the bare agent:<ref> runtime", which is what the submitter would have opened.
        final String bare = codec.encode(baseMessage().build());
        assertThat(bare).doesNotContain("contextDiscriminator");
        assertThat(codec.decode(bare, "42").getContextDiscriminator()).isEmpty();
    }

    @Test
    @DisplayName("a row written before the turn stamp existed still decodes, with no turn")
    void preTurnStampRowStillDecodes() {
        // Rolling upgrade: rows undelivered at deploy time have no turnId. The reader must degrade to "unknown turn"
        // rather than throw — an inbox row that cannot be decoded is a turn the submitter was promised and never gets.
        final String stored = "{\"conversationId\":\"c-42\",\"agentRef\":\"agent-x\",\"userInput\":\"hello\","
                + "\"priority\":\"NEXT\",\"initiator\":{\"type\":\"USER\",\"id\":\"u-1\",\"displayName\":\"alice\"},"
                + "\"deliveredAt\":\"2026-04-27T10:00:00Z\"}";

        final InboundMessage decoded = codec.decode(stored, "7");

        assertThat(decoded.getTurnId()).isEmpty();
        assertThat(decoded.getUserInput()).isEqualTo("hello");
    }

    @Test
    @DisplayName("the conversation id is written under the frozen payload key \"conversationId\"")
    void conversationIdKeyIsFrozenOnEncode() throws IOException {
        // FROZEN WIRE FORMAT. This key lives inside conversation_inbox.payload (JSONB), not in a column, so it is
        // invisible to the DDL freeze test and to every round-trip above — those encode and decode with the same
        // spelling and stay green through any rename. Rows undelivered at deploy time are read back by the new code,
        // so the reader and the writer must keep agreeing with the rows already on disk. Spelled out deliberately:
        // an assertion routed through a constant would be carried along by the very sweep it exists to stop.
        final InboundMessage message = baseMessage().sessionId(SessionId.of("c-42")).build();

        final JsonNode root = mapper.readTree(codec.encode(message));

        assertThat(root.has("conversationId")).isTrue();
        assertThat(root.get("conversationId").asText()).isEqualTo("c-42");
    }

    @Test
    @DisplayName("a row already stored under \"conversationId\" still decodes to its conversation")
    void conversationIdKeyIsFrozenOnDecode() {
        // The reader half: pinning encode alone would still let a decode-side rename orphan every inbox row written
        // before the deploy. Hand-built JSON rather than codec.encode() output, so the writer cannot supply the key
        // the reader is being tested for.
        final String stored = "{\"conversationId\":\"c-42\",\"agentRef\":\"agent-x\",\"userInput\":\"hello\","
                + "\"priority\":\"NEXT\",\"initiator\":{\"type\":\"USER\",\"id\":\"u-1\",\"displayName\":\"alice\"},"
                + "\"deliveredAt\":\"2026-04-27T10:00:00Z\"}";

        final InboundMessage decoded = codec.decode(stored, "7");

        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("c-42"));
    }

    private InboundMessage.Builder baseMessage() {
        return InboundMessage.builder().id(InboundMessageId.of("42")).sessionId(SessionId.of("c-1")).agentRef("agent-x")
                .userInput("hello").priority(QueuedInputPriority.NEXT)
                .initiator(Principal.builder().type(Principal.Type.USER).id("u-1").displayName("alice").build())
                .deliveredAt(Instant.parse("2026-04-27T10:00:00Z"));
    }
}
