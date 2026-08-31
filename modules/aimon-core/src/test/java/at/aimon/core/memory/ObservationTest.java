package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;

@DisplayName("Observation")
class ObservationTest {

    private Workspace ws() {
        return Workspace.builder().id("ws-1").build();
    }

    private PeerView peer(Workspace ws, String userId) {
        return PeerView.of(ws, Principal.user(userId, userId));
    }

    private Observation.Builder validBuilder() {
        Workspace ws = ws();
        return Observation.builder().id(ObservationId.of(ws, "obs-1")).subject(peer(ws, "alice"))
                .observer(peer(ws, "bob")).content("Alice prefers dark mode").type(ObservationType.EXPLICIT)
                .createdAt(Instant.parse("2024-01-15T10:00:00Z")).confidence(0.8d);
    }

    @Test
    @DisplayName("builder constructs observation with all fields")
    void builderConstructs() {
        Observation obs = validBuilder().sourceMessageIds(List.of("m1", "m2")).build();

        assertThat(obs.getContent()).isEqualTo("Alice prefers dark mode");
        assertThat(obs.getType()).isEqualTo(ObservationType.EXPLICIT);
        assertThat(obs.getConfidence()).isEqualTo(0.8d);
        assertThat(obs.getSourceMessageIds()).containsExactly("m1", "m2");
    }

    @Test
    @DisplayName("blank content throws IllegalArgumentException")
    void blankContentRejected() {
        assertThatThrownBy(() -> validBuilder().content("   ").build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    @DisplayName("confidence below 0 throws IllegalArgumentException")
    void confidenceBelowRangeRejected() {
        assertThatThrownBy(() -> validBuilder().confidence(-0.01d).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    @DisplayName("confidence above 1 throws IllegalArgumentException")
    void confidenceAboveRangeRejected() {
        assertThatThrownBy(() -> validBuilder().confidence(1.5d).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    @DisplayName("NaN confidence throws IllegalArgumentException")
    void confidenceNaNRejected() {
        assertThatThrownBy(() -> validBuilder().confidence(Double.NaN).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confidence");
    }

    @Test
    @DisplayName("subject in different workspace from id throws IllegalArgumentException")
    void subjectWorkspaceMismatchRejected() {
        Workspace ws1 = ws();
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        assertThatThrownBy(() -> Observation.builder().id(ObservationId.of(ws1, "obs-1")).subject(peer(ws2, "alice"))
                .observer(peer(ws1, "bob")).content("hi").type(ObservationType.EXPLICIT).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subject");
    }

    @Test
    @DisplayName("observer in different workspace from id throws IllegalArgumentException")
    void observerWorkspaceMismatchRejected() {
        Workspace ws1 = ws();
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        assertThatThrownBy(() -> Observation.builder().id(ObservationId.of(ws1, "obs-1")).subject(peer(ws1, "alice"))
                .observer(peer(ws2, "bob")).content("hi").type(ObservationType.EXPLICIT).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("observer");
    }

    @Test
    @DisplayName("withConfidence returns a new instance with updated confidence")
    void withConfidenceCreatesNewInstance() {
        Observation original = validBuilder().build();
        Observation updated = original.withConfidence(0.3d);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getConfidence()).isEqualTo(0.3d);
        assertThat(original.getConfidence()).isEqualTo(0.8d);
        assertThat(updated.getId()).isEqualTo(original.getId());
        assertThat(updated.getContent()).isEqualTo(original.getContent());
    }

    @Test
    @DisplayName("equals and hashCode are based on id only")
    void equalsBasedOnIdOnly() {
        Observation a = validBuilder().build();
        Observation b = validBuilder().content("totally different content").confidence(0.1d).build();
        Workspace ws = ws();
        Observation differentId = validBuilder().id(ObservationId.of(ws, "obs-other")).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(differentId);
    }
}
