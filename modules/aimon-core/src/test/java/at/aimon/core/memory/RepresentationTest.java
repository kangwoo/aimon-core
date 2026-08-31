package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;

@DisplayName("Representation")
class RepresentationTest {

    private Workspace ws() {
        return Workspace.builder().id("ws-1").build();
    }

    private PeerView peer(Workspace ws, String userId) {
        return PeerView.of(ws, Principal.user(userId, userId));
    }

    @Test
    @DisplayName("global representation (observer == null) is constructed successfully")
    void globalRepresentation() {
        PeerView subject = peer(ws(), "alice");

        Representation rep = Representation.builder().subject(subject).summary("Global summary")
                .generatedAt(Instant.parse("2024-01-15T10:00:00Z")).tokenCount(42).build();

        assertThat(rep.isGlobal()).isTrue();
        assertThat(rep.isLocal()).isFalse();
        assertThat(rep.getObserver()).isEmpty();
        assertThat(rep.getSessionId()).isEmpty();
        assertThat(rep.getSummary()).isEqualTo("Global summary");
        assertThat(rep.getTokenCount()).isEqualTo(42);
    }

    @Test
    @DisplayName("local representation with observer and sessionId")
    void localRepresentation() {
        Workspace ws = ws();
        PeerView subject = peer(ws, "alice");
        PeerView observer = peer(ws, "bob");

        Representation rep = Representation.builder().subject(subject).observer(observer).sessionId("sess-99")
                .summary("As bob sees alice").build();

        assertThat(rep.isGlobal()).isFalse();
        assertThat(rep.isLocal()).isTrue();
        assertThat(rep.getObserver()).isPresent().contains(observer);
        assertThat(rep.getSessionId()).isPresent().contains("sess-99");
    }

    @Test
    @DisplayName("global representation with sessionId throws IllegalArgumentException")
    void globalWithSessionIdRejected() {
        PeerView subject = peer(ws(), "alice");

        assertThatThrownBy(() -> Representation.builder().subject(subject).sessionId("sess-1").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("observer in different workspace from subject throws IllegalArgumentException")
    void observerWorkspaceMismatchRejected() {
        Workspace ws1 = ws();
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        PeerView subject = peer(ws1, "alice");
        PeerView observer = peer(ws2, "bob");

        assertThatThrownBy(() -> Representation.builder().subject(subject).observer(observer).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workspace");
    }

    @Test
    @DisplayName("negative tokenCount throws IllegalArgumentException")
    void negativeTokenCountRejected() {
        PeerView subject = peer(ws(), "alice");

        assertThatThrownBy(() -> Representation.builder().subject(subject).tokenCount(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tokenCount");
    }

    @Test
    @DisplayName("observations list is preserved")
    void observationsPreserved() {
        Workspace ws = ws();
        PeerView subject = peer(ws, "alice");
        Observation obs = Observation.builder().id(ObservationId.of(ws, "o1")).subject(subject)
                .observer(peer(ws, "bob")).content("hello").type(ObservationType.EXPLICIT).build();

        Representation rep = Representation.builder().subject(subject).observations(List.of(obs)).build();

        assertThat(rep.getObservations()).containsExactly(obs);
    }

    @Test
    @DisplayName("local representation may have null sessionId (cross-session scope)")
    void localWithNullSessionId() {
        Workspace ws = ws();
        Representation rep = Representation.builder().subject(peer(ws, "alice")).observer(peer(ws, "bob")).build();

        assertThat(rep.isLocal()).isTrue();
        assertThat(rep.getSessionId()).isEmpty();
    }
}
