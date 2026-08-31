package at.aimon.session.redis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;

@DisplayName("IdempotencyEntryCodec — JSON round-trip, and the frozen wire key the round-trip cannot see")
class IdempotencyEntryCodecTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final IdempotencyEntryCodec codec = new IdempotencyEntryCodec(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    @DisplayName("an in-flight entry round-trips field-by-field")
    void inFlightRoundTrip() {
        final IdempotencyEntry entry = IdempotencyEntry.builder().key("idem-1").sessionId(SessionId.of("c-1"))
                .inputHash("sha256:abc").status(IdempotencyEntry.Status.IN_FLIGHT).holderId("node-a")
                .createdAt(Instant.parse("2026-04-27T10:00:00Z")).lastTouchedAt(Instant.parse("2026-04-27T10:00:05Z"))
                .build();

        final IdempotencyEntry decoded = codec.decode(codec.encode(entry, TTL));

        assertThat(decoded.getKey()).isEqualTo("idem-1");
        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("c-1"));
        assertThat(decoded.getInputHash()).isEqualTo("sha256:abc");
        assertThat(decoded.getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(decoded.getHolderId()).contains("node-a");
        assertThat(decoded.getResult()).isEmpty();
        assertThat(decoded.getCreatedAt()).isEqualTo(Instant.parse("2026-04-27T10:00:00Z"));
        assertThat(decoded.getLastTouchedAt()).isEqualTo(Instant.parse("2026-04-27T10:00:05Z"));
    }

    @Test
    @DisplayName("the guarded conversation is encoded under the frozen wire key \"conversationId\"")
    void conversationIdKeyIsFrozenOnEncode() throws IOException {
        // FROZEN WIRE FORMAT. The key inside the stored STRING is not allowed to follow a rename of the Java
        // identifier. Outliving the turn it guards is the whole point of an entry -- it waits out its TTL for a retry
        // that may arrive after the writer has been replaced -- so a reader on a new build is by definition reading
        // what an older build wrote. The round-trip above cannot protect the name: renaming encode() and decode()
        // together leaves it green. The literal is spelled out here rather than read from the codec on purpose;
        // anything resolving through the production side renames in lockstep with it and can therefore never fail.
        final IdempotencyEntry entry = IdempotencyEntry.builder().key("idem-2").sessionId(SessionId.of("c-7"))
                .inputHash("sha256:def").status(IdempotencyEntry.Status.IN_FLIGHT).holderId("node-a")
                .createdAt(Instant.parse("2026-04-27T10:00:00Z")).lastTouchedAt(Instant.parse("2026-04-27T10:00:05Z"))
                .build();

        final JsonNode root = new ObjectMapper().readTree(codec.encode(entry, TTL));

        final List<String> fields = new ArrayList<>();
        root.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("conversationId");
        assertThat(root.get("conversationId").asText()).isEqualTo("c-7");
    }

    @Test
    @DisplayName("an entry already in Redis under \"conversationId\" still decodes to its conversation")
    void conversationIdKeyIsFrozenOnDecode() {
        // The reader half of the freeze. Guarding encode() alone would leave a decode()-side rename free to misread
        // every entry still under TTL at upgrade time, and misreading here is not a cosmetic loss: the entry exists so
        // a replayed turn resolves to the session that already ran it.
        final IdempotencyEntry decoded = codec
                .decode("{\"key\":\"idem-3\",\"conversationId\":\"c-7\",\"inputHash\":\"sha256:def\","
                        + "\"status\":\"DONE\",\"holderId\":null,\"createdAt\":\"2026-04-27T10:00:00Z\","
                        + "\"lastTouchedAt\":\"2026-04-27T10:00:05Z\",\"ttlMillis\":1800000,\"result\":null}");

        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("c-7"));
    }
}
