package at.aimon.session.mongodb.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;

/**
 * Unit tests for {@link IdempotencyEntryCodec} — a document round-trip exercised without a container so it runs under
 * the daemonless {@code test} task, plus the frozen wire key the round-trip cannot see.
 */
@DisplayName("IdempotencyEntryCodec — round-trip, and the frozen wire key the round-trip cannot see")
class IdempotencyEntryCodecTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-07T10:00:00Z");

    private static final Instant TOUCHED_AT = Instant.parse("2026-07-07T10:01:00Z");

    private final IdempotencyEntryCodec codec = new IdempotencyEntryCodec();

    @Test
    @DisplayName("an in-flight entry survives encode → decode")
    void roundTripInFlight() {
        final IdempotencyEntry original = entry("conv-1");

        // encode() deliberately leaves _id to the caller (it is the idempotency key), so the decode side has to be
        // handed the same document the store would have written back.
        final Document doc = codec.encode(original).append(DocumentKeys.F_ID, original.getKey());
        final IdempotencyEntry decoded = codec.decode(doc);

        assertThat(decoded.getKey()).isEqualTo("idem-1");
        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("conv-1"));
        assertThat(decoded.getInputHash()).isEqualTo("hash-1");
        assertThat(decoded.getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(decoded.getHolderId()).contains("node-A/1");
        assertThat(decoded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(decoded.getLastTouchedAt()).isEqualTo(TOUCHED_AT);
    }

    @Test
    @DisplayName("the target conversation is encoded under the frozen wire key \"conversationId\"")
    void conversationIdKeyIsFrozenOnEncode() {
        // FROZEN WIRE FORMAT. Idempotency entries outlive the turn that created them (that is their whole point) and
        // sit under a TTL index, so a rename splits the fleet into writers and readers that disagree about the key
        // while both look healthy — the replay guarantee quietly stops holding. This assertion deliberately spells the
        // key out instead of going through DocumentKeys.F_CONVERSATION_ID — a rename sweep would carry the constant and
        // every reference to it along in lockstep, so a constant-based assertion can never fail.
        final Document doc = codec.encode(entry("conv-2"));

        assertThat(doc.keySet()).contains("conversationId");
        assertThat(doc.getString("conversationId")).isEqualTo("conv-2");
    }

    @Test
    @DisplayName("an entry already stored under \"conversationId\" still decodes to its conversation")
    void conversationIdKeyIsFrozenOnDecode() {
        // The reader half of the freeze: guarding encode() alone would still let a decode()-side rename orphan every
        // entry already in the collection. Nothing else notices — SessionId.of(null) is what a renamed reader
        // produces, and the failure surfaces as a replay against the wrong session rather than a missing key.
        final Document stored = new Document().append("_id", "idem-3").append("conversationId", "conv-3")
                .append("inputHash", "hash-3").append("status", "DONE").append("createdAt", Date.from(CREATED_AT))
                .append("lastTouchedAt", Date.from(TOUCHED_AT));

        final IdempotencyEntry decoded = codec.decode(stored);

        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("conv-3"));
        assertThat(decoded.getKey()).isEqualTo("idem-3");
    }

    private static IdempotencyEntry entry(String sessionId) {
        return IdempotencyEntry.builder().key("idem-1").sessionId(SessionId.of(sessionId)).inputHash("hash-1")
                .status(IdempotencyEntry.Status.IN_FLIGHT).holderId("node-A/1").createdAt(CREATED_AT)
                .lastTouchedAt(TOUCHED_AT).build();
    }
}
