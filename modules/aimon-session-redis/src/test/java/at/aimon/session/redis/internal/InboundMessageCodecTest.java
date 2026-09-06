package at.aimon.session.redis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.MultimodalInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.UnreadableEntry;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Round-trip tests for the Redis {@link InboundMessageCodec}, focused on {@link SubmitOptions} preservation through
 * the JSON wire format, plus the frozen wire key no round-trip can see. Mirrors the equivalent Postgres / Mongo codec
 * tests so cross-backend behavior stays locked-down.
 */
@DisplayName("Redis InboundMessageCodec — submitOptions round-trip, and the frozen wire key the round-trip cannot see")
class InboundMessageCodecTest {

    private static final String ENTRY_ID = "1700000000000-0";

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
        assertThat(decoded.getUserInput()).isEqualTo(TextInput.of("hello"));
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

    @Test
    @DisplayName("a multimodal submission survives the wire, image bytes and all")
    void multimodalSurvivesTheWire() {
        final UserInput input = MultimodalInput.of(TextInput.of("what is in this?"),
                ImageInput.of(new byte[]{1, 2, 3, 4}, "image/png"));
        final InboundMessage message = baseMessage().userInput(input).build();

        final InboundMessage decoded = codec.decode(codec.encode(message), "1700000000000-0");

        assertThat(decoded.getUserInput()).isEqualTo(input);
    }

    @Test
    @DisplayName("plain text is written exactly as it was before the envelope widened")
    void textIsWrittenInTheOldShape() throws IOException {
        // The half of the compatibility contract that faces backwards. A node running the previous build reads
        // root.get("userInput").asText(); if this key ever became an object, that reader would get "" from Jackson and
        // run an empty turn -- silently, which is the worst shape this change could take. So text stays a string and
        // gains no sidecar at all: byte-for-byte the document the previous build wrote.
        final JsonNode root = new ObjectMapper().readTree(codec.encode(baseMessage().build()));

        assertThat(root.get("userInput").isTextual()).isTrue();
        assertThat(root.get("userInput").asText()).isEqualTo("hello");
        assertThat(root.has("userInputEncoded")).isFalse();
    }

    @Test
    @DisplayName("a multimodal entry still leaves a reader that predates it something to run")
    void multimodalLeavesTheOldKeyReadable() throws IOException {
        // The other half: this build's document has to stay decodable by the previous one. It cannot run the image
        // either way, so what it gets under the old key is the asText() rendering -- a turn that says an image was
        // attached rather than one that pretends nothing was. The alternative, omitting or restructuring the key,
        // makes the entry undecodable on that node, and an inbox entry nobody can decode is a turn nobody runs.
        final UserInput input = MultimodalInput.of(TextInput.of("what is in this?"),
                ImageInput.of(new byte[]{1, 2, 3, 4}, "image/png"));

        final JsonNode root = new ObjectMapper().readTree(codec.encode(baseMessage().userInput(input).build()));

        assertThat(root.get("userInput").isTextual()).isTrue();
        assertThat(root.get("userInput").asText()).isEqualTo(input.asText());
        assertThat(root.get("userInputEncoded").get("type").asText()).isEqualTo("multimodal");
    }

    @Test
    @DisplayName("an entry written before the encoding existed decodes as the text it carries")
    void preEncodingEntryDecodesAsText() {
        // Rolling upgrade, forwards this time: the stream holds entries the previous build wrote, which have only the
        // string. Absent sidecar means the entry is text, which is exactly what it was.
        final InboundMessage decoded = codec.decode("{\"conversationId\":\"c-9\",\"agentRef\":\"agent-x\","
                + "\"userInput\":\"hello\",\"priority\":\"NEXT\","
                + "\"initiator\":{\"type\":\"USER\",\"id\":\"u-1\",\"displayName\":\"alice\"},"
                + "\"deliveredAt\":\"2026-04-27T10:00:00Z\"}", "1700000000000-0");

        assertThat(decoded.getUserInput()).isEqualTo(TextInput.of("hello"));
    }

    @Test
    @DisplayName("a sidecar this build cannot read degrades to the text beside it, rather than losing the batch")
    void unreadableSidecarDegradesToText() {
        // The mirror of the compatibility direction the rest of this file guards, and the one this branch created:
        // a *newer* node writes an entry whose userInputEncoded names an input type this build does not have.
        // collectTier decodes AFTER the Lua script has already XDEL'd the whole batch, so a throw here does not
        // reject one entry -- it loses every entry collected in that call, permanently. The old key has a usable
        // string right beside it, which is exactly what an older node would have run.
        final String stored = "{\"conversationId\":\"c-9\",\"agentRef\":\"agent-x\","
                + "\"userInput\":\"what is in this?\","
                + "\"userInputEncoded\":{\"type\":\"video\",\"mimeType\":\"video/mp4\",\"data\":\"AQID\"},"
                + "\"priority\":\"NEXT\","
                + "\"initiator\":{\"type\":\"USER\",\"id\":\"u-1\",\"displayName\":\"alice\"},"
                + "\"deliveredAt\":\"2026-04-27T10:00:00Z\"}";

        final InboundMessage decoded = codec.decode(stored, "1700000000000-0");

        assertThat(decoded.getUserInput()).isEqualTo(TextInput.of("what is in this?"));
        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("c-9"));
    }

    @Test
    @DisplayName("a broken envelope field still throws — the drop policy is the caller's, not this codec's")
    void aBrokenEnvelopeFieldStillRefuses() {
        // The design corrects what happens *after* the throw, not whether there is one. Widening the user-input
        // degradation to the envelope would run a damaged document as if it were sound: `initiator`, `priority` and
        // `deliveredAt` have no plain-text stand-in sitting beside them the way the input does.
        final String damaged = "{\"conversationId\":\"c-9\",\"agentRef\":\"agent-x\","
                + "\"userInput\":\"hello\",\"priority\":\"NEXT\","
                + "\"initiator\":{\"type\":\"ROBOT\",\"id\":\"u-1\",\"displayName\":\"alice\"},"
                + "\"deliveredAt\":\"2026-04-27T10:00:00Z\"}";

        assertThatThrownBy(() -> codec.decode(damaged, ENTRY_ID)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("address recovery reads the two ids out of the very payload that failed")
    void addressRecoveryReadsWhatSurvived() {
        final String damaged = "{\"conversationId\":\"c-9\",\"agentRef\":\"agent-x\","
                + "\"userInput\":\"hello\",\"priority\":\"NEXT\","
                + "\"turnId\":\"turn-abc\",\"idempotencyKey\":\"key-abc\","
                + "\"initiator\":{\"type\":\"ROBOT\",\"id\":\"u-1\",\"displayName\":\"alice\"},"
                + "\"deliveredAt\":\"2026-04-27T10:00:00Z\"}";

        final UnreadableEntry entry = codec.recoverAddress(damaged, ENTRY_ID, new IllegalArgumentException("boom"));

        assertThat(entry.getTurnId()).hasValue("turn-abc");
        assertThat(entry.getIdempotencyKey()).hasValue("key-abc");
        assertThat(entry.isAddressable()).isTrue();
        assertThat(entry.getReason()).contains("boom");
    }

    @Test
    @DisplayName("address recovery never throws, whatever it is handed")
    void addressRecoveryIsTotal() {
        // It runs inside the per-entry guard, on input that has already proved unreadable. A throw here would put
        // the batch loss back exactly where the guard removed it.
        final IllegalStateException cause = new IllegalStateException();

        assertThat(codec.recoverAddress("{not json at all", ENTRY_ID, cause).isAddressable()).isFalse();
        assertThat(codec.recoverAddress("[]", ENTRY_ID, cause).isAddressable()).isFalse();
        assertThat(codec.recoverAddress(null, ENTRY_ID, cause).isAddressable()).isFalse();
        // getMessage() is null on that exception; the reason must still be usable.
        assertThat(codec.recoverAddress(null, ENTRY_ID, cause).getReason()).isNotBlank();
        // The entry-id axis too: InboundMessageId refuses an empty value, and that refusal would land inside the
        // per-entry guard as a second throw — exactly what this method exists not to do.
        assertThat(codec.recoverAddress(null, null, cause).getId().value()).isEqualTo("unknown");
        assertThat(codec.recoverAddress(null, "", cause).getId().value()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("the reason names the field that failed, and never the payload around it")
    void theReasonNamesTheFailingFieldAndNotThePayload() {
        // Driven by a real decode failure rather than an exception the test invents, because the claim is about what
        // the codec's own failures carry. An unparsable deliveredAt is the shape that reaches here: the user input
        // degrades long before this point, so what is left is the envelope.
        final String damaged = "{\"conversationId\":\"c-9\",\"agentRef\":\"agent-x\","
                + "\"userInput\":\"POISON-SECRET-TEXT\",\"priority\":\"NEXT\","
                + "\"initiator\":{\"type\":\"USER\",\"id\":\"u-1\",\"displayName\":\"alice\"},"
                + "\"deliveredAt\":\"not-a-timestamp\"}";

        final RuntimeException cause = catchRuntimeException(() -> codec.decode(damaged, ENTRY_ID));
        final UnreadableEntry entry = codec.recoverAddress(damaged, ENTRY_ID, cause);

        // The value that failed IS named — without it an operator cannot tell a newer build's document from a
        // damaged one, which is the question this path exists to answer.
        assertThat(entry.getReason()).contains("not-a-timestamp");
        // The rest of the document is not. Nothing appends the payload, and the input a user wrote cannot reach a
        // failure here at all.
        assertThat(entry.getReason()).doesNotContain("POISON-SECRET-TEXT");
    }

    @Test
    @DisplayName("an address that is present but not text does not count as an address")
    void aNonTextAddressIsNotAnAddress() {
        // asText() answers "" for an object or an array node, so without folding blank to absent this document
        // would recover an empty key, call itself addressable, and have the router announce a turn under an
        // address nobody registered.
        final String damaged = "{\"conversationId\":\"c-9\",\"turnId\":{},\"idempotencyKey\":[]}";

        final UnreadableEntry entry = codec.recoverAddress(damaged, ENTRY_ID, new IllegalStateException("boom"));

        assertThat(entry.getTurnId()).isEmpty();
        assertThat(entry.getIdempotencyKey()).isEmpty();
        assertThat(entry.isAddressable()).isFalse();
    }

    private static RuntimeException catchRuntimeException(Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected the decode to fail");
    }

    private InboundMessage.Builder baseMessage() {
        return InboundMessage.builder().id(InboundMessageId.of("1700000000000-0")).sessionId(SessionId.of("c-1"))
                .agentRef("agent-x").userInput("hello").priority(QueuedInputPriority.NEXT)
                .initiator(Principal.builder().type(Principal.Type.USER).id("u-1").displayName("alice").build())
                .deliveredAt(Instant.parse("2026-04-27T10:00:00Z"));
    }
}
