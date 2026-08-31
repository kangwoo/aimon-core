package at.aimon.session.redis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Round-trip tests for the Redis {@link InboundMessageCodec}, focused on {@link SubmitOptions} preservation through
 * the JSON wire format, plus the frozen wire key no round-trip can see. Mirrors the equivalent Postgres / Mongo codec
 * tests so cross-backend behavior stays locked-down.
 */
@DisplayName("Redis InboundMessageCodec — submitOptions round-trip, and the frozen wire key the round-trip cannot see")
class InboundMessageCodecTest {

    private final InboundMessageCodec codec = new InboundMessageCodec(new ObjectMapper());

    @Test
    @DisplayName("empty SubmitOptions round-trips as SubmitOptions.empty()")
    void emptySubmitOptions() {
        final InboundMessage message = baseMessage().submitOptions(SubmitOptions.empty()).build();

        final String json = codec.encode(message);
        final InboundMessage decoded = codec.decode(json, "1700000000000-0");

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
        final InboundMessage decoded = codec.decode(json, "1700000000000-0");

        assertThat(decoded.getSubmitOptions()).isEqualTo(options);
        assertThat(json).contains("\"principal\"");
    }

    @Test
    @DisplayName("nested Map values round-trip as LinkedHashMap (cross-backend uniformity)")
    void nestedMapRoundTripsAsLinkedHashMap() {
        final SubmitOptions options = SubmitOptions.builder()
                .systemPromptVariable("nested", Map.of("inner", 1, "deep", Map.of("k", "v"))).build();
        final InboundMessage message = baseMessage().submitOptions(options).build();

        final InboundMessage decoded = codec.decode(codec.encode(message), "1700000000000-0");

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

        final InboundMessage decoded = codec.decode(codec.encode(message), "1700000000000-0");

        assertThat(decoded.getSubmitOptions().getUserContextInjection()).contains(Boolean.TRUE);
        assertThat(decoded.getSubmitOptions().getPrincipal()).isEmpty();
        assertThat(decoded.getSubmitOptions().getLlmCallMetadata()).isEmpty();
        assertThat(decoded.getSubmitOptions().getSystemPromptVariables()).isEmpty();
        assertThat(decoded.getSubmitOptions().getExecutionAttributes()).isEmpty();
    }

    @Test
    @DisplayName("turnId round-trips, and its absence round-trips as absence")
    void turnIdRoundTrip() {
        final InboundMessage stamped = baseMessage().turnId(TurnId.of("turn-42")).build();

        final String json = codec.encode(stamped);
        assertThat(codec.decode(json, "1700000000000-0").getTurnId()).contains(TurnId.of("turn-42"));
        assertThat(json).contains("\"turnId\":\"turn-42\"");

        // Absence must stay absence rather than becoming a fabricated id: the holder mints one only because it can see
        // the field is missing, and a placeholder would be reported to nobody while looking like a real address.
        final String bare = codec.encode(baseMessage().build());
        assertThat(bare).doesNotContain("turnId");
        assertThat(codec.decode(bare, "1700000000000-0").getTurnId()).isEmpty();
    }

    @Test
    @DisplayName("contextDiscriminator round-trips, and its absence round-trips as absence")
    void contextDiscriminatorRoundTrip() {
        final String json = codec.encode(baseMessage().contextDiscriminator("tenant-a").build());
        assertThat(json).contains("\"contextDiscriminator\":\"tenant-a\"");
        assertThat(codec.decode(json, "1700000000000-0").getContextDiscriminator()).contains("tenant-a");

        // Absence must stay absence: empty means "open the bare agent:<ref> runtime", whereas a fabricated
        // discriminator names a runtime nobody registered and fails the open outright.
        final String bare = codec.encode(baseMessage().build());
        assertThat(bare).doesNotContain("contextDiscriminator");
        assertThat(codec.decode(bare, "1700000000000-0").getContextDiscriminator()).isEmpty();
    }

    @Test
    @DisplayName("an entry written before the turn stamp existed still decodes, with no turn")
    void preTurnStampEntryStillDecodes() {
        // Rolling upgrade: the stream holds work the previous build wrote. Those entries have no turnId, and the reader
        // must degrade to "unknown turn" instead of throwing — an inbox entry that cannot be decoded is a lost turn.
        final InboundMessage decoded = codec.decode("{\"conversationId\":\"c-9\",\"agentRef\":\"agent-x\","
                + "\"userInput\":\"hello\",\"priority\":\"NEXT\","
                + "\"initiator\":{\"type\":\"USER\",\"id\":\"u-1\",\"displayName\":\"alice\"},"
                + "\"deliveredAt\":\"2026-04-27T10:00:00Z\"}", "1700000000000-0");

        assertThat(decoded.getTurnId()).isEmpty();
        assertThat(decoded.getUserInput()).isEqualTo("hello");
    }

    @Test
    @DisplayName("the target conversation is encoded under the frozen wire key \"conversationId\"")
    void conversationIdKeyIsFrozenOnEncode() throws IOException {
        // FROZEN WIRE FORMAT. The key inside a Stream entry is not allowed to follow a rename of the Java identifier:
        // an inbox holds work that has not been done yet, so at any upgrade the stream still contains entries the old
        // build wrote and the new build has to route them. None of the round-trips above can protect the name --
        // renaming encode() and decode() together leaves them all green. The literal is spelled out here rather than
        // read from the codec on purpose; anything that resolves through the production side renames in lockstep with
        // it and can therefore never fail.
        final InboundMessage message = baseMessage().build();

        final JsonNode root = new ObjectMapper().readTree(codec.encode(message));

        final List<String> fields = new ArrayList<>();
        root.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("conversationId");
        assertThat(root.get("conversationId").asText()).isEqualTo("c-1");
    }

    @Test
    @DisplayName("an entry already in the stream under \"conversationId\" still decodes to its conversation")
    void conversationIdKeyIsFrozenOnDecode() {
        // The reader half of the freeze. Guarding encode() alone would still let a decode()-side rename strand every
        // message sitting in the stream at upgrade time, and it would do so loudly at the worst moment -- the reader
        // NPEs on the missing key rather than degrading -- so the fixture is a hand-written payload in the shape the
        // older build emitted.
        final InboundMessage decoded = codec.decode("{\"conversationId\":\"c-9\",\"agentRef\":\"agent-x\","
                + "\"userInput\":\"hello\",\"priority\":\"NEXT\","
                + "\"initiator\":{\"type\":\"USER\",\"id\":\"u-1\",\"displayName\":\"alice\"},"
                + "\"deliveredAt\":\"2026-04-27T10:00:00Z\"}", "1700000000000-0");

        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("c-9"));
    }

    /**
     * The regression guard for converging this codec onto {@link at.aimon.core.subagent.task.codec.SubmitOptionsCodec}.
     *
     * <p>
     * That class carries a private {@code ObjectMapper} of its own for callers with no opinion, and calling it that way
     * from here would have compiled, round-tripped, and passed every other test in this file. What it would have
     * changed is invisible from inside a round-trip: {@code systemPromptVariables} and {@code executionAttributes} are
     * {@code Map<String, Object>}, so a temporal value in one of them is written according to the mapper's
     * configuration — and {@link at.aimon.session.redis.RedisSessionInbox} both defaults to a mapper with
     * {@code JavaTimeModule} registered and lets the application supply its own. One subtree of the document would
     * have kept following the private mapper's rules while the document around it followed the application's.
     *
     * <p>
     * The two configurations below are the same {@code Instant} under mappers that disagree about it, so the
     * assertion is on encoded text rather than on a decoded value — a round-trip is exactly what cannot see this.
     */
    @Test
    @DisplayName("the inbox's mapper reaches inside the submitOptions subtree, not just the envelope around it")
    void theInboxMapperReachesInsideTheSubmitOptionsSubtree() throws IOException {
        final SubmitOptions options = SubmitOptions.builder()
                .executionAttribute("deadline", Instant.parse("2026-08-28T09:15:00Z")).build();
        final InboundMessage message = baseMessage().submitOptions(options).build();

        final InboundMessageCodec configured = new InboundMessageCodec(new ObjectMapper()
                .registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        final JsonNode root = new ObjectMapper().readTree(configured.encode(message));

        // An ISO-8601 string here is only reachable through the mapper handed to this codec: the shared codec's own
        // mapper has no java.time module, so the same value would be a number, an object, or an outright failure
        // depending on the Jackson build. Which of those it is not worth pinning; that it is this is.
        assertThat(root.get("submitOptions").get("executionAttributes").get("deadline").asText())
                .isEqualTo("2026-08-28T09:15:00Z");
    }

    private InboundMessage.Builder baseMessage() {
        return InboundMessage.builder().id(InboundMessageId.of("1700000000000-0")).sessionId(SessionId.of("c-1"))
                .agentRef("agent-x").userInput("hello").priority(QueuedInputPriority.NEXT)
                .initiator(Principal.builder().type(Principal.Type.USER).id("u-1").displayName("alice").build())
                .deliveredAt(Instant.parse("2026-04-27T10:00:00Z"));
    }
}
