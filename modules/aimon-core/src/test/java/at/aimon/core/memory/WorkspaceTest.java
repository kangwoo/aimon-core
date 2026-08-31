package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Workspace")
class WorkspaceTest {

    @Test
    @DisplayName("builder constructs workspace with all fields")
    void builderConstructsWorkspace() {
        Instant now = Instant.parse("2024-01-15T10:00:00Z");
        Workspace ws = Workspace.builder().id("ws-1").displayName("Acme").createdAt(now)
                .metadata(Map.of("region", "eu")).build();

        assertThat(ws.getId()).isEqualTo("ws-1");
        assertThat(ws.getDisplayName()).isEqualTo("Acme");
        assertThat(ws.getCreatedAt()).isEqualTo(now);
        assertThat(ws.getMetadata()).containsEntry("region", "eu");
    }

    @Test
    @DisplayName("displayName defaults to id when not set")
    void displayNameDefaultsToId() {
        Workspace ws = Workspace.builder().id("ws-default").build();

        assertThat(ws.getDisplayName()).isEqualTo("ws-default");
    }

    @Test
    @DisplayName("createdAt defaults to current instant when not set")
    void createdAtDefaultsToNow() {
        Workspace ws = Workspace.builder().id("ws-x").build();

        assertThat(ws.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("blank id throws IllegalArgumentException")
    void blankIdRejected() {
        assertThatThrownBy(() -> Workspace.builder().id("   ").build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("null id throws NullPointerException")
    void nullIdRejected() {
        assertThatThrownBy(() -> Workspace.builder().id(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null displayName throws NullPointerException")
    void nullDisplayNameRejected() {
        assertThatThrownBy(() -> Workspace.builder().displayName(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null createdAt throws NullPointerException")
    void nullCreatedAtRejected() {
        assertThatThrownBy(() -> Workspace.builder().createdAt(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null metadata throws NullPointerException")
    void nullMetadataRejected() {
        assertThatThrownBy(() -> Workspace.builder().metadata(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("equals and hashCode are based on id only")
    void equalsBasedOnIdOnly() {
        Workspace a = Workspace.builder().id("same").displayName("First").build();
        Workspace b = Workspace.builder().id("same").displayName("Second").build();
        Workspace c = Workspace.builder().id("other").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }
}
