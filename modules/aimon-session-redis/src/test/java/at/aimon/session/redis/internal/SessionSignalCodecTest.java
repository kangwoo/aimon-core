package at.aimon.session.redis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;

@DisplayName("SessionSignalCodec — JSON round-trip, and the frozen wire key the round-trip cannot see")
class SessionSignalCodecTest {

    private final SessionSignalCodec codec = new SessionSignalCodec(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    @DisplayName("an envelope with a primitive payload round-trips field-by-field")
    void roundTrip() {
        final SessionSignal signal = SessionSignal.builder().sessionId(SessionId.of("c-1")).kind(SignalKind.INTERRUPT)
                .originNodeId("node-a").payload(Map.of("reason", "user-abort")).build();

        final SessionSignal decoded = codec.decode(codec.encode(signal));

        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("c-1"));
        assertThat(decoded.getKind()).isEqualTo(SignalKind.INTERRUPT);
        assertThat(decoded.getOriginNodeId()).isEqualTo("node-a");
        assertThat(decoded.getPayload()).containsEntry("reason", "user-abort");
    }

    @Test
    @DisplayName("the addressed conversation is encoded under the frozen wire key \"conversationId\"")
    void conversationIdKeyIsFrozenOnEncode() throws IOException {
        // FROZEN WIRE FORMAT. The key on the bus is not allowed to follow a rename of the Java identifier. The
        // exposure here is not the past but the node next door: a rolling upgrade has publishers and subscribers on
        // different builds at the same time, so a renamed key means INTERRUPT and EVICT cross the bus and address
        // nobody. The round-trip above cannot see it -- both halves live in one process, so renaming encode() and
        // decode() together leaves it green. The literal is spelled out here rather than read from the codec on
        // purpose; anything resolving through the production side renames in lockstep with it and can never fail.
        final SessionSignal signal = SessionSignal.builder().sessionId(SessionId.of("c-7")).kind(SignalKind.EVICT)
                .originNodeId("node-a").build();

        final JsonNode root = new ObjectMapper().readTree(codec.encode(signal));

        final List<String> fields = new ArrayList<>();
        root.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("conversationId");
        assertThat(root.get("conversationId").asText()).isEqualTo("c-7");
    }

    @Test
    @DisplayName("an envelope published under \"conversationId\" still decodes to its conversation")
    void conversationIdKeyIsFrozenOnDecode() {
        // The reader half of the freeze, and on a bus it is the half that matters most: the subscriber is the side
        // that gets upgraded while the other node keeps publishing the old shape. Guarding encode() alone would let a
        // decode()-side rename drop every signal arriving from a node that has not rolled yet.
        final SessionSignal decoded = codec.decode("{\"conversationId\":\"c-7\",\"kind\":\"INTERRUPT\","
                + "\"originNodeId\":\"node-b\",\"payload\":{\"reason\":\"user-abort\"}}");

        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("c-7"));
    }
}
