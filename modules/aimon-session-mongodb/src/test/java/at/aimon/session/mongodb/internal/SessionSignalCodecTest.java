package at.aimon.session.mongodb.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;

/**
 * Unit tests for {@link SessionSignalCodec} — a capped-collection envelope round-trip exercised without a
 * container so it runs under the daemonless {@code test} task, plus the frozen wire key the round-trip cannot see.
 */
@DisplayName("SessionSignalCodec — round-trip, and the frozen wire key the round-trip cannot see")
class SessionSignalCodecTest {

    private final SessionSignalCodec codec = new SessionSignalCodec();

    @Test
    @DisplayName("an envelope with a payload survives encode → decode")
    void roundTripWithPayload() {
        final SessionSignal original = SessionSignal.builder().sessionId(SessionId.of("conv-1"))
                .kind(SignalKind.MESSAGE_ENQUEUED).originNodeId("node-A").payload(Map.of("messageId", "m-7")).build();

        final SessionSignal decoded = codec.decode(codec.encode(original));

        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("conv-1"));
        assertThat(decoded.getKind()).isEqualTo(SignalKind.MESSAGE_ENQUEUED);
        assertThat(decoded.getOriginNodeId()).isEqualTo("node-A");
        assertThat(decoded.getPayload()).containsEntry("messageId", "m-7");
    }

    @Test
    @DisplayName("the target conversation is encoded under the frozen wire key \"conversationId\"")
    void conversationIdKeyIsFrozenOnEncode() {
        // FROZEN WIRE FORMAT. The capped conversation_signals collection is read by every other node in the fleet,
        // including nodes still running the previous release, so the field name is part of the cross-node protocol and
        // not a private detail of this codec. This assertion deliberately spells the key out instead of going through
        // DocumentKeys.F_CONVERSATION_ID — a rename sweep would carry the constant and every reference to it along in
        // lockstep, so a constant-based assertion can never fail.
        final SessionSignal signal = SessionSignal.builder().sessionId(SessionId.of("conv-2"))
                .kind(SignalKind.INTERRUPT).originNodeId("node-A").build();

        final Document doc = codec.encode(signal);

        assertThat(doc.keySet()).contains("conversationId");
        assertThat(doc.getString("conversationId")).isEqualTo("conv-2");
    }

    @Test
    @DisplayName("an envelope already published under \"conversationId\" still decodes to its conversation")
    void conversationIdKeyIsFrozenOnDecode() {
        // The reader half of the freeze: guarding encode() alone would still let a decode()-side rename drop every
        // signal published by a node that has not been upgraded yet. A renamed reader does not fail loudly here — it
        // hands SessionId.of(null) a null and misroutes or blows up at delivery time, far from the cause.
        final Document published = new Document().append("conversationId", "conv-3").append("kind", "EVICT")
                .append("originNodeId", "node-B");

        final SessionSignal decoded = codec.decode(published);

        assertThat(decoded.getSessionId()).isEqualTo(SessionId.of("conv-3"));
        assertThat(decoded.getKind()).isEqualTo(SignalKind.EVICT);
    }
}
