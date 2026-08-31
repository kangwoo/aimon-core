package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ObservationId")
class ObservationIdTest {

    private Workspace ws() {
        return Workspace.builder().id("ws-1").build();
    }

    @Test
    @DisplayName("of(workspaceId, localId) constructs id from raw strings")
    void ofRawStrings() {
        ObservationId id = ObservationId.of("ws-1", "obs-42");

        assertThat(id.getWorkspaceId()).isEqualTo("ws-1");
        assertThat(id.getLocalId()).isEqualTo("obs-42");
    }

    @Test
    @DisplayName("of(Workspace, localId) derives workspace id from instance")
    void ofWorkspace() {
        ObservationId id = ObservationId.of(ws(), "obs-1");

        assertThat(id.getWorkspaceId()).isEqualTo("ws-1");
        assertThat(id.getLocalId()).isEqualTo("obs-1");
    }

    @Test
    @DisplayName("blank workspaceId throws IllegalArgumentException")
    void blankWorkspaceIdRejected() {
        assertThatThrownBy(() -> ObservationId.of("  ", "obs")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
    }

    @Test
    @DisplayName("blank localId throws IllegalArgumentException")
    void blankLocalIdRejected() {
        assertThatThrownBy(() -> ObservationId.of("ws-1", "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localId");
    }

    @Test
    @DisplayName("null arguments throw NullPointerException")
    void nullArgumentsRejected() {
        assertThatThrownBy(() -> ObservationId.of((String) null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ObservationId.of("ws", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ObservationId.of((Workspace) null, "x")).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("toString format is workspaceId:localId")
    void toStringFormat() {
        assertThat(ObservationId.of("ws", "local").toString()).isEqualTo("ws:local");
    }

    @Test
    @DisplayName("equals and hashCode use both workspaceId and localId")
    void equalsAndHashCode() {
        ObservationId a = ObservationId.of("ws-1", "x");
        ObservationId b = ObservationId.of("ws-1", "x");
        ObservationId differentLocal = ObservationId.of("ws-1", "y");
        ObservationId differentWorkspace = ObservationId.of("ws-2", "x");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(differentLocal);
        assertThat(a).isNotEqualTo(differentWorkspace);
    }
}
